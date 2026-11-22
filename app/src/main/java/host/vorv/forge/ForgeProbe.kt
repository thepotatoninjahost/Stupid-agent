package host.vorv.forge

import android.content.Context
import java.io.File

/**
 * Readiness for the app's own computer — the replacement for the Termux gates
 * (`TermuxProbe.termuxInstalled`, `runCommandGranted`, `readinessError`).
 *
 * Nothing here asks the user to install anything: the only inputs are the
 * files this APK shipped and the runtime it can execute.
 */
object ForgeProbe {

    data class Gate(val name: String, val ok: Boolean, val detail: String)

    /**
     * Mode A executes a private runtime from `nativeLibraryDir` (the only
     * location modern Android permits execve from for a bundled interpreter).
     */
    fun runtimeExecutable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libforge-java.so")

    fun gates(context: Context, paths: ForgePaths = ForgePaths(context)): List<Gate> {
        val runtime = runtimeExecutable(context)
        // The compiler, dexer and API jar are APK assets until the payload
        // is provisioned into filesDir/forge/tools (ForgeRuntime.provision).
        val libDir = ForgeRuntime(context).toolsDir
        val libs = listOf(
            "kotlin-compiler-embeddable-2.4.21.jar",
            "kotlin-stdlib-2.4.21.jar",
            "kotlinx-coroutines-core-jvm-1.11.0.jar",
            "annotations-26.0.2.jar",
            "d8.jar",
            "android.jar",
            "apksigner.jar",
        )
        val missingLibs = libs.filterNot { File(libDir, it).isFile }
        return listOf(
            Gate(
                name = "Runtime",
                ok = runtime.isFile && runtime.canExecute(),
                detail = if (runtime.isFile) "ready" else "runtime missing from the APK",
            ),
            Gate(
                name = "Toolchain",
                ok = missingLibs.isEmpty(),
                detail = if (missingLibs.isEmpty()) "ready" else "missing: ${missingLibs.joinToString(", ")}",
            ),
            inventory(context, paths),
            Gate(
                name = "Workspace",
                ok = paths.root.isDirectory,
                detail = if (paths.root.isDirectory) "private compute root ready" else "not extracted yet",
            ),
        )
    }

    /**
     * The shipped inventory compared with the files actually provisioned.
     *
     * Until 2026-11 this gate only asked whether a manifest file existed, and
     * nothing ever wrote one, so it could never pass and no hash was ever
     * checked. The manifest is now generated at build time and verified here.
     * The result is cached per provisioned payload: hashing ~250 MB on every
     * readiness call would be a stall, and the `payload` stamp changes exactly
     * when the provisioned tree does.
     */
    @Volatile private var inventoryCache: Pair<Long, Gate>? = null

    private fun inventory(context: Context, paths: ForgePaths): Gate {
        val stamp = File(paths.root, "payload").lastModified()
        inventoryCache?.let { (at, gate) -> if (at == stamp) return gate }
        val gate = computeInventory(context, paths)
        inventoryCache = stamp to gate
        return gate
    }

    private fun computeInventory(context: Context, paths: ForgePaths): Gate {
        val text = try {
            context.assets.open("forge/toolchain.manifest").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            return Gate("Inventory", false, "this build ships no toolchain.manifest — nothing can vouch for the payload")
        }
        val parsed = ToolchainManifest.parse(text)
        if (parsed is ToolchainManifest.Parsed.Invalid) {
            return Gate("Inventory", false, "toolchain.manifest is invalid: ${parsed.reason}")
        }
        val entries = (parsed as ToolchainManifest.Parsed.Ok).entries
        val problems = ToolchainManifest.verify(entries, paths.root, ::sha256)
        if (problems.isEmpty()) {
            return Gate("Inventory", true, "${entries.size} payload file(s) match their sha256")
        }
        val shown = problems.take(3).joinToString("; ")
        val rest = if (problems.size > 3) " (+${problems.size - 3} more)" else ""
        return Gate("Inventory", false, shown + rest)
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** One line for the model/tool layer: null when the forge is usable. */
    fun readinessError(context: Context): String? {
        val bad = gates(context).filterNot { it.ok }
        if (bad.isEmpty()) return null
        return "Forge not ready: " + bad.joinToString("; ") { "${it.name} — ${it.detail}" }
    }
}
