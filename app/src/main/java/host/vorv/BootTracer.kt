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
 *   GenieX callbacks write breadcrumbs from binder threads (audit N-L4,
 *   VRA-2026-1103), so every write is @Synchronized — lines can interleave
 *   in order but never corrupt (SimpleDateFormat is not thread-safe).
 *   Uses append mode with fsync after each write so the data is on disk before
 *   the next step runs.
 */
object BootTracer {

    private const val TRACE_FILE = "boot_trace.txt"

    /**
     * Hard cap on the trace file. It is appended on every launch and never
     * used to be trimmed, so on a phone that has launched the app hundreds of
     * times every read of it (and every crash report that embeds it) grew with
     * it — straight into an out-of-memory kill of the process during startup,
     * which then looks like "the previous boot did not complete" and loops.
     * The tail is what matters; history beyond [MAX_BYTES] is dropped.
     */
    internal const val MAX_BYTES = 128 * 1024

    /** How much of the previous trace a crash report embeds. */
    internal const val EMBED_BYTES = 8 * 1024
    private const val INSTALL_ID_FILE = "boot_install_id.txt"
    private const val CRASH_COUNT_FILE = "crash_count.txt"

    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * [processSuffix] names the file for a non-main process. The watchdog gets
     * its own trace: it writes BOOT_STARTED seconds after the main process and
     * never writes BOOT_COMPLETE, so sharing one file made the "previous boot"
     * of a crash report the watchdog's two-line section instead of the session
     * that died (2026-11-27: pid 2768 in the trace, pid 2733 in the crash).
     */
    fun traceFile(ctx: Context, processSuffix: String? = null): File =
        File(ctx.getExternalFilesDir(null), traceFileName(processSuffix))

    internal fun traceFileName(processSuffix: String?): String =
        if (processSuffix.isNullOrBlank()) TRACE_FILE else "boot_trace_$processSuffix.txt"

    fun crashCountFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), CRASH_COUNT_FILE)

    /**
     * Start a fresh boot trace. Writes a separator + the BOOT_STARTED marker.
     *
     * N-L2 (VRA-2026-1103): this runs BEFORE the crash handler is installed,
     * so every write is guarded — a disk-full or I/O failure here must not
     * take the app down unlogged.
     */
    /**
     * A per-install identity, so a trace left behind by an EARLIER build of
     * the app is never read as "this install crashed".
     *
     * Why: traces live in the external files directory, which survives an
     * uninstall/reinstall on Android. A trace from a build that was removed
     * (a previous version that hung on its loading screen) therefore looked
     * like a crash of the brand-new install — the app opened for the first
     * time and announced "Vorv crashed last time" about a session that
     * belonged to a different build. Sections carrying another install's id
     * are history, not evidence.
     */
    fun installId(ctx: Context): String {
        val file = File(ctx.filesDir, INSTALL_ID_FILE)
        runCatching { file.takeIf { it.exists() }?.readText()?.trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val created = java.util.UUID.randomUUID().toString()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(created)
        }
        return created
    }

    @Synchronized
    fun start(ctx: Context, processSuffix: String? = null) {
        runCatching {
            val file = traceFile(ctx, processSuffix)
            file.parentFile?.mkdirs()
            // Don't delete — we need to read the OLD trace to detect a crash loop.
            // Instead, write a separator + new start marker.
            file.appendText("\n" + "=".repeat(60) + "\n")
        }
        trimIfHuge(ctx, processSuffix)
        writeStep(
            ctx,
            "BOOT_STARTED",
            "pid=${android.os.Process.myPid()} install=${installId(ctx)} epoch=${System.currentTimeMillis()}",
            processSuffix,
        )
    }

    /**
     * Write a breadcrumb. Call this BEFORE the step you're about to do.
     */
    fun step(ctx: Context, name: String, detail: String? = null, processSuffix: String? = null) {
        writeStep(ctx, name, detail, processSuffix)
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
     * Read the boot trace, bounded to the last [MAX_BYTES]. Used by
     * [MainActivity] to display diagnostics.
     */
    fun readTrace(ctx: Context): String {
        return runCatching {
            traceFile(ctx).takeIf { it.exists() }?.let { tailOf(it, MAX_BYTES) } ?: "(no trace file)"
        }.getOrDefault("(error reading trace)")
    }

    /** Last [max] bytes of the file, never the whole thing. */
    internal fun tailOf(file: File, max: Int): String = runCatching {
        val len = file.length()
        if (len <= max) return@runCatching file.readText()
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - max)
            val bytes = ByteArray(max)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")

    /**
     * Keep the trace bounded. Called at the start of every boot so the file
     * cannot grow without limit: the newest sections (the ones a crash report
     * needs) are kept, older ones are dropped.
     */
    internal fun trimIfHuge(ctx: Context, processSuffix: String? = null) {
        runCatching {
            val file = traceFile(ctx, processSuffix)
            if (!file.exists() || file.length() <= MAX_BYTES) return
            // Trim to half the cap: the file is appended to on every boot, so
            // cutting exactly at the cap would exceed it again immediately.
            val tail = tailOf(file, MAX_BYTES / 2)
            // Drop the partial first section so the file still starts cleanly.
            val firstSep = tail.indexOf("\n" + "=".repeat(60) + "\n")
            file.writeText(if (firstSep >= 0) tail.substring(firstSep) else tail)
        }
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
            traceFile(ctx).takeIf { it.exists() }?.let { tailOf(it, MAX_BYTES) }
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
        // A section from a different install (or from before install ids
        // existed) says nothing about THIS install: ignore it.
        return sectionCompleted(prev, installId(ctx))
    }

    /**
     * Epoch millis at which the previous boot started, when the trace records
     * it. Used to tell "crashed during that session" from "a record from an
     * older session". Null when the trace predates epoch stamps.
     */
    fun previousBootStartEpoch(ctx: Context): Long? {
        val prev = readPreviousTrace(ctx) ?: return null
        return sectionStartEpoch(prev)
    }

    internal fun sectionStartEpoch(section: String): Long? {
        val line = section.lines().firstOrNull { it.contains("BOOT_STARTED") } ?: return null
        return Regex("epoch=(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    /**
     * Pure decision for one trace section: did THIS install's previous boot
     * finish? A section without this install's marker is not evidence.
     */
    internal fun sectionCompleted(section: String, id: String): Boolean {
        if (!section.contains("install=$id")) return true
        val lastLine = section.lines().filter { it.isNotBlank() }.lastOrNull() ?: return true
        return lastLine.contains("BOOT_COMPLETE")
    }

    @Synchronized
    private fun writeStep(
        ctx: Context,
        name: String,
        detail: String?,
        processSuffix: String? = null,
    ) {
        runCatching {
            val ts = timestampFormat.format(Date())
            val line = if (detail != null) "[$ts] $name — $detail\n" else "[$ts] $name\n"
            val file = traceFile(ctx, processSuffix)
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
