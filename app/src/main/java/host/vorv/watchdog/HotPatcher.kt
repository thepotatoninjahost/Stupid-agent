package host.vorv.watchdog

import android.content.Context
import android.os.Build
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HotPatcher — runtime code patching via DexClassLoader (hardened after
 * audit finding C-2).
 *
 * SECURITY POSTURE (v0.6.6, post-audit — READ BEFORE TOUCHING):
 *
 *   1. NOTHING loads at boot. VorvApplication no longer calls
 *      [loadPendingPatches] — a .dex in filesDir/hotpatch/ is inert data
 *      until it is BOTH cryptographically signed AND explicitly loaded by
 *      this method, which nothing in production currently does. Loading is
 *      disabled until a signing key is pinned below.
 *
 *   2. A patch is accepted ONLY when ALL of these hold:
 *        - [PINNED_ED25519_PUBLIC_KEY_HEX] is configured (non-empty);
 *        - the device supports Ed25519 in java.security (API 33+);
 *        - the manifest has an entry for the patch id with a sha256;
 *        - the dex's SHA-256 matches that entry;
 *        - the manifest entry carries a base64 Ed25519 signature over the
 *          dex bytes that verifies against the PINNED key.
 *      A dex with NO manifest entry is REFUSED and deleted — the v0.6.5
 *      behavior of loading it unverified (skip-on-missing-entry) is gone.
 *      Fail closed, never open.
 *
 *   3. A SHA-256 in a manifest that lives in the same directory as the dex
 *      is self-attestation, not authentication — which is why the
 *      signature against a key COMPILED INTO THE APP is required on top.
 *
 *   4. [fetchManifest] refuses any URL that is not HTTPS on an allow-listed
 *      host ([ALLOWED_MANIFEST_HOSTS], empty by default).
 *
 *   5. The previous "patches are signed (SHA-256 verified)" claim in this
 *      file's docs was wrong — a hash is not a signature. It has been
 *      replaced by this honest description.
 *
 *   6. Class-override patching (making a patched class run instead of the
 *      original) was never implemented — parent-first delegation means a
 *      DexClassLoader parented to the app classloader cannot override
 *      already-loadable classes. The pendingLoaders list and that claim are
 *      removed. If class-override patching is genuinely needed, adopt a
 *      maintained hot-patch framework instead of a hand-rolled chain.
 */
object HotPatcher {

    private const val PATCH_DIR = "hotpatch"
    private const val MANIFEST_FILE = "manifest.json"
    private const val APPLIED_FILE = "applied.json"

    /**
     * Ed25519 public key (hex) that patch dex files must be signed with.
     * EMPTY = hot-patching is disabled: loadPendingPatches refuses (and
     * deletes) everything. To enable, generate a keypair, embed the public
     * key here and sign patches with the private key offline.
     */
    private const val PINNED_ED25519_PUBLIC_KEY_HEX = ""

    /**
     * Hosts a patch manifest may be fetched from. Empty = no manifest
     * fetching is allowed at all (fail closed).
     */
    private val ALLOWED_MANIFEST_HOSTS = emptySet<String>()

