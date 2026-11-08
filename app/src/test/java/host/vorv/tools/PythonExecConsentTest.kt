package host.vorv.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-8 regression tests (code review, 2026-11): the python_exec consent gate
 * is FAIL-CLOSED under every abnormal path.
 *
 * The bug: `putIfAbsent(taskId, true)` marked the task consented BEFORE the
 * dialog opened — a consent bridge that THREW (UI glitch) left the task
 * marked consented, so the model's plain RETRY skipped the gate entirely and
 * executed the code unconsented. These tests pin the tri-state behavior:
 * an exception, a denial and a cancellation all clean up, and the next call
 * ASKS AGAIN instead of running.
 *
 * Robolectric (plain Application): the deny/failure paths return before any
 * Chaquopy initialization, so no Python runtime is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PythonExecConsentTest {

    private val tool = PythonExecTool(
        appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
    )

    private fun ctx(confirm: (suspend (String) -> Boolean)?) = ToolContext(
        taskId = "pygate", iteration = 1,
        workspaceUri = "content://tree/primary::Vorv", confirm = confirm,
    )

    private val code = JsonObject().apply { addProperty("code", "print('hi')") }

    @Test
    fun `a throwing consent bridge fails closed and cleans up`() = runBlocking {
        // First call: the bridge itself explodes mid-dialog.
        val r1 = tool.run(code, ctx(confirm = { throw RuntimeException("bridge glitch") }))
        assertFalse(r1.ok)
        assertTrue(
            "must name the failure and the closed gate: ${r1.error}",
            r1.error!!.contains("consent dialog failed") && r1.error!!.contains("fail closed"),
        )
    }

    @Test
    fun `after a bridge exception the RETRY asks again - never runs unconsented`() = runBlocking {
        // THE M-8 regression: the old code left the task marked consented
        // here, so this second call skipped the dialog and went straight to
        // Python init ("Python init failed: ..." under Robolectric — an
        // EXECUTION attempt). The fixed code re-asks; a deny here proves the
        // gate ran again.
        tool.run(code, ctx(confirm = { throw RuntimeException("bridge glitch") }))
        var askedAgain = 0
        val r2 = tool.run(code, ctx(confirm = { askedAgain++; false }))
        assertTrue("the consent dialog must re-open on retry", askedAgain == 1)
        assertFalse(r2.ok)
        assertTrue("deny is honest: ${r2.error}", r2.error!!.contains("DENIED"))
    }

    @Test
    fun `a denial cleans up so the next call re-asks`() = runBlocking {
        val r1 = tool.run(code, ctx(confirm = { false }))
        assertFalse(r1.ok)
        assertTrue(r1.error!!.contains("DENIED"))
        var asked = 0
        val r2 = tool.run(code, ctx(confirm = { asked++; false }))
        assertTrue(asked == 1)
        assertTrue(r2.error!!.contains("DENIED"))
    }

    @Test
    fun `no consent bridge at all fails closed without burning the one-shot ask`() = runBlocking {
        val r1 = tool.run(code, ctx(confirm = null))
        assertFalse(r1.ok)
        assertTrue(r1.error!!.contains("fail closed"))
        // The failed ask must not poison the task: a later call with a
        // working bridge still gets to ask (and deny) — not auto-run.
        var asked = 0
        val r2 = tool.run(code, ctx(confirm = { asked++; false }))
        assertTrue(asked == 1)
        assertTrue(r2.error!!.contains("DENIED"))
    }
}
