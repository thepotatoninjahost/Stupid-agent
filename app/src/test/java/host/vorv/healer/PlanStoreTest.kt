package host.vorv.healer

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A planned fix is only honest if the plan's fate is readable afterwards: what
 * the owner was asked, what they answered, and which steps actually ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlanStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun plan(id: String = "plan-test-1") = FixPlan(
        id = id,
        title = "Repair the crash in NotifyTool",
        problem = "NotifyTool crashes when the title is empty",
        steps = listOf(FixStep(1, "host.vorv.tools.NotifyTool", "Guard the empty title.")),
    )

    @Test
    fun `a saved plan starts proposed with pending steps`() {
        assertNotNull(PlanStore.save(ctx, plan()))
        assertEquals("proposed", PlanStore.status(ctx, "plan-test-1"))
        val step = PlanStore.read(ctx, "plan-test-1")!!
            .getAsJsonArray("steps")[0].asJsonObject
        assertEquals("pending", step.get("status").asString)
    }

    @Test
    fun `a denied plan is kept on disk with the reason`() {
        PlanStore.save(ctx, plan("plan-test-2"))
        assertTrue(PlanStore.decide(ctx, "plan-test-2", approved = false, note = "owner denied the plan"))
        val doc = PlanStore.read(ctx, "plan-test-2")
        assertEquals("denied", doc!!.get("status").asString)
        assertEquals("owner denied the plan", doc.get("note").asString)
        assertTrue(
            "the denial must be visible to the agent, not silently dropped",
            PlanStore.list(ctx).any { it.contains("plan-test-2") && it.contains("denied") },
        )
    }

    @Test
    fun `a step records its outcome and the patch it produced`() {
        PlanStore.save(ctx, plan("plan-test-3"))
        PlanStore.decide(ctx, "plan-test-3", approved = true)
        PlanStore.recordStep(ctx, "plan-test-3", 1, "patched", "plan-1", null)
        val step = PlanStore.read(ctx, "plan-test-3")!!
            .getAsJsonArray("steps")[0].asJsonObject
        assertEquals("patched", step.get("status").asString)
        assertEquals("plan-1", step.get("patch_id").asString)
    }

    @Test
    fun `deciding an unknown plan changes nothing and says so`() {
        assertEquals(false, PlanStore.decide(ctx, "plan-does-not-exist", approved = true))
        assertNull(PlanStore.status(ctx, "plan-does-not-exist"))
    }
}
