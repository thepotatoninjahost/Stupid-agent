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
 * The happy path needs the on-device model + the forge toolchain and is exercised on a
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
            JsonObject().apply {
                addProperty("problem", "NotifyTool crashes when the title is empty")
                add("steps", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("target_class", "host.vorv.tools.NotifyTool")
                        addProperty("change", "Guard the title against empty strings.")
                    })
                })
            },
            ctx(confirm = { true }, workspaceUri = null),
        )
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("workspace"))
    }

    @Test
    fun `schema requires the problem field`() {
        val schema = tool.schema()
        assertTrue(schema.get("required").asJsonArray.toString().contains("problem"))
        assertTrue(schema.toString().contains("steps"))
        assertTrue(schema.toString().contains("target_class"))
    }

    @Test
    fun `a fix with no named class is refused as no plan`() = runBlocking {
        val r = tool.run(
            JsonObject().apply { addProperty("problem", "NotifyTool crashes when the title is empty") },
            ctx(confirm = { true }, workspaceUri = "content://tree/primary::Vorv"),
        )
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("No plan"))
        assertFalse("nothing may be generated from an unnamed plan", r.output.contains("APPROVED"))
    }

    @Test
    fun `a plan naming a class outside the dispatch surface is refused`() {
        val input = JsonObject().apply {
            addProperty("problem", "The launcher icon is the wrong shade of yellow")
            add("steps", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("target_class", "host.vorv.ui.screen.HomeScreen")
                    addProperty("change", "Use the other yellow.")
                })
            })
        }
        val (steps, error) = tool.parseSteps(input, "The launcher icon is the wrong shade of yellow")
        assertTrue(steps == null)
        assertTrue(error!!.contains("cannot be patched"))
    }

    @Test
    fun `a plan step has to say what changes`() {
        val input = JsonObject().apply {
            addProperty("problem", "NotifyTool crashes when the title is empty")
            add("steps", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("target_class", "host.vorv.tools.NotifyTool")
                    addProperty("change", "fix")
                })
            })
        }
        val (steps, error) = tool.parseSteps(input, "NotifyTool crashes when the title is empty")
        assertTrue(steps == null)
        assertTrue(error!!.contains("does not say what changes"))
    }

    @Test
    fun `a valid plan keeps every step and its text`() {
        val input = JsonObject().apply {
            addProperty("problem", "NotifyTool crashes when the title is empty")
            add("steps", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("target_class", "host.vorv.tools.NotifyTool")
                    addProperty("change", "Guard the title against empty strings.")
                })
                add(JsonObject().apply {
                    addProperty("target_class", "host.vorv.tools.ScheduleTool")
                    addProperty("change", "Keep entries whose time is midnight.")
                })
            })
        }
        val (steps, error) = tool.parseSteps(input, "NotifyTool crashes when the title is empty")
        assertTrue(error == null)
        assertTrue(steps!!.size == 2)
        assertTrue(steps[0].change == "Guard the title against empty strings.")
        assertTrue(steps[1].targetClass == "host.vorv.tools.ScheduleTool")
    }
}
