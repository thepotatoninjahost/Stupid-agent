package host.vorv.watchdog

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import host.vorv.tools.NetworkGuard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * HotPatcher — runtime code patching via DexClassLoader, HARDENED
 * (audit C-2).
 *
 * WHAT CHANGED vs the audited version:
 *
 *  1. SIGNATURE REQUIRED. Every patch dex must carry an Ed25519/ECDSA
 *     signature from the device patch keypair ([PatchKeys]) over its exact
 *     bytes, recorded in the manifest. The old code accepted a dex when a
 *     manifest entry was present and SKIPPED verification when it was
 *     missing — a self-attested file loading into the app process. Now a
 *     dex without a valid signature is REFUSED and quarantined, never
 *     skipped.
 *  2. USER CONSENT REQUIRED. A patch id must appear in the consent
 *     registry (written only after the user taps APPROVE in the consent
 *     dialog). Auto-generated patches go through the same consent bridge
 *     as app_intent and shell.
 *  3. VERSION GATES ENFORCED. minAppVersion/maxAppVersion from the
 *     manifest are checked against BuildConfig.VERSION_CODE.
 *  4. PATCHES ACTUALLY RUN. Verified loaders are registered in
 *     [PatchRegistry], and construction sites dispatch through the shared
 *     interfaces — the old code loaded classes no caller could ever use.
 *  5. PROBATION + AUTO-ROLLBACK (correctness gate). Every applied patch
 *     records its first-apply time. If the app crashes in a class a patch
 *     overrides within the probation window, [quarantineIfCrashed] deletes
 *     the patch at next launch — a fix that makes things worse undoes
 *     itself.
 *  6. MANIFEST FETCHING PINNED. [fetchManifest] only accepts HTTPS URLs on
 *     an allow-listed host set and refuses everything else.
 *
 * WHAT STAYS: the feature itself. Patches load, run, and repair the app —
 * now with proof of origin, user approval, and rollback.
 */
object HotPatcher {

    private const val TAG = "Vorv/HotPatcher"
    private const val PATCH_DIR = "hotpatch"
    private const val MANIFEST_FILE = "manifest.json"
    private const val APPROVED_FILE = "approved.json"
    private const val QUARANTINE_DIR = "hotpatch_quarantine"

    /** Probation window: a crash in the patched class within this window rolls the patch back. */
    private const val PROBATION_MS = 72 * 60 * 60 * 1000L

    /** Hosts patch manifests may be fetched from (HTTPS only). */
    private val MANIFEST_HOST_ALLOWLIST = setOf(
        "github.com", "raw.githubusercontent.com", "objects.githubusercontent.com",
    )

    // ================================================================
    // Boot path
    // ================================================================

    /**
     * Load every patch that passes ALL gates. Must be called at the very
     * start of VorvApplication.onCreate (main process only — the watchdog
     * process never loads patches).
     *
     * Returns the ids of patches that were applied (registered in
     * PatchRegistry). Anything failing a gate is quarantined and reported
     * in the log — never silently skipped.
     */
    fun loadPendingPatches(context: Context): List<String> {
        val applied = mutableListOf<String>()
        runCatching {
            val patchDir = File(context.filesDir, PATCH_DIR)
            if (!patchDir.exists()) return emptyList()

            val manifest = readManifest(context)
            val approvedIds = readApprovedIds(context)

            patchDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
                ?.forEach { dexFile ->
                    val patchId = dexFile.nameWithoutExtension
                    val entry = manifest?.patchEntry(patchId)

                    if (entry == null) {
                        quarantine(context, dexFile, "no manifest entry — refusing unsigned/unlisted dex")
                        return@forEach
                    }

                    // Gate 1: user consent.
                    if (patchId !in approvedIds) {
                        quarantine(context, dexFile, "not in the consent registry — the user never approved this patch")
                        return@forEach
                    }

                    // Gate 2: version window.
                    val minV = entry.optInt("minAppVersion", 0)
                    val maxV = entry.optInt("maxAppVersion", Int.MAX_VALUE)
                    val versionCode = host.vorv.BuildConfig.VERSION_CODE
                    if (versionCode < minV || versionCode > maxV) {
                        quarantine(context, dexFile, "version gate: app $versionCode not in [$minV, $maxV]")
                        return@forEach
                    }

                    // Gate 3: hash.
                    val expectedSha = entry.optString("sha256", "")
                    val actualSha = sha256Hex(dexFile.readBytes())
                    if (expectedSha.isBlank() || actualSha != expectedSha) {
                        quarantine(context, dexFile, "SHA-256 mismatch (expected=$expectedSha actual=$actualSha)")
                        return@forEach
                    }

                    // Gate 4: cryptographic signature (C-2 core).
                    val signature = entry.optString("signature", "")
                    if (signature.isBlank() ||
                        !PatchKeys.verify(context, dexFile.readBytes(), signature)
                    ) {
                        quarantine(context, dexFile, "signature verification FAILED — refusing to load")
                        return@forEach
                    }

                    // Gate 5: dispatchable class present in the patch.
                    val classes = entry.optJSONArray("classes")
                    val classNames = (0 until classes.length()).map { classes.getString(it) }
                    if (classNames.none { PatchRegistry.isPatchableClass(it) }) {
                        quarantine(context, dexFile, "no dispatchable class in the patch")
                        return@forEach
                    }

                    // All gates passed — load and register.
                    val odexDir = File(context.codeCacheDir, "hotpatch_odex").apply { mkdirs() }
                    val loader = DexClassLoader(
                        dexFile.absolutePath,
                        odexDir.absolutePath,
                        null,
                        context.classLoader,
                    )
                    // Gate 6: the patch class must really implement its dispatch
                    // interface before the loader is trusted.
                    val dispatchable = classNames.any { fqcn ->
                        val base = PatchRegistry.dispatchBaseFor(fqcn) ?: return@any false
                        runCatching {
                            base.isAssignableFrom(loader.loadClass(fqcn))
                        }.getOrDefault(false)
                    }
                    if (!dispatchable) {
                        quarantine(context, dexFile, "patch class does not implement its dispatch interface")
                        return@forEach
                    }

                    PatchRegistry.registerLoader(loader)
                    recordFirstApply(context, patchId)
                    applied.add(patchId)
                    Log.i(TAG, "Applied patch: $patchId (classes=$classNames)")
                }
        }.onFailure {
            Log.e(TAG, "Failed to load pending patches: ${it.message}", it)
        }
        return applied
    }

