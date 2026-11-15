package host.vorv.healer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A proven crash must be acted on, not just described. */
@RunWith(RobolectricTestRunner::class)
class CrashRepairTest {

    private val trace = """
        ============================================================
        [07:14:43.412] BOOT_STARTED — pid=21408 install=abc epoch=1763200483412
        [07:14:43.425] geniex_init_begin
        [07:14:43.900] geniex_init_after_call
    """.trimIndent()

    @Test
    fun `death inside native init runs the next boot without it`() {
        val plan = CrashRepair.plan(trace, emptyList())
        assertTrue(plan.suppressNativeInit)
        assertTrue(plan.actions.any { it.contains("GenieX native init") })
    }

    @Test
    fun `native init that succeeded is not suppressed`() {
        val ok = trace + "\n[07:14:44.100] geniex_init_success"
        assertFalse(CrashRepair.plan(ok, emptyList()).suppressNativeInit)
    }

    @Test
    fun `a quarantined patch is reported as already handled`() {
        val plan = CrashRepair.plan(trace, listOf("com.example.Bug"))
        assertTrue(plan.actions.any { it.contains("Quarantined com.example.Bug") })
    }

    @Test
    fun `death during hotpatch load is called out`() {
        val t = "[00:00:01.000] BOOT_STARTED — pid=1 install=a epoch=1\n[00:00:01.010] hotpatch_load_begin"
        val plan = CrashRepair.plan(t, emptyList())
        assertTrue(plan.actions.any { it.contains("hot-patches") })
    }

    @Test
    fun `a crash with nothing to disable says so instead of pretending`() {
        val t = "[00:00:01.000] BOOT_STARTED — pid=1 install=a epoch=1\n[00:00:02.000] init_prefs"
        val plan = CrashRepair.plan(t, emptyList())
        assertFalse(plan.suppressNativeInit)
        assertTrue(plan.actions.single().contains("Nothing needed disabling"))
    }

    @Test
    fun `no trace at all is answered, not crashed on`() {
        val plan = CrashRepair.plan(null, emptyList())
        assertFalse(plan.suppressNativeInit)
        assertTrue(plan.actions.isNotEmpty())
    }
}
