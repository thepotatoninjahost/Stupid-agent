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
 *   On the next launch, [MainActivity] reads boot_trace.txt. If the last line
 *   isn't "BOOT_COMPLETE", the previous launch crashed — and the last breadcrumb
 *   tells us where.
 *
 * USAGE:
 *   BootTracer.start(ctx)          // clears previous trace, writes "boot started"
 *   BootTracer.step(ctx, "geniex_init_begin")
 *   // ... do GenieX init ...
 *   BootTracer.step(ctx, "geniex_init_end")
 *   BootTracer.complete(ctx)       // writes "BOOT_COMPLETE"
 *
 * THREAD SAFETY:
 *   Single-threaded by design (only called from the main thread during onCreate).
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
     * Start a fresh boot trace. Clears any previous trace file.
     */
    fun start(ctx: Context) {
        val file = traceFile(ctx)
        file.parentFile?.mkdirs()
        // Don't delete — we need to read the OLD trace to detect a crash loop.
        // Instead, write a separator + new start marker.
        file.appendText("\n" + "=".repeat(60) + "\n")
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
     * after the UI is up. If this line is missing from the trace, the previous
     * launch crashed.
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
     * Increment the crash counter. Called when a previous-boot crash is detected
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
     * or the only section if there's just one.
     */
    fun readPreviousTrace(ctx: Context): String? {
        val full = runCatching {
            traceFile(ctx).takeIf { it.exists() }?.readText()
        }.getOrNull() ?: return null

        val sections = full.split("\n${"=".repeat(60)}\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // The last section is the current boot. The one before is the previous.
        return when {
            sections.size >= 2 -> sections[sections.size - 2]
            sections.size == 1 -> sections[0]
            else -> null
        }
    }

    /**
     * Check if the previous boot completed successfully.
     * Returns true if the previous trace ends with BOOT_COMPLETE.
     */
    fun previousBootCompleted(ctx: Context): Boolean {
        val prev = readPreviousTrace(ctx) ?: return true // no previous = first boot
        // Look at the last non-empty line of the previous trace.
        val lastLine = prev.lines().filter { it.isNotBlank() }.lastOrNull() ?: return true
        return lastLine.contains("BOOT_COMPLETE")
    }

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
