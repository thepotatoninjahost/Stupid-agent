package host.vorv.healer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real evidence about why the process died last time, read from Android itself
 * instead of inferred from breadcrumbs.
 *
 * Android keeps a per-app exit history ([ActivityManager.getHistoricalProcessExitReasons];
 * the entry type is the top-level [ApplicationExitInfo] on current SDKs)
 * that includes NATIVE crashes and ANRs — and, for a debuggable build, the
 * tombstone/ANR trace bytes. That is the tool the previous builds were missing,
 * which is why their reports said "unknown" and had to guess. Nothing here needs
 * root: the API only ever returns this app's own exits.
 *
 * Everything that cannot be read is reported as unavailable — never invented.
 */
object NativeCrashHistory {

    /** One recorded exit of this process. */
    data class Exit(
        val timestampMs: Long,
        val reason: Int,
        val reasonName: String,
        val description: String,
        val processName: String,
        val trace: String?,
        val traceUnavailableReason: String?,
    ) {
        val crashed: Boolean
            get() = reason == ApplicationExitInfo.REASON_CRASH ||
                reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                reason == ApplicationExitInfo.REASON_ANR
    }

    private const val MAX_TRACE_CHARS = 60_000

    /** Recent exits, newest first. Empty when the API refuses or there are none. */
    fun recent(ctx: Context, limit: Int = 10): List<Exit> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        return runCatching {
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, limit)
                .mapNotNull { info -> runCatching { read(info) }.getOrNull() }
                .sortedByDescending { it.timestampMs }
        }.getOrDefault(emptyList())
    }

    /**
     * The newest exit that needs explaining (crash or ANR) at or after [sinceMs].
     * [sinceMs] = 0 means "any".
     */
    fun latestCrash(ctx: Context, sinceMs: Long = 0L): Exit? =
        recent(ctx).firstOrNull { it.crashed && it.timestampMs >= sinceMs }

    private fun read(info: ApplicationExitInfo): Exit {
        var trace: String? = null
        var note: String? = null
        runCatching {
            info.traceInputStream?.use { input ->
                val bytes = input.readBytes()
                trace = String(bytes, Charsets.UTF_8).take(MAX_TRACE_CHARS)
            }
        }.onFailure { note = "the trace could not be read: ${it.message}" }
        if (trace == null && note == null) {
            note = "Android kept no trace for this exit (only debuggable builds keep tombstones)."
        }
        return Exit(
            timestampMs = info.timestamp,
            reason = info.reason,
            reasonName = reasonName(info.reason),
            description = info.description.orEmpty(),
            processName = info.processName.orEmpty(),
            trace = trace,
            traceUnavailableReason = note,
        )
    }

    /** The OS constant name, so a report never says "reason 5". */
    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (uncaught Java exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native tombstone)"
        ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    /** A report body that states what is fact and what is missing. */
    fun format(exit: Exit): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(exit.timestampMs))
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("Vorv crash report (REAL — from Android's exit history)")
        sb.appendLine("========================================")
        sb.appendLine("Process : ${exit.processName}")
        sb.appendLine("Died at : $stamp")
        sb.appendLine("Reason  : ${exit.reasonName}")
        if (exit.description.isNotBlank()) sb.appendLine("Detail  : ${exit.description}")
        sb.appendLine("========================================")
        if (exit.trace != null) {
            sb.appendLine("Trace supplied by the OS (verbatim):")
            sb.appendLine("========================================")
            sb.appendLine(exit.trace)
        } else {
            sb.appendLine("No trace from the OS: ${exit.traceUnavailableReason}")
            sb.appendLine("The reason above is still Android's own record, not a guess.")
        }
        return sb.toString()
    }
}
