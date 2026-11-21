package host.vorv.watchdog

import android.content.Context
import android.util.Log
import java.io.File

/**
 * M-7 (code review, 2026-11): one death, one report.
 *
 * The same main-process death is detected twice — binder death, then the
 * stale-heartbeat check — and each used to write its own
 * `.crash_handled_<ts>` file and relaunch. This is one time-based claim
 * instead: the first detection handles the death, and any detection inside
 * [WINDOW_MS] is the same death and does nothing.
 *
 * Internal storage, shared by both processes of the app.
 */
internal object CrashEvents {

    private const val TAG = "Vorv/CrashEvents"
    private const val DIR = "watchdog"
    private const val FILE = ".crash_handled_at"
    private const val WINDOW_MS = 30_000L

    internal fun file(context: Context): File = File(context.filesDir, "$DIR/$FILE")

    /**
     * True when this caller owns the death and must handle it; false when it
     * was already handled inside [windowMs]. An unreadable marker is treated
     * as "not handled yet" — reporting a death twice is worse than losing the
     * claim, but silently dropping a crash is worse than both.
     */
    fun claim(context: Context, source: String, windowMs: Long = WINDOW_MS): Boolean {
        val file = file(context)
        val handledAt = if (file.exists()) {
            try {
                file.readText().lineSequence().firstOrNull()?.trim()?.toLongOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "could not read the crash claim: ${e.message}")
                null
            }
        } else {
            null
        }
        val now = System.currentTimeMillis()
        // Strictly inside the window: a window of 0 means "claim every death",
        // and two claims in the same millisecond must not swallow the second.
        if (handledAt != null && now - handledAt < windowMs) return false
        try {
            file.parentFile?.mkdirs()
            file.writeText("$now\nsource=$source\n")
        } catch (e: Exception) {
            Log.w(TAG, "could not write the crash claim: ${e.message}")
        }
        return true
    }
}
