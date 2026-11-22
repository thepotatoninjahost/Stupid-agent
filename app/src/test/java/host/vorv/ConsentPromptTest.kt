package host.vorv

import host.vorv.healer.FixPlan
import host.vorv.healer.FixStep
import host.vorv.healer.consentText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The owner's rule (2026-11): approve the PLAN once, then it does what the
 * plan says. That makes this one text the whole contract, so it is pinned:
 * every step and every change must appear, complete and untruncated, and the
 * text must not promise anything the app cannot do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ConsentPromptTest {

    private fun plan(vararg steps: Pair<String, String>) = FixPlan(
        id = "plan-2026-11-21",
        title = "Repair the crash in NotifyTool",
        problem = "NotifyTool crashes when the title is empty",
        steps = steps.mapIndexed { i, (cls, change) ->
            FixStep(index = i + 1, targetClass = cls, change = change)
        },
    )

    @Test
    fun `every step and its full change text are shown`() {
        val longChange = "Guard the title against empty strings. " + "Detail ".repeat(120)
        val msg = plan(
            "host.vorv.tools.NotifyTool" to longChange,
            "host.vorv.tools.ScheduleTool" to "Keep entries whose time is midnight.",
        ).consentText()

        assertTrue(msg.contains("1. host.vorv.tools.NotifyTool"))
        assertTrue(msg.contains("2. host.vorv.tools.ScheduleTool"))
        assertTrue("the full change text must appear", msg.contains(longChange))
        assertTrue(msg.contains("What will change (2 step(s))"))
        assertFalse("no ellipsis anywhere", msg.contains("…"))
    }

    @Test
    fun `the text states the plan's limits instead of promising more`() {
        val msg = plan("host.vorv.tools.NotifyTool" to "Guard the empty title.").consentText()
        assertTrue(msg.contains("exactly these steps and nothing else"))
        assertTrue(msg.contains("A repair for any class NOT listed above is refused"))
        assertTrue(msg.contains("later steps do not run and nothing is improvised"))
        assertTrue(msg.contains("kept in the bin"))
        // Consent happens before any code exists, so there is no hash to show
        // yet — and the text must not pretend otherwise.
        assertFalse("must not claim a patch exists yet", msg.contains("SHA-256"))
    }

    @Test
    fun `the plan id is shown so the decision can be traced`() {
        val msg = plan("host.vorv.tools.NotifyTool" to "Guard the empty title.").consentText()
        assertTrue(msg.contains("plan-2026-11-21"))
    }
}

/** What the plan file, the load log and the dialog all rely on. */
class FixPlanShapeTest {

    @Test
    fun `the plan covers exactly the classes of its steps`() {
        val p = FixPlan(
            id = "plan-1",
            title = "t",
            problem = "p",
            steps = listOf(
                FixStep(1, "host.vorv.tools.A", "a"),
                FixStep(2, "host.vorv.tools.B", "b"),
            ),
        )
        assertEquals(
            listOf("host.vorv.tools.A", "host.vorv.tools.B"),
            p.steps.map { it.targetClass },
        )
        assertEquals(setOf("host.vorv.tools.A", "host.vorv.tools.B"), p.targetClasses)
    }
}
