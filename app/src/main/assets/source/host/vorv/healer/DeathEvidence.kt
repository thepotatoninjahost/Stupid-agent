package host.vorv.healer

import android.content.Context
import java.io.File

/**
 * Did the previous launch actually crash? A boot that never wrote
 * BOOT_COMPLETE is NOT an answer to that question.
 *
 * The app used to assume "previous boot did not finish" meant "crashed", and
 * told the owner "Vorv crashed last time" on a launch that had simply been
 * closed, swiped away or killed by Android for memory. The owner's answer —
 * "it never crashed" — was correct, and the app was wrong.
 *
 * A crash is claimed only when something independent says so:
 *  - Android's own exit history names a crash reason (CRASH, CRASH_NATIVE,
 *    ANR, INITIALIZATION_FAILURE) for an exit at/after the previous boot's
 *    start, or
 *  - a Java crash report was written during that boot, or
 *  - the watchdog wrote a death detection during that boot.
 *
 * Anything else (no record, user closed it, system killed it) is reported as
 * what it is: an unfinished session, not a crash.
 */
object DeathEvidence {

    data class Verdict(
        val crashed: Boolean,
        /** One line naming the evidence, or why there is none. */
        val detail: String,
    )

    /** Reasons Android gives for an exit that is genuinely a crash. */
    fun isCrashReason(reason: Int): Boolean = reason ==
        android.app.ApplicationExitInfo.REASON_CRASH ||
        reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
        reason == android.app.ApplicationExitInfo.REASON_ANR ||
        reason == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE

    /**
     * Pure decision, so the rule can be tested without a device.
     *
     * Times are epoch millis; null means "none was found". [prevBootStart] is
     * the start of the boot that did not finish; a record older than it
     * belongs to an earlier session and is not evidence for this one.
     */
    fun decide(
        prevBootStart: Long?,
        osReason: Int?,
        osAt: Long?,
        javaCrashAt: Long?,
        watchdogAt: Long?,
    ): Verdict {
        fun afterStart(t: Long?): Boolean =
            t != null && (prevBootStart == null || t >= prevBootStart)

        if (osReason != null && osAt != null && afterStart(osAt) && isCrashReason(osReason)) {
            return Verdict(true, "Android's exit history says ${NativeCrashHistory.reasonName(osReason)}")
        }
        if (afterStart(javaCrashAt)) {
            return Verdict(true, "a Java crash report was written during that session")
        }
        if (afterStart(watchdogAt)) {
            return Verdict(true, "the watchdog detected this process dying")
        }
        val why = when {
            osReason != null && osAt != null && afterStart(osAt) ->
                "Android's exit history says ${NativeCrashHistory.reasonName(osReason)} — ended, not crashed"
            osReason != null && osAt != null ->
                "Android's newest exit record is from an earlier session"
            else -> "Android kept no exit record for it"
        }
        return Verdict(false, "the previous boot did not finish, but nothing says it crashed: $why")
    }

    /** Gather the evidence from this phone and decide. */
    fun of(ctx: Context, prevBootStart: Long?): Verdict {
        val extDir = ctx.getExternalFilesDir(null)

        val osExit = runCatching { NativeCrashHistory.latestCrash(ctx) }.getOrNull()

        val javaCrashAt = extDir?.let { dir ->
            File(dir, "crash_logs").listFiles()
                ?.filter {
                    it.isFile && it.name.endsWith(".txt") &&
                        !it.name.startsWith("handled_") && !it.name.startsWith("crash_synthesized_")
                }
                ?.maxOfOrNull { it.lastModified() }
        }

        val watchdogAt = extDir?.let { dir ->
            File(dir, "watchdog").listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash_detected") && it.name.endsWith(".txt") }
                ?.maxOfOrNull { it.lastModified() }
        }

        return decide(
            prevBootStart = prevBootStart,
            osReason = osExit?.reason,
            osAt = osExit?.timestampMs,
            javaCrashAt = javaCrashAt,
            watchdogAt = watchdogAt,
        )
    }
}
