package host.vorv.tools

import com.google.gson.JsonObject
import host.vorv.data.entity.ToolCall
import host.vorv.kernel.CognitiveKernel
import host.vorv.kernel.KernelEventSink
import host.vorv.llm.Llm
import host.vorv.tools.SessionGrants.statusLine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Termux smoke tests (v0.6.15) — the chain the field bug broke, wired
 * end-to-end in the JVM.
 *
 * Field finding #3: asked to use Termux, Vorv answered "I don't have
 * access to Termux" WITHOUT EVER CALLING the shell tool. The tool was
 * registered and one consent dialog away, but its description read like a
 * disclaimer of unavailability and the system prompt carried no live
 * Termux status — so the model guessed, guessed "no", and the honest-error
 * machinery never fired.
 *
 * These tests smoke the WHOLE wiring, not isolated units:
 *  A. what the model is TOLD — the system prompt (built by the real
 *     CognitiveKernel) carries the live Termux status line in all three
 *     session states;
 *  B. what the model READS — the shell tool's description orders it to
 *     call the tool and forbids the false denial;
 *  C. how consent behaves — the session grant replaces dialogs with an
 *     audit trail, never with silence; revocation restores dialogs; the
 *     grant never leaks to other tools; fail-closed survives.
 *
 * The real Termux round-trip still runs on the device (Setup → Test
 * connection); here we pin the contracts around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TermuxSmokeTest {

    private val appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun cleanSession() = SessionGrants.resetForTest()

    // ------------------------------------------------------- fakes (kernel)

    /** Scripted LLM that records every message list it was handed. */
    private class FakeLlm(private val script: List<Llm.LlmResponse>) : Llm {
        override val label = "fake"
        val steps = mutableListOf<List<Llm.Message>>()
        private var i = 0
        override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse {
            steps.add(messages.toList())
            return script[i++]
        }
    }

    private class FakeSink : KernelEventSink {
        val episodes = mutableListOf<Triple<String, String, String>>()
        override suspend fun logEpisode(kind: String, summary: String, detailsJson: String) {
            episodes.add(Triple(kind, summary, detailsJson))
        }
        override suspend fun insertToolCall(call: ToolCall) {}
        override suspend fun recordSkillResult(toolName: String, ok: Boolean) {}
        override suspend fun setTaskIterations(taskId: String, iterations: Int) {}
    }

    /** Run one no-op task through the REAL kernel and return the system prompt it sent. */
    private fun systemPromptFor(): String {
        val llm = FakeLlm(listOf(Llm.LlmResponse.Text("ok", done = true)))
        val kernel = CognitiveKernel(FakeSink(), emptyList(), File(appContext.cacheDir, "smoke-out").apply { mkdirs() })
        runBlocking { kernel.run("smoke", "q", llm, null) }
        val first = llm.steps.firstOrNull() ?: error("the LLM was never called")
        return first.first().content
    }

    // =============================================== A. what the model is told

    @Test
    fun `A1 unverified session - prompt forbids the false denial and orders the call`() {
        SessionGrants.resetForTest() // pristine boot: nothing verified
        val prompt = systemPromptFor()
        assertTrue("the live status line must exist", prompt.contains("Termux shell:"))
        assertTrue("must forbid claiming no access",
            prompt.contains("Do NOT claim you cannot access Termux"))
        assertTrue("must order the call", prompt.contains("call the shell tool"))
        assertTrue("honest-error promise must be stated", prompt.contains("honest error"))
    }

    @Test
    fun `A2 verified session - prompt states the shell works and dialogs are normal`() {
        SessionGrants.markShellVerified()
        val prompt = systemPromptFor()
        assertTrue(prompt.contains("VERIFIED this session"))
        assertTrue("per-command dialogs must be called normal, not a failure",
            prompt.contains("not a failure"))
    }

    @Test
    fun `A3 granted session - prompt states the batch grant and its audit trail`() {
        SessionGrants.markShellVerified()
        SessionGrants.grantShellForSession()
        val prompt = systemPromptFor()
        assertTrue(prompt.contains("granted ALL shell commands"))
        assertTrue("the audit trail must be stated to the model", prompt.contains("logged"))
        assertTrue("revocability must be stated", prompt.contains("revocable"))
    }

    // ============================================ B. what the model reads

    @Test
    fun `B1 shell description orders the call and forbids the false denial`() {
        val d = ShellTool(appContext).description.lowercase()
        assertTrue("must say to always call it", d.contains("always call this tool"))
        assertTrue("must forbid claiming no access",
            d.contains("never claim you cannot access termux"))
        assertFalse("the old prerequisite-disclaimer opener is gone", d.contains("requires:"))
    }

    @Test
    fun `B2 the shell tool is registered under the name the prompt promises`() {
        // The kernel lists tools by name in the prompt; the smoke: the tool
        // that carries CONSENT_PREFIX is exactly the one named "shell".
        assertEquals("shell", ShellTool(appContext).name)
        assertTrue(ShellTool.CONSENT_PREFIX.startsWith("Vorv wants to run a SHELL COMMAND"))
    }

    // ======================================== C. how consent behaves (grant)

    @Test
    fun `C1 session grant skips the dialog but not the other gates`() = runBlocking {
        val audited = mutableListOf<Triple<String, String, String>>()
        SessionGrants.auditSink = { k, s, d -> audited.add(Triple(k, s, d)) }
        SessionGrants.grantShellForSession()

        // A bridge that FAILS the test if any dialog is raised: the grant is
        // the consent, so nobody may ask again this session.
        val failIfAsked: suspend (String) -> Boolean = { fail("dialog raised under an active session grant"); true }

        // Null workspace: the run must sail PAST consent straight into the
        // next honest gate — proving both the skip and that gates survived.
        val r = ShellTool(appContext).run(
            JsonObject().apply { addProperty("command", "echo smoke-grant") },
            ToolContext("smoke", 1, workspaceUri = null, confirm = failIfAsked),
        )
        assertFalse(r.ok)
        assertTrue("should hit the workspace gate, not a consent gate",
            r.error!!.contains("No workspace granted"))
        assertEquals("exactly one auto-run counted", 1, SessionGrants.autoRunCount)
        assertEquals("audited once", 1, audited.size)
        assertEquals("shell_session_auto", audited.first().first)
        assertTrue("the COMPLETE command must be in the audit record",
            audited.first().third.contains("echo smoke-grant"))
    }

    @Test
    fun `C2 without a grant every command still asks with the pinned prefix`() = runBlocking {
        val asked = mutableListOf<String>()
        val deny: suspend (String) -> Boolean = { m -> asked.add(m); false }
        val r = ShellTool(appContext).run(
            JsonObject().apply { addProperty("command", "echo smoke-ask") },
            ToolContext("smoke", 1, workspaceUri = null, confirm = deny),
        )
        assertEquals(1, asked.size)
        assertTrue("consent message is pinned to the single-source prefix",
            asked.first().startsWith(ShellTool.CONSENT_PREFIX))
        assertTrue(asked.first().contains("Allow?"))
        assertTrue(asked.first().contains("echo smoke-ask"))
        assertTrue("denial stays honest", r.error!!.contains("DENIED"))
        assertEquals("nothing auto-ran", 0, SessionGrants.autoRunCount)
    }

    @Test
    fun `C3 revoking restores the per-command dialog`() = runBlocking {
        SessionGrants.grantShellForSession()
        SessionGrants.revokeShellGrant()

        val asked = mutableListOf<String>()
        val deny: suspend (String) -> Boolean = { m -> asked.add(m); false }
        ShellTool(appContext).run(
            JsonObject().apply { addProperty("command", "echo smoke-revoke") },
            ToolContext("smoke", 1, workspaceUri = null, confirm = deny),
        )
        assertEquals("the dialog is back after revoke", 1, asked.size)
        assertFalse("grant is truly off", SessionGrants.shellAllCommands)
    }

    @Test
    fun `C4 no grant and no bridge still fails closed`() = runBlocking {
        SessionGrants.resetForTest() // no grant, no UI
        val r = ShellTool(appContext).run(
            JsonObject().apply { addProperty("command", "echo smoke-closed") },
            ToolContext("smoke", 1, workspaceUri = null, confirm = null),
        )
        assertFalse(r.ok)
        assertTrue("the fail-closed law survives v0.6.15", r.error!!.contains("fail closed"))
    }

    @Test
    fun `C5 the session grant is shell-only - python still asks`() = runBlocking {
        SessionGrants.grantShellForSession()
        val asked = mutableListOf<String>()
        val deny: suspend (String) -> Boolean = { m -> asked.add(m); false }
        val r = PythonExecTool(appContext).run(
            JsonObject().apply { addProperty("code", "print('hi')") },
            ToolContext("smoke", 1, workspaceUri = null, confirm = deny),
        )
        assertEquals("python consent is unaffected by the shell grant", 1, asked.size)
        assertTrue(r.error!!.contains("DENIED"))
    }

    // ============================================ D. statusLine pure wording

    @Test
    fun `D1 statusLine names all three session states distinctly`() {
        SessionGrants.resetForTest()
        assertTrue(statusLine().contains("not yet verified"))
        SessionGrants.markShellVerified()
        val verified = statusLine()
        assertTrue(verified.contains("VERIFIED"))
        assertFalse("no grant wording before the user grants", verified.contains("granted ALL"))
        SessionGrants.grantShellForSession()
        val granted = statusLine()
        assertTrue(granted.contains("granted ALL shell commands"))
        assertTrue(granted.contains("VERIFIED"))
    }

    @Test
    fun `D2 verification is knowledge not consent - dialogs continue until granted`() {
        SessionGrants.markShellVerified()
        assertFalse("verified alone grants nothing", SessionGrants.shellAllCommands)
        assertTrue("but it changes what the model is told", statusLine().contains("VERIFIED"))
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)
}