    /**
     * Attempt to load verified pending patches. DISABLED in production:
     * returns an empty list and deletes any .dex found in the patch
     * directory unless every verification step in the class KDoc passes.
     * Kept as a hardened utility for the day signed patching is enabled.
     */
    fun loadPendingPatches(context: Context): List<String> {
        val applied = mutableListOf<String>()
        runCatching {
            val patchDir = File(context.filesDir, PATCH_DIR)
            if (!patchDir.exists()) return emptyList()

            val signingEnabled = PINNED_ED25519_PUBLIC_KEY_HEX.isNotEmpty() &&
                Build.VERSION.SDK_INT >= 33
            if (!signingEnabled) {
                val stale = patchDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
                    ?.toList().orEmpty()
                stale.forEach {
                    android.util.Log.w("Vorv/HotPatcher",
                        "Deleting unverified patch dex (hot-patching disabled): ${it.name}")
                    it.delete()
                }
                if (stale.isNotEmpty()) {
                    clearAppliedRegistry(patchDir)
                }
                return emptyList()
            }

            val manifest = readManifest(context)
            val appliedFile = File(patchDir, APPLIED_FILE)
            val appliedIds = if (appliedFile.exists()) {
                runCatching {
                    JSONObject(appliedFile.readText()).getJSONArray("applied")
                        .map { it.toString() }.toSet()
                }.getOrDefault(emptySet())
            } else emptySet()

            patchDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
                ?.forEach { dexFile ->
                    val patchId = dexFile.nameWithoutExtension
                    if (patchId !in appliedIds) return@forEach

                    // Manifest entry is REQUIRED — refuse (and delete) any
                    // dex lacking one (audit C-2: was loaded unverified).
                    val entry = manifest?.optJSONArray("patches")
                        ?.let { arr ->
                            (0 until arr.length()).map { i -> arr.getJSONObject(i) }
                                .firstOrNull { it.optString("id") == patchId }
                        }
                    if (entry == null) {
                        android.util.Log.e("Vorv/HotPatcher",
                            "REFUSED: no manifest entry for $patchId — deleting")
                        dexFile.delete()
                        return@forEach
                    }

                    val expectedSha = entry.optString("sha256")
                    if (expectedSha.isBlank()) {
                        android.util.Log.e("Vorv/HotPatcher",
                            "REFUSED: manifest entry for $patchId has no sha256 — deleting")
                        dexFile.delete()
                        return@forEach
                    }
                    val actualSha = sha256(dexFile)
                    if (actualSha != expectedSha) {
                        android.util.Log.e("Vorv/HotPatcher",
                            "REFUSED: SHA-256 mismatch for $patchId — deleting")
                        dexFile.delete()
                        return@forEach
                    }

                    val signatureB64 = entry.optString("signature")
                    if (!verifyEd25519(dexFile, signatureB64)) {
                        android.util.Log.e("Vorv/HotPatcher",
                            "REFUSED: Ed25519 signature missing/invalid for $patchId — deleting")
                        dexFile.delete()
                        return@forEach
                    }

                    val odexDir = File(context.codeCacheDir, "hotpatch_odex").apply { mkdirs() }
                    val loader = DexClassLoader(
                        dexFile.absolutePath,
                        odexDir.absolutePath,
                        null,
                        context.classLoader,
                    )
                    // No override list: nothing consults extra loaders (parent-first
                    // delegation cannot override app classes anyway — see KDoc).
                    // The load itself is recorded for diagnostics only.
                    applied.add(patchId)
                    android.util.Log.i("Vorv/HotPatcher", "Applied SIGNED patch: $patchId")
                }
        }.onFailure {
            android.util.Log.e("Vorv/HotPatcher", "Failed to load pending patches: ${it.message}", it)
        }
        return applied
    }

    /**
     * Read the local copy of the patch manifest.
     */
    private fun readManifest(context: Context): JSONObject? {
        return runCatching {
            val f = File(context.filesDir, "$PATCH_DIR/$MANIFEST_FILE")
            if (!f.exists()) null else JSONObject(f.readText())
        }.getOrNull()
    }

    /**
     * Fetch a patch manifest from a URL. Refuses anything that is not
     * HTTPS on an allow-listed host (audit C-2: previously accepted any
     * URL with no scheme or host validation).
     */
    suspend fun fetchManifest(url: String): JSONObject? {
        val allowed = runCatching {
            val uri = android.net.Uri.parse(url)
            val schemeOk = uri.scheme?.lowercase() == "https"
            val hostOk = uri.host in ALLOWED_MANIFEST_HOSTS
            if (!schemeOk || !hostOk) {
                android.util.Log.w("Vorv/HotPatcher",
                    "Manifest URL refused (not HTTPS or host not allow-listed): $url")
                return null
            }
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string() ?: return null)
            }
        }.getOrNull()
        return allowed
    }

    /**
     * Ed25519-verify [signatureB64] over the exact bytes of [dexFile],
     * against the pinned public key. Requires API 33+ (java.security
     * Ed25519); returns false on any failure — never loads on doubt.
     */
    private fun verifyEd25519(dexFile: File, signatureB64: String): Boolean {
        if (PINNED_ED25519_PUBLIC_KEY_HEX.isBlank() || signatureB64.isBlank()) return false
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val pub = java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(java.security.spec.X509EncodedKeySpec(hexToBytes(PINNED_ED25519_PUBLIC_KEY_HEX)))
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(pub)
            dexFile.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    sig.update(buf, 0, n)
                }
            }
            sig.verify(android.util.Base64.decode(signatureB64, android.util.Base64.NO_WRAP))
        }.getOrDefault(false)
    }

    private fun clearAppliedRegistry(patchDir: File) {
        runCatching { File(patchDir, APPLIED_FILE).delete() }
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// Extension to map JSONArray
private fun <T> org.json.JSONArray.map(transform: (Any) -> T): List<T> =
    (0 until length()).map { transform(get(it)) }
