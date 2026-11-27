package host.vorv.healer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The text of a Java crash report.
 *
 * Why this exists: the 2026-11-27 crash left Android's own record
 * ("CRASH (uncaught Java exception)") but NO file in `crash_logs/`, so neither
 * the crash dialog nor the agent could name the class, the message or the line.
 * A Java crash never produces an OS tombstone — `ApplicationExitInfo` only
 * supplies traces for ANRs and (from API 31) native crashes — so the stack has
 * to be written by the app itself, in the process that is dying.
 *
 * Kept pure so the exact shape can be tested without Android, and so the
 * synchronous report (written by the handler) and the fallback report (written
 * by [host.vorv.CrashReportWorker] in a fresh process) cannot drift apart.
 */
internal object CrashText {

    fun format(
        threadName: String,
        throwable: Throwable,
        bootTrace: String,
        at: Date = Date(),
    ): String = format(
        threadName = threadName,
        exceptionClass = throwable.javaClass.name,
        message = throwable.message ?: "",
        stackTrace = throwable.stackTraceToString(),
        bootTrace = bootTrace,
        at = at,
    )

    fun format(
        threadName: String,
        exceptionClass: String,
        message: String,
        stackTrace: String,
        bootTrace: String,
        at: Date = Date(),
        writtenBy: String = "the app's crash handler, before the process was killed",
    ): String = buildString {
        appendLine("========================================")
        appendLine("Vorv crash report (written by $writtenBy)")
        appendLine("========================================")
        appendLine("Timestamp: ${stamp(at)}")
        appendLine("Thread: $threadName")
        appendLine("Exception: $exceptionClass")
        appendLine("Message: $message")
        appendLine("========================================")
        appendLine("Stack trace (a Java crash has no OS tombstone — this is the only stack that exists):")
        appendLine("========================================")
        appendLine(stackTrace.trimEnd())
        appendLine()
        appendLine("========================================")
        appendLine("Boot trace (breadcrumbs from this launch):")
        appendLine("========================================")
        appendLine(bootTrace.trimEnd())
    }

    private fun stamp(at: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(at)
}
