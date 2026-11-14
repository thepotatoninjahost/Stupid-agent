package host.vorv.computer

import java.io.File

/**
 * The app's own computer, on disk.
 *
 * One private tree that the agent treats as its machine: a home directory,
 * a place to put projects, a scratch area, a bin for its own tooling and a
 * log of everything it ran. Nothing here is shared, user-visible storage, so
 * no other app and no SAF provider can swap a file out from under a running
 * task, and no path is ever handed to a shell.
 *
 * Deliberately plain `java.io.File`: no Android types, so the whole thing is
 * exercised by JVM unit tests with a temp directory.
 */
class ComputerHome(val root: File) {

    val homeDir: File get() = dir("home")
    val projectsDir: File get() = dir("projects")
    val scratchDir: File get() = dir("tmp")
    val binDir: File get() = dir("bin")
    val logDir: File get() = dir("log")

    val auditFile: File get() = File(logDir, "audit.log")

    /** Bytes currently used under [root], counted without following links out. */
    fun usedBytes(): Long {
        if (!root.isDirectory) return 0L
        return root.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Refuses work that would push the workspace past [quotaBytes]. Quotas are
     * checked before a task starts, never by killing it halfway: a task that
     * dies mid-write is how you get a corrupt tree.
     */
    fun hasRoomFor(bytes: Long, quotaBytes: Long = DEFAULT_QUOTA): Boolean =
        usedBytes() + bytes <= quotaBytes

    /** Creates the standard tree. Returns a list of what could not be created. */
    fun provision(): List<String> =
        listOf(homeDir, projectsDir, scratchDir, binDir, logDir)
            .filterNot { it.isDirectory || it.mkdirs() }
            .map { "cannot create $it" }

    /**
     * A fresh, empty scratch directory for one task. Callers own cleanup; a
     * leftover directory is a bug the audit log will show, not a crash.
     */
    fun scratchFor(taskId: String): File =
        File(scratchDir, taskId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
            .ifEmpty { "task" })

    private fun dir(name: String): File = File(root, name)

    companion object {
        /** 512 MB. Big enough for real projects, small enough to notice. */
        const val DEFAULT_QUOTA: Long = 512L * 1024 * 1024
    }
}
