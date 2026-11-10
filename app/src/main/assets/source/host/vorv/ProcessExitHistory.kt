package host.vorv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.util.Date

/** Android retains a bounded, potentially incomplete history of process exits. */
internal object ProcessExitHistory {
    fun read(context: Context): Result<String> = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java)
            ?: error("ActivityManager unavailable")
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.processName == context.packageName }
            .take(20)
        buildString {
            appendLine("Android main-process exit history (newest first; records may be missing):")
            if (exits.isEmpty()) appendLine("No main-process exit records available.")
            exits.forEach { exit ->
                appendLine("time=${Date(exit.timestamp)} epoch_ms=${exit.timestamp} pid=${exit.pid} " +
                    "process=${exit.processName} reason=${reasonName(exit.reason)} (${exit.reason}) " +
                    "status=${exit.status} pss_kb=${exit.pss} rss_kb=${exit.rss}")
            }
            appendLine("Match PID and time to the incident. A signal can have multiple causes; " +
                "some devices report low-memory kills as signals. No native stack is included here.")
        }.trim()
    }

    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "java_crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unrecognized"
    }
}
