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
 * SelfFixTool validation tests (v0.6.8 general-scope self-healing).
 *
 * The happy path needs the on-device model + Termux and is exercised on a
 * real device; these tests pin the FAIL-CLOSED contract: no consent bridge,
 * vague problems, no workspace — every path returns an honest error and
 * changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SelfFixToolTest {

    private val tool = SelfFixTool(
        appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
    )

    private fun ctx(
        confirm: (suspend (String) -> Boolean)? = null,
        workspaceUri: String? = null,
    ) = ToolContext(
        taskId = "t1", iteration = 1,
        workspaceUri = workspaceUri, confirm = confirm,
    )

    @Test
    fun `no consent bridge means fail closed`() = runBlocking {
        val r = tool.run(
            JsonObject().apply { addProperty("problem", "NotifyTool crashes when the title is empty") },
            ctx(confirm = null, workspaceUri = "content://tree/primary::Vorv"),
        )
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("consent"))
    }

    @Test
    fun `a vague problem is refused`() = runBlocking {
        val r = tool.run(
            JsonObject().apply { addProperty("problem", "fix it") },
            ctx(confirm = { true }, workspaceUri = "content://tree/primary::Vorv"),
        )
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("vague"))
    }

    @Test
    fun `no workspace is an honest actionable error`() = runBlocking {
        val r = tool.run(
            JsonObject().apply { addProperty("problem", "NotifyTool crashes when the title is empty") },
            ctx(confirm = { true }, workspaceUri = null),
        )
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("workspace"))
    }

    @Test
    fun `schema requires the problem field`() {
        val schema = tool.schema()
        assertTrue(schema.get("required").asJsonArray.toString().contains("problem"))
        assertTrue(schema.toString().contains("target_class"))
    }
}
