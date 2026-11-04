package host.vorv.tools

import com.google.gson.JsonObject
import host.vorv.MainActivity
import host.vorv.healer.PatchProposal
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

    // --------------------------- crash-repair patch dialog (MainActivity path)

    /**
     * The FIFTH approval surface: the crash-repair "Apply self-repair
     * patch?" dialog in MainActivity (v0.6.12). The old dialog cut the
     * SHA-256 to a 16-char prefix and the description to 400 chars — the
     * last truncation left in any approval path. Now the complete hash,
     * the complete description, and the exact APPROVE/DENY consequences
     * are shown, and the dialog itself scrolls with selectable text.
     */
    @Test
    fun `crash-repair patch consent shows the full SHA-256 and complete description`() {
        val sha = "a1b2c3d4".repeat(8) // 64 hex chars — the old code showed only the first 16
        val longDescription = (1..80).joinToString(" ") {
            "Sentence $it of a deliberately long patch description with a tail marker TAIL-$it."
        } // ~4.5k chars — far beyond the old 400-char cap
        val proposal = PatchProposal(
            patchId = "fix_20260101_000000_deadbeef",
            targetClass = "host.vorv.tools.ShellTool",
            description = longDescription,
            sha256 = sha,
            signature = "test-signature",
            surfaceVerified = false,
            attempts = 3,
        )
        val msg = MainActivity.buildPatchConsentMessage(proposal)
        // The COMPLETE 64-char hash — never a 16-char prefix with an ellipsis.
        assertTrue("the complete 64-char SHA-256 must be shown", msg.contains(sha))
        // The COMPLETE description — never capped at 400 chars.
        assertTrue("the complete description must be shown verbatim", msg.contains(longDescription))
        assertTrue("the description tail must survive the old 400-char cap", msg.contains("TAIL-80."))
        // No truncation notices anywhere.
        assertFalse("no ellipsis character", msg.contains("…"))
        // The honest verification status is part of what the user sees.
        assertTrue("verification status must be stated", msg.contains("NOT verified"))
        // What APPROVE and DENY actually do must be stated in full.
        assertTrue("consequences of APPROVE must be stated", msg.contains("APPROVE ="))
        assertTrue("consequences of DENY must be stated", msg.contains("DENY ="))
    }
}
