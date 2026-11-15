package host.vorv.healer

import android.app.ApplicationExitInfo
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The old crash reports said "unknown" because they had no way to ask the OS.
 * These pin the honest report shape: Android's own reason is stated as fact,
 * and a missing trace is named as missing.
 */
class NativeCrashHistoryTest {

    private fun exit(trace: String?, note: String?) = NativeCrashHistory.Exit(
        timestampMs = 1_700_000_000_000,
        reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
        reasonName = NativeCrashHistory.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE),
        description = "signal 11 (SIGSEGV)",
        processName = "host.vorv",
        trace = trace,
        traceUnavailableReason = note,
    )

    @Test
    fun `a native crash is named as such`() {
        assertTrue(NativeCrashHistory.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE).contains("NATIVE"))
        assertTrue(NativeCrashHistory.reasonName(ApplicationExitInfo.REASON_ANR).contains("ANR"))
        assertTrue(NativeCrashHistory.reasonName(ApplicationExitInfo.REASON_LOW_MEMORY).contains("LOW_MEMORY"))
    }

    @Test
    fun `a report with an OS tombstone is labelled real and quotes it`() {
        val text = NativeCrashHistory.format(exit("*** *** ***\npid: 1234, tid: 1234 >>> host.vorv <<<", null))
        assertTrue(text.contains("REAL"))
        assertTrue(text.contains("pid: 1234"))
        assertTrue(text.contains("SIGSEGV"))
        assertTrue(text.contains("host.vorv"))
    }

    @Test
    fun `a report without a tombstone says the trace is missing, not guessed`() {
        val text = NativeCrashHistory.format(exit(null, "Android kept no trace for this exit"))
        assertTrue(text.contains("No trace from the OS"))
        assertTrue(text.contains("not a guess"))
    }

    @Test
    fun `an unknown exit reason keeps the number`() {
        assertTrue(NativeCrashHistory.reasonName(4242).contains("4242"))
    }
}
