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
 * M-9 regression (code review, 2026-11-20): a python_exec run that blows the
 * hard Kotlin wall leaves a daemon thread inside CPython, and the old code
 * released the run mutex anyway — so the next call started a SECOND script in
 * the same interpreter while the first was still executing. Once a run has
 * leaked, python_exec must refuse (fail closed) instead.
 *
 * Robolectric: the refusal happens before any Chaquopy initialization, so no
 * Python runtime is needed here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PythonExecRunawayTest {

    private val tool = PythonExecTool(
        appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
    )

    private fun ctx() = ToolContext(
        taskId = "pyrunaway", iteration = 1,
        workspaceUri = "content://tree/primary::Vorv", confirm = { true },
    )

    private val code = JsonObject().apply { addProperty("code", "print('hi')") }

    @Test
    fun `a leaked interpreter refuses further runs instead of starting a second script`() =
        runBlocking {
            tool.markRunawayForTest()
            assertTrue(tool.isRunawayForTest())

            val r = tool.run(code, ctx())
            val err = r.error.orEmpty()

            assertFalse("must not run: ${r.output}", r.ok)
            assertTrue(
                "must say why and what to do: $err",
                err.contains("still executing") &&
                    err.contains("two at once") &&
                    err.contains("Restart"),
            )
        }

    @Test
    fun `a fresh tool is not poisoned`() = runBlocking {
        assertFalse(tool.isRunawayForTest())
    }
}
