package host.vorv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported symptom: a brand-new install opened and announced "Vorv
 * crashed last time" about a trace left behind by an EARLIER build. A trace
 * from another install is history, not evidence.
 */
class BootTracerInstallTest {

    private val mine = "11111111-2222-3333-4444-555555555555"

    @Test
    fun `a trace from another install is not this install's crash`() {
        val foreign = """
            ============================================================
            [05:51:19.026] BOOT_STARTED - pid=15474 install=99999999-dead-beef-0000-000000000000
            [05:51:19.030] hotpatch_load_begin
            [05:51:19.031] hotpatch_load_end - applied=0
        """.trimIndent()
        assertTrue(BootTracer.sectionCompleted(foreign, mine))
    }

    @Test
    fun `a trace from before install ids existed is not this install's crash`() {
        val legacy = """
            [05:51:19.026] BOOT_STARTED - pid=15474
            [05:51:19.030] hotpatch_load_begin
        """.trimIndent()
        assertTrue(BootTracer.sectionCompleted(legacy, mine))
    }

    @Test
    fun `a boot of this install that died before BOOT_COMPLETE did not finish`() {
        val real = """
            [09:01:00.000] BOOT_STARTED - pid=200 install=$mine
            [09:01:00.010] init_room_begin
        """.trimIndent()
        // "Did not finish" is all this answers. Whether it CRASHED is
        // DeathEvidence's call (it needs Android's exit record, a Java report
        // or a watchdog detection) — this check alone must never be a verdict.
        assertFalse(BootTracer.sectionCompleted(real, mine))
    }

    @Test
    fun `only the main process writes the trace a crash report reads`() {
        assertEquals("boot_trace.txt", BootTracer.traceFileName(null))
        assertEquals("boot_trace.txt", BootTracer.traceFileName(""))
        assertEquals("boot_trace_watchdog.txt", BootTracer.traceFileName("watchdog"))
    }

    @Test
    fun `a completed boot of this install is not a crash`() {
        val ok = """
            [09:01:00.000] BOOT_STARTED - pid=200 install=$mine
            [09:01:02.000] BOOT_COMPLETE - ui is up
        """.trimIndent()
        assertTrue(BootTracer.sectionCompleted(ok, mine))
    }
}
