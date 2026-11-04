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

    /**
     * v0.6.11 (audit N-I4): the approval dialog shows the FULL 64-char
     * SHA-256 — never a truncated prefix with an ellipsis. The user
     * approves exactly what they see, and they see all of it.
     */
    @Test
    fun `approval message shows the full SHA-256 and never truncates`() {
        val sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val msg = tool.approvalMessage(
            host.vorv.healer.PatchProposal(
                patchId = "fix-001",
                targetClass = "host.vorv.tools.NotifyTool",
                description = "Guard the title against empty strings.",
                sha256 = sha,
                signature = "sig",
                surfaceVerified = true,
                attempts = 1,
            )
        )
        assertTrue("the full 64-char hash must appear", msg.contains(sha))
        assertTrue("must be labeled SHA-256", msg.contains("SHA-256"))
        assertFalse("no ellipsis anywhere", msg.contains("…"))
        assertFalse("no take() leftovers", msg.contains("take("))
    }
}
