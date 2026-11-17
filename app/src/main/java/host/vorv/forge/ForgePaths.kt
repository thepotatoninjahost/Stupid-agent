package host.vorv.forge

import android.content.Context
import java.io.File

/**
 * The app's own computer's file tree (docs/OWN-COMPUTER.md §1).
 *
 * Everything the toolchain builds lives under the app's private data dir and
 * nowhere else: not on the SD card, not in the user's SAF workspace, not in
 * shared storage the user (or any other app) can rename while a build is
 * running. That is the property that makes the old Termux dispatch wrapper —
 * and the injection class that went with it — structurally impossible.
 */
class ForgePaths(context: Context) {

    val root: File = File(context.filesDir, "forge")

    val toolchainDir: File get() = File(root, "toolchain")
    val libDir: File get() = File(toolchainDir, "lib")
    val jreDir: File get() = File(toolchainDir, "jre")
    val manifestFile: File get() = File(root, "toolchain.manifest")
    val stampFile: File get() = File(root, "toolchain.version")
    val workRoot: File get() = File(root, "work")
    val outDir: File get() = File(root, "out")
    val logDir: File get() = File(root, "log")

    fun workDir(runId: String): File = File(workRoot, sanitize(runId))

    fun ensureLayout(): List<String> =
        ensureDirs(listOf(root, toolchainDir, libDir, workRoot, outDir, logDir))

    companion object {
        /**
         * Creates what is missing; returns a line per directory that could not
         * be created. This is a `mkdirs()` per target — the previous version
         * tested `isDirectory && (exists() || mkdirs())`, whose left operand is
         * already false for a missing directory, so it never created anything
         * and reported every fresh tree as unmakeable.
         */
        internal fun ensureDirs(targets: List<File>): List<String> =
            targets.filterNot { it.isDirectory || it.mkdirs() }.map { "cannot create $it" }
    }

    /**
     * `runId` reaches the filesystem, so it is reduced to a conservative
     * character set here rather than trusted from the caller.
     */
    private fun sanitize(runId: String): String {
        val cleaned = runId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        return cleaned.ifEmpty { "run" }
    }
}
