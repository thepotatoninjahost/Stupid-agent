package host.vorv.watchdog

import android.content.Context
import android.util.Log
import java.io.File

/**
 * M-7 (code review, 2026-11): swiping the app away is not a crash.
 *
 * The watchdog sees the main process die exactly the same way in both cases.
 * This marker, written by [WatchdogService.onTaskRemoved], is the one signal
 * that says "the user closed it" — so the death gets no crash file, no
 * relaunch and no budget use.
 *
 * Internal storage, because the main process and the `:watchdog` process share
 * `filesDir`. A missing, unreadable or unparseable marker reads as false: a
 * damaged marker must look like a crash, never silence a real one.
 */
internal object UserCloseMarker {

    private const val TAG = "Vorv/UserClose"
    private const val DIR = "watchdog"
    private const val FILE = "user_closed"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    internal fun file(context: Context): File = File(context.filesDir, "$DIR/$FILE")

    fun mark(context: Context) {
        try {
            val file = file(context)
            file.parentFile?.mkdirs()
            file.writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not write the user-close marker: ${e.message}")
        }
    }

    /** True while the marker holds a parseable epoch no older than [maxAgeMs]. */
    fun isSet(context: Context, maxAgeMs: Long = MAX_AGE_MS): Boolean {
        val file = file(context)
        if (!file.exists()) return false
        val markedAt = try {
            file.readText().trim().toLongOrNull() ?: return false
        } catch (e: Exception) {
            Log.w(TAG, "could not read the user-close marker: ${e.message}")
            return false
        }
        return System.currentTimeMillis() - markedAt <= maxAgeMs
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            Log.w(TAG, "could not clear the user-close marker: ${e.message}")
        }
    }
}