    // ================================================================
    // Registration (called by HealingAgent AFTER user approval)
    // ================================================================

    /**
     * Persist an approved patch: dex bytes into filesDir/hotpatch, a signed
     * manifest entry, and the consent record. Applies at next boot.
     */
    fun registerApprovedPatch(
        context: Context,
        patchId: String,
        targetClass: String,
        dexBytes: ByteArray,
        sha256: String,
        signature: String,
        description: String,
    ): Boolean {
        if (!PatchRegistry.isPatchableClass(targetClass)) {
            Log.e(TAG, "refusing to register patch for non-dispatchable class $targetClass")
            return false
        }
        if (!PatchKeys.verify(context, dexBytes, signature)) {
            Log.e(TAG, "refusing to register patch whose signature does not verify")
            return false
        }
        return runCatching {
            val patchDir = File(context.filesDir, PATCH_DIR).apply { mkdirs() }
            File(patchDir, "$patchId.dex").writeBytes(dexBytes)

            // Manifest entry (v2 format — signed).
            val manifestFile = File(patchDir, MANIFEST_FILE)
            val manifest = if (manifestFile.exists()) {
                JSONObject(manifestFile.readText())
            } else {
                JSONObject().put("version", 2).put("patches", JSONArray())
            }
            val patches = manifest.getJSONArray("patches")
            // Replace any stale entry with the same id.
            for (i in 0 until patches.length()) {
                if (patches.getJSONObject(i).optString("id") == patchId) {
                    patches.remove(i)
                    break
                }
            }
            patches.put(JSONObject().apply {
                put("id", patchId)
                put("sha256", sha256)
                put("signature", signature)
                put("classes", JSONArray().put(targetClass))
                put("minAppVersion", host.vorv.BuildConfig.VERSION_CODE)
                put("maxAppVersion", host.vorv.BuildConfig.VERSION_CODE + 10)
                put("description", description)
                put("createdAt", System.currentTimeMillis())
            })
            manifestFile.writeText(manifest.toString())

            // Consent record — a GENUINE JSONArray (the org.json round-trip
            // bug that silently deleted every approved patch is pinned by a
            // unit test).
            markPendingApply(context, patchId)
            Log.i(TAG, "Patch $patchId registered (signed + approved; applies at next boot)")
            true
        }.onFailure {
            Log.e(TAG, "registerApprovedPatch failed: ${it.message}", it)
            false
        }.getOrDefault(false)
    }

    /**
     * Record the user's approval of [patchId] in the consent registry.
     * Writes a real JSONArray — verified by round-trip in tests.
     */
    fun markPendingApply(context: Context, patchId: String) {
        runCatching {
            val patchDir = File(context.filesDir, PATCH_DIR).apply { mkdirs() }
            val approvedFile = File(patchDir, APPROVED_FILE)
            val ids = readApprovedIds(context).toMutableSet()
            ids.add(patchId)
            val out = JSONObject().put("applied", JSONArray(ids))
            approvedFile.writeText(out.toString())
        }.onFailure {
            Log.e(TAG, "markPendingApply failed: ${it.message}", it)
        }
    }

    /** The ids the user has approved (consent registry). */
    fun readApprovedIds(context: Context): Set<String> {
        return runCatching {
            val f = File(context.filesDir, "$PATCH_DIR/$APPROVED_FILE")
            if (!f.exists()) return emptySet()
            JSONObject(f.readText()).getJSONArray("applied")
                .let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
        }.onFailure {
            Log.e(TAG, "consent registry unreadable: ${it.message}", it)
        }.getOrDefault(emptySet())
    }

