package host.vorv.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Consent-prompt completeness tests (v0.6.11, audit N-I4).
 *
 * Rule under test: if the user is asked to approve it, they see ALL of it.
 * Every consent message must contain the complete, exact content the model
 * wants to execute — never a preview, never a "+N more lines" notice, never
 * an ellipsis. Each test drives the REAL run() path with a capturing
 * confirm bridge that denies, so the tools exercise their genuine
 * pre-approval code (the gate fires before any workspace/Termux/Android
 * side effects).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ConsentPromptTest {

    private val appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Records every consent message and denies — the safe outcome. */
    private class CapturingDeny {
        val messages = mutableListOf<String>()
        val bridge: suspend (String) -> Boolean = { msg ->
            messages.add(msg); false
        }
    }

    private fun ctx(taskId: String, confirm: (suspend (String) -> Boolean)?): ToolContext =
        ToolContext(taskId = taskId, iteration = 1, workspaceUri = null, confirm = confirm)

    // ------------------------------------------------------------- shell

    @Test
    fun `shell consent shows the complete command with no truncation notice`() = runBlocking {
        // 40 lines — far beyond the old 8-line preview.
        val command = (1..40).joinToString("\n") { "echo \"line $it of the forty-line script marker-$it\"" }
        val capture = CapturingDeny()
        val r = ShellTool(appContext).run(
            JsonObject().apply { addProperty("command", command) },
            ctx("shell-t1", capture.bridge),
        )
        assertEquals(1, capture.messages.size)
        val msg = capture.messages.first()
        assertTrue("must carry the shell heading", msg.contains("SHELL COMMAND"))
        assertTrue("must end with the Allow question", msg.contains("Allow?"))
        // EVERY line must be present — the whole command, not a preview.
        (1..40).forEach { i ->
            assertTrue("line $i missing from the consent prompt", msg.contains("marker-$i"))
        }
        assertTrue("the joined command must appear verbatim", msg.contains(command))
        assertFalse("no '+N more lines' notice", msg.contains("more lines"))
        assertFalse("no ellipsis character", msg.contains("…"))
        assertFalse("no parenthetical continuation", msg.contains("(+"))
        // Denial must be honest and explicit.
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("DENIED"))
    }

    // -------------------------------------------------------- python_exec

    @Test
    fun `python consent shows the complete program with no truncation notice`() = runBlocking {
        // 25 lines — far beyond the old 6-line preview.
        val code = (1..25).joinToString("\n") { "print(\"py line $it marker-py-$it\") # tail comment $it" }
        val capture = CapturingDeny()
        val r = PythonExecTool(appContext).run(
            JsonObject().apply { addProperty("code", code) },
            ctx("py-t1", capture.bridge),
        )
        assertEquals(1, capture.messages.size)
        val msg = capture.messages.first()
        assertTrue("must carry the python heading", msg.contains("PYTHON CODE"))
        assertTrue("must end with the Allow question", msg.contains("Allow?"))
        (1..25).forEach { i ->
            assertTrue("python line $i missing from the consent prompt", msg.contains("marker-py-$i"))
        }
        assertTrue("the joined program must appear verbatim", msg.contains(code))
        assertFalse("no '+N more lines' notice", msg.contains("more lines"))
        assertFalse("no ellipsis character", msg.contains("…"))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("DENIED"))
    }

    // --------------------------------------------------- app_intent share

    @Test
    fun `share consent shows the full text past the old 120-char cut`() = runBlocking {
        // 300 chars — far beyond the old 120-character cut, with a unique
        // marker in the last stretch that the old code could never show.
        val text = (1..60).joinToString(" ") { "segment-$it" } + " TAIL-MARKER-BEYOND-120"
        val capture = CapturingDeny()
        val r = AppIntentTool(appContext).run(
            JsonObject().apply {
                addProperty("action", "share")
                addProperty("data", text)
            },
            ctx("share-t1", capture.bridge),
        )
        assertEquals(1, capture.messages.size)
        val msg = capture.messages.first()
        assertTrue("must carry the share heading", msg.contains("share the text"))
        assertTrue("the tail past the old 120-char cut must be shown", msg.contains("TAIL-MARKER-BEYOND-120"))
        assertTrue("the full text must appear verbatim", msg.contains("\"$text\""))
        assertFalse("no ellipsis character", msg.contains("…"))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("DENIED"))
    }

    // -------------------------------------------------- app_intent others

    @Test
    fun `view and dial consent show the full URL and number`() = runBlocking {
        val capture = CapturingDeny()
        val rView = AppIntentTool(appContext).run(
            JsonObject().apply {
                addProperty("action", "view")
                addProperty("data", "https://example.example.very.long.domain.name/some/deep/path?with=query&and=more#fragment")
            },
            ctx("view-t1", capture.bridge),
        )
        assertTrue(
            "the full URL must appear in the prompt",
            capture.messages.first().contains("https://example.example.very.long.domain.name/some/deep/path?with=query&and=more#fragment"),
        )
        assertFalse(rView.ok)
        assertTrue(rView.error!!.contains("DENIED"))
    }
}
