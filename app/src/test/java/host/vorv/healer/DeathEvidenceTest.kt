package host.vorv.healer

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rule that decides whether "Vorv crashed last time" is allowed to appear.
 *
 * The owner's report was the spec: an unfinished boot is not a crash. The app
 * said it crashed when it had simply been closed, and the dialog's own text
 * admitted there was no crash record anywhere.
 */
@RunWith(RobolectricTestRunner::class)
class DeathEvidenceTest {

    private val bootStart = 1_000_000L

    @Test
    fun `an unfinished boot with no record anywhere is not a crash`() {
        val v = DeathEvidence.decide(bootStart, null, null, null, null)
        assertFalse(v.crashed)
        assertTrue(v.detail.contains("nothing says it crashed"))
    }

    @Test
    fun `the user closing the app is not a crash`() {
        val v = DeathEvidence.decide(
            bootStart,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            bootStart + 5_000,
            null,
            null,
        )
        assertFalse(v.crashed)
        assertTrue(v.detail.contains("ended, not crashed"))
    }

    @Test
    fun `the system reclaiming the process is not a crash`() {
        val v = DeathEvidence.decide(
            bootStart,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            bootStart + 5_000,
            null,
            null,
        )
        assertFalse(v.crashed)
    }

    @Test
    fun `a native crash recorded after the boot started is a crash`() {
        val v = DeathEvidence.decide(
            bootStart,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            bootStart + 5_000,
            null,
            null,
        )
        assertTrue(v.crashed)
        assertTrue(v.detail.contains("CRASH"))
    }

    @Test
    fun `a crash record from an earlier session proves nothing about this one`() {
        val v = DeathEvidence.decide(
            bootStart,
            ApplicationExitInfo.REASON_CRASH,
            bootStart - 60_000,
            null,
            null,
        )
        assertFalse(v.crashed)
    }

    @Test
    fun `a java crash report written during the session is a crash`() {
        val v = DeathEvidence.decide(bootStart, null, null, bootStart + 1_000, null)
        assertTrue(v.crashed)
        assertTrue(v.detail.contains("Java crash report"))
    }

    @Test
    fun `a watchdog death detection during the session is a crash`() {
        val v = DeathEvidence.decide(bootStart, null, null, null, bootStart + 1_000)
        assertTrue(v.crashed)
        assertTrue(v.detail.contains("watchdog"))
    }

    @Test
    fun `a java report older than the session is not evidence`() {
        val v = DeathEvidence.decide(bootStart, null, null, bootStart - 1_000, null)
        assertFalse(v.crashed)
    }

    @Test
    fun `without an epoch stamp a current crash reason still counts`() {
        val v = DeathEvidence.decide(
            null,
            ApplicationExitInfo.REASON_CRASH,
            5_000L,
            null,
            null,
        )
        assertTrue(v.crashed)
    }

    @Test
    fun `boot start epoch is read from the trace when it was recorded`() {
        val section = """
            [07:14:43.412] BOOT_STARTED — pid=21408 install=abc epoch=1763200483412
            [07:14:43.425] hotpatch_load_begin
        """.trimIndent()
        assertEquals(1763200483412L, host.vorv.BootTracer.sectionStartEpoch(section))
    }

    @Test
    fun `a trace without an epoch stamp reports none instead of guessing`() {
        val section = "[07:14:43.412] BOOT_STARTED — pid=21408 install=abc"
        assertNull(host.vorv.BootTracer.sectionStartEpoch(section))
    }
}