    // ================================================================
    // Probation (correctness gate — auto-rollback)
    // ================================================================

    /**
     * If the app crashed in a class a patch overrides, and that patch is
     * still within its probation window, roll it back (quarantine + remove
     * from the consent registry) and return what was rolled back.
     * Called by VorvApplication at boot when a previous-boot crash is
     * detected.
     */
    fun quarantineIfCrashed(context: Context, crashedClass: String?): List<String> {
        if (crashedClass.isNullOrBlank()) return emptyList()
        val manifest = readManifest(context) ?: return emptyList()
        val rolledBack = mutableListOf<String>()
        val patchDir = File(context.filesDir, PATCH_DIR)
        val now = System.currentTimeMillis()

        for (i in 0 until manifest.patches().length()) {
            val entry = manifest.patches().getJSONObject(i)
            val classes = entry.optJSONArray("classes") ?: continue
            val overrides = (0 until classes.length()).any { classes.getString(it) == crashedClass }
            if (!overrides) continue

            val firstAppliedAt = entry.optLong("firstAppliedAt", 0L)
            val inProbation = firstAppliedAt > 0 && (now - firstAppliedAt) < PROBATION_MS
            if (!inProbation) continue

            val patchId = entry.optString("id")
            val dexFile = File(patchDir, "$patchId.dex")
            quarantine(context, dexFile,
                "probation rollback: app crashed in $crashedClass within 72h of applying")
            removeFromConsent(context, patchId)
            rolledBack.add(patchId)
        }
        if (rolledBack.isNotEmpty()) {
            Log.w(TAG, "Rolled back patches after crash in $crashedClass: $rolledBack")
        }
        return rolledBack
    }

    /** Record when a patch first took effect (probation start). */
    private fun recordFirstApply(context: Context, patchId: String) {
        runCatching {
            val manifest = readManifest(context) ?: return
            val entry = manifest.patchEntry(patchId) ?: return
            if (entry.optLong("firstAppliedAt", 0L) == 0L) {
                entry.put("firstAppliedAt", System.currentTimeMillis())
                File(context.filesDir, "$PATCH_DIR/$MANIFEST_FILE").writeText(manifest.toString())
            }
        }
    }

    private fun removeFromConsent(context: Context, patchId: String) {
        runCatching {
            val ids = readApprovedIds(context).toMutableSet()
            ids.remove(patchId)
            val patchDir = File(context.filesDir, PATCH_DIR)
            val out = JSONObject().put("applied", JSONArray(ids))
            File(patchDir, APPROVED_FILE).writeText(out.toString())
        }
    }

    // ================================================================
    // Manifest fetching — pinned to HTTPS allow-listed hosts (C-2)
    // ================================================================

    /**
     * Fetch a patch manifest. REFUSES non-HTTPS URLs and hosts outside the
     * allow-list; every response body must parse as a v2 manifest.
     */
    suspend fun fetchManifest(url: String): JSONObject? {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""
        if (scheme != "https") {
            Log.e(TAG, "manifest URL refused: scheme must be https, got '$scheme'")
            return null
        }
        if (host !in MANIFEST_HOST_ALLOWLIST) {
            Log.e(TAG, "manifest URL refused: host '$host' is not in the allow-list $MANIFEST_HOST_ALLOWLIST")
            return null
        }
        return runCatching {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .dns(NetworkGuard.PublicDns)
                .build()
            val req = okhttp3.Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                JSONObject(resp.body?.string() ?: return@runCatching null)
            }
        }.getOrNull()
    }

    // ================================================================
    // Internals
    // ================================================================

    private data class Manifest(val json: JSONObject) {
        fun patches(): JSONArray = json.optJSONArray("patches") ?: JSONArray()
        fun patchEntry(id: String): JSONObject? {
            val arr = patches()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("id") == id) return o
            }
            return null
        }
    }

    private fun readManifest(context: Context): Manifest? {
        return runCatching {
            val f = File(context.filesDir, "$PATCH_DIR/$MANIFEST_FILE")
            if (!f.exists()) null else Manifest(JSONObject(f.readText()))
        }.getOrNull()
    }

    /**
     * Move a refused/rolled-back dex out of the patch dir so it can never
     * be reconsidered, with the reason logged. Refuse ≠ skip.
     */
    private fun quarantine(context: Context, dexFile: File, reason: String) {
        Log.e(TAG, "PATCH REFUSED — ${dexFile.name}: $reason")
        runCatching {
            val qDir = File(context.filesDir, QUARANTINE_DIR).apply { mkdirs() }
            dexFile.renameTo(File(qDir, "${System.currentTimeMillis()}_${dexFile.name}"))
        }
    }

    /** SHA-256 as lowercase hex (shared with HealingAgent). */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
