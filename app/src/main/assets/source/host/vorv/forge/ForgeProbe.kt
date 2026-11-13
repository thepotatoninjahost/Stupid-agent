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
        File(context.applicationInfo.nativeLibraryDir, "libforgejvm.so")

    fun gates(context: Context, paths: ForgePaths = ForgePaths(context)): List<Gate> {
        val runtime = runtimeExecutable(context)
        val libDir = paths.libDir
        val manifest = paths.manifestFile
        val libs = listOf(
            "kotlin-compiler-embeddable-2.4.21.jar",
            "kotlin-stdlib-2.4.21.jar",
            "kotlinx-coroutines-core-jvm-1.11.0.jar",
            "annotations-26.0.2.jar",
            "d8.jar",
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
            Gate(
                name = "Inventory",
                ok = manifest.isFile,
                detail = if (manifest.isFile) "signed inventory present" else "toolchain.manifest missing",
            ),
            Gate(
                name = "Workspace",
                ok = paths.root.isDirectory,
                detail = if (paths.root.isDirectory) "private compute root ready" else "not extracted yet",
            ),
        )
    }

    /** One line for the model/tool layer: null when the forge is usable. */
    fun readinessError(context: Context): String? {
        val bad = gates(context).filterNot { it.ok }
        if (bad.isEmpty()) return null
        return "Forge not ready: " + bad.joinToString("; ") { "${it.name} — ${it.detail}" }
    }
}
