package host.vorv

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes timestamped breadcrumbs to a file at each step of app startup.
 *
 * WHY THIS EXISTS:
 *   The UncaughtExceptionHandler in [VorvApplication] only fires for Java/Kotlin
 *   exceptions. If a native library (e.g. a GenieX .so) fails to load with a
 *   SIGSEGV, the process is killed instantly — no Java code runs, no handler
 *   fires, no crash log is written. The app is a black box.
 *
 *   Breadcrumbs solve this: each startup step writes a line to boot_trace.txt
 *   BEFORE the step runs. If a native crash happens during step N, the file
 *   already contains "about to do step N" as its last line — telling us exactly
 *   which step killed the process.
 *
 *   On the next launch, [MainActivity] reads boot_trace.txt. A missing
 *   "BOOT_COMPLETE" marks an incomplete boot; it does not prove a crash.
 *
 * USAGE:
 *   BootTracer.start(ctx)          // clears previous trace, writes "boot started"
 *   BootTracer.step(ctx, "geniex_init_begin")
 *   // ... do GenieX init ...
 *   BootTracer.step(ctx, "geniex_init_end")
 *   BootTracer.complete(ctx)       // writes "BOOT_COMPLETE"
 *
 * THREAD SAFETY:
 *   GenieX callbacks write breadcrumbs from binder threads (audit N-L4,
 *   VRA-2026-1103), so every write is @Synchronized — lines can interleave
 *   in order but never corrupt (SimpleDateFormat is not thread-safe).
 *   Uses append mode with fsync after each write so the data is on disk before
 *   the next step runs.
 */
object BootTracer {

    private const val TRACE_FILE = "boot_trace.txt"
    private const val CRASH_COUNT_FILE = "crash_count.txt"

    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun traceFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), TRACE_FILE)

    fun crashCountFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), CRASH_COUNT_FILE)

    /**
     * Start a fresh boot trace. Writes a separator + the BOOT_STARTED marker.
     *
     * N-L2 (VRA-2026-1103): this runs BEFORE the crash handler is installed,
     * so every write is guarded — a disk-full or I/O failure here must not
     * take the app down unlogged.
     */
    @Synchronized
    fun start(ctx: Context) {
        runCatching {
            val file = traceFile(ctx)
            file.parentFile?.mkdirs()
            // Don't delete — we need to read the OLD trace to detect a crash loop.
            // Instead, write a separator + new start marker.
            file.appendText("\n" + "=".repeat(60) + "\n")
        }
        writeStep(ctx, "BOOT_STARTED", "pid=${android.os.Process.myPid()}")
    }

    /**
     * Write a breadcrumb. Call this BEFORE the step you're about to do.
     */
    fun step(ctx: Context, name: String, detail: String? = null) {
        writeStep(ctx, name, detail)
    }

    /**
     * Mark the boot as complete. Called at the end of MainActivity.onCreate
     * after the UI is up. A missing line means the launch did not complete;
     * it does not identify why.
     */
    fun complete(ctx: Context) {
        writeStep(ctx, "BOOT_COMPLETE", "ui is up")
        // Reset the crash counter on a successful boot — a successful launch
        // means the previous crash was transient or fixed.
        runCatching { crashCountFile(ctx).delete() }
    }

    /**
     * Read the crash counter. Returns 0 if no counter file exists.
     */
    fun getCrashCount(ctx: Context): Int {
        return runCatching {
            crashCountFile(ctx).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        }.getOrDefault(0)
    }

    /**
     * Increment the crash counter. Called when a previous boot was incomplete
     * (boot_trace.txt exists without a BOOT_COMPLETE marker).
     */
    fun incrementCrashCount(ctx: Context): Int {
        val current = getCrashCount(ctx)
        val next = current + 1
        runCatching { crashCountFile(ctx).writeText(next.toString()) }
        return next
    }

    /**
     * Read the full boot trace. Used by [MainActivity] to display diagnostics.
     */
    fun readTrace(ctx: Context): String {
        return runCatching {
            traceFile(ctx).takeIf { it.exists() }?.readText() ?: "(no trace file)"
        }.getOrDefault("(error reading trace)")
    }

    /**
     * Read the LAST boot trace (the one before the current boot). Used to
     * diagnose a crash on the previous launch. Returns null if no previous
     * trace exists.
     *
     * The trace file accumulates multiple boots separated by "====" lines.
     * This function returns the second-to-last section (the previous boot),
     * and returns null if there is no previous main-process boot.
     */
    fun readPreviousTrace(ctx: Context): String? {
        val full = runCatching {
            traceFile(ctx).takeIf { it.exists() }?.readText()
        }.getOrNull() ?: return null

        val sections = mainBootSections(full)
        return sections.getOrNull(sections.size - 2)
    }

    /** Repair traces written by older versions which started a section in the
     * watchdog process too. Main-process callbacks could follow that marker,
     * so preserve those lines as part of the preceding main boot. */
    internal fun mainBootSections(full: String): List<String> {
        val sections = mutableListOf<String>()
        val pidPattern = Regex("pid=(\\d+)")
        for (raw in full.split("\n${"=".repeat(60)}\n")) {
            val lines = raw.trim().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val start = lines.firstOrNull { it.contains(" BOOT_STARTED") }
            val pid = start?.let { pidPattern.find(it)?.groupValues?.get(1) }
            val skip = lines.firstOrNull {
                it.contains(" watchdog_process_skip") &&
                    pid != null && pidPattern.find(it)?.groupValues?.get(1) == pid
            }
            if (start != null && skip != null) {
                val remaining = lines.filter { it !== start && it !== skip }.joinToString("\n")
                if (sections.isNotEmpty() && remaining.isNotEmpty()) {
                    sections[sections.lastIndex] += "\n$remaining"
                }
            } else {
                sections.add(lines.joinToString("\n"))
            }
        }
        return sections
    }

    /**
     * Check if the previous boot completed successfully.
     * Returns true if the previous trace contains BOOT_COMPLETE. Asynchronous
     * callbacks can append breadcrumbs after completion.
     */
    fun previousBootCompleted(ctx: Context): Boolean {
        val prev = readPreviousTrace(ctx) ?: return true // no previous = first boot
        return prev.lines().any { it.contains(" BOOT_COMPLETE") }
    }

    @Synchronized
    private fun writeStep(ctx: Context, name: String, detail: String?) {
        runCatching {
            val ts = timestampFormat.format(Date())
            val line = if (detail != null) "[$ts] $name — $detail\n" else "[$ts] $name\n"
            val file = traceFile(ctx)
            file.parentFile?.mkdirs()
            file.appendText(line)
            // fsync via RandomAccessFile to ensure the write hits disk before
            // the next step runs (critical for native-crash breadcrumbs).
            try {
                val raf = java.io.RandomAccessFile(file, "rw")
                raf.fd.sync()
                raf.close()
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /**
     * Clear the crash counter and trace. Called after the user acknowledges
     * a crash report.
     */
    fun clear(ctx: Context) {
        runCatching { traceFile(ctx).delete() }
        runCatching { crashCountFile(ctx).delete() }
    }
}
