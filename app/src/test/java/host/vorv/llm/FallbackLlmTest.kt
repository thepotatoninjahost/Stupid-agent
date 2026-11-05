package host.vorv.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.0 — FallbackLlm unit tests (pure JVM, no Android, no network).
 *
 * These lock down the three honesty rules of the hybrid engine:
 *  1. Cloud failure with a TRANSIENT cause (network/5xx/exhausted retries)
 *     reruns the step on the offline engine — the task survives.
 *  2. AUTH failures never fall back — a broken key must stay visible.
 *  3. Cancellation (STOP button / timeout) never falls back.
 * Plus the pairing-safe compaction contract for the small local context.
 */
class FallbackLlmTest {

    private class FakeLlm(
        override val label: String,
        override val contextTokenBudget: Int = 100_000,
        private val behavior: suspend () -> Llm.LlmResponse,
    ) : Llm {
        val received = mutableListOf<List<Llm.Message>>()
        var calls = 0
            private set

        override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse {
            calls++
            received.add(messages)
            return behavior()
        }
    }

    private fun text(content: String) = Llm.LlmResponse.Text(content, done = true)

    private fun msg(role: String, content: String, calls: List<Llm.ToolCall> = emptyList()) =
        Llm.Message(role, content, calls)

    // ------------------------------------------------------------ behavior

    @Test
    fun `primary success answers directly and never touches the secondary`() = runTest {
        val secondary = FakeLlm("local") { text("secondary") }
        val fb = FallbackLlm(FakeLlm("cloud") { text("cloud answer") }, secondary)

        val resp = fb.step(listOf(msg("user", "hi")), emptyList())

        assertEquals("cloud answer", (resp as Llm.LlmResponse.Text).content)
        assertEquals("cloud", fb.lastEngineUsed)
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `transient cloud failure falls back and reports the real reason`() = runTest {
        val secondary = FakeLlm("local") { text("offline answer") }
        var switchReason: String? = null
        var switchTo: String? = null
        val fb = FallbackLlm(
            FakeLlm("cloud") { throw RuntimeException("LLM call failed after 4 attempts: HTTP 529: timeout") },
            secondary,
        )
        fb.onEngineSwitch = { _, to, reason -> switchTo = to; switchReason = reason }

        val resp = fb.step(listOf(msg("user", "hi")), emptyList())

        assertEquals("offline answer", (resp as Llm.LlmResponse.Text).content)
        assertEquals("local", fb.lastEngineUsed)
        assertEquals(1, secondary.calls)
        assertEquals("local", switchTo)
        assertTrue(switchReason!!.contains("HTTP 529"))
    }

    @Test
    fun `auth failures propagate and never silently switch engines`() = runTest {
        val secondary = FakeLlm("local") { text("should not run") }
        var switched = false
        val fb = FallbackLlm(
            FakeLlm("cloud") { throw RuntimeException("LLM HTTP 401: {\"error\":{\"message\":\"Invalid API key\"}}") },
            secondary,
        )
        fb.onEngineSwitch = { _, _, _ -> switched = true }

        val thrown = runCatching { fb.step(listOf(msg("user", "hi")), emptyList()) }.exceptionOrNull()

        assertTrue(thrown is RuntimeException)
        assertTrue(thrown!!.message!!.contains("401"))
        assertEquals(0, secondary.calls)
        assertFalse(switched)
    }

    @Test
    fun `cancellation propagates without fallback`() = runTest {
        val secondary = FakeLlm("local") { text("should not run") }
        val fb = FallbackLlm(
            FakeLlm("cloud") { throw CancellationException("user pressed STOP") },
            secondary,
        )

        val thrown = runCatching { fb.step(listOf(msg("user", "hi")), emptyList()) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `budget and label come from the primary engine`() {
        val primary = FakeLlm("cloud", contextTokenBudget = 100_000) { text("x") }
        val secondary = FakeLlm("local", contextTokenBudget = 1_800) { text("x") }
        val fb = FallbackLlm(primary, secondary)

        assertEquals(100_000, fb.contextTokenBudget)
        assertEquals("cloud → local", fb.label)
    }

    // ------------------------------------------------------------ compaction

    @Test
    fun `history under budget passes through unchanged`() {
        val messages = listOf(
            msg("system", "You are Vorv."),
            msg("user", "hello"),
            msg("assistant", "hi"),
        )
        assertSame(messages, FallbackLlm.compactFor(messages, 10_000))
    }

    @Test
    fun `compaction keeps the system message and the newest turns`() {
        val messages = buildList {
            add(msg("system", "SYS-IDENTITY"))
            repeat(30) { i -> add(msg("user", "question $i ".repeat(20))) }
            add(msg("user", "THE-CURRENT-QUESTION"))
        }

        val out = FallbackLlm.compactFor(messages, 200) // ~600 chars

        assertEquals("system", out.first().role)
        assertEquals("SYS-IDENTITY", out.first().content)
        assertEquals("THE-CURRENT-QUESTION", out.last().content)
        assertTrue("compaction must actually drop old turns", out.size < messages.size)
        val size = out.sumOf { it.content.length + it.toolCalls.sumOf { c -> c.arguments.length + c.name.length } }
        assertTrue("output must fit budget chars", size <= 200 * 3 + 1)
    }

    @Test
    fun `compaction never starts with an orphaned tool result`() {
        val toolCall = Llm.ToolCall("id1", "file_read", "{\"path\":\"a.txt\"}")
        val messages = buildList {
            add(msg("system", "SYS"))
            add(msg("user", "read a.txt please"))
            add(msg("assistant", "", listOf(toolCall)))
            add(Llm.Message("tool", "file contents here", toolCallId = "id1"))
            add(msg("user", "now summarize THE-POINT"))
        }

        // Budget tuned so the window would otherwise open on the tool result.
        val out = FallbackLlm.compactFor(messages, 16)

        assertNotEquals("tool", out.first { it.role != "system" }.role)
        assertTrue(out.any { it.content.contains("THE-POINT") })
    }

    @Test
    fun `compaction keeps a tool call together with its result`() {
        val toolCall = Llm.ToolCall("id1", "file_read", "{\"path\":\"a.txt\"}")
        val messages = buildList {
            add(msg("system", "SYS"))
            add(msg("user", "read a.txt please"))
            add(msg("assistant", "", listOf(toolCall)))
            add(Llm.Message("tool", "RESULT-BODY", toolCallId = "id1"))
        }

        val out = FallbackLlm.compactFor(messages, 100)

        // The assistant tool-call and its result both survive — never split.
        val hasCall = out.any { it.role == "assistant" && it.toolCalls.any { c -> c.id == "id1" } }
        val hasResult = out.any { it.role == "tool" && it.toolCallId == "id1" }
        assertTrue("tool call kept", hasCall)
        assertTrue("tool result kept", hasResult)
    }

    // ------------------------------------------------------------ catalog

    @Test
    fun `cloud model catalog defaults to the free tier`() {
        assertEquals("glm-4.7-flash", CloudModel.fromId(null).id)
        assertEquals("glm-4.7-flash", CloudModel.fromId("garbage").id)
        assertEquals("glm-4.6", CloudModel.fromId("glm-4.6").id)
        assertTrue(CloudModel.GLM_4_7_FLASH.free)
        assertFalse(CloudModel.GLM_5_3.free)
    }

    @Test
    fun `auth error classifier matches Z.ai error shapes`() {
        assertTrue(FallbackLlm.isAuthError(RuntimeException("LLM HTTP 401: unauthorized")))
        assertTrue(FallbackLlm.isAuthError(RuntimeException("LLM HTTP 403: Forbidden")))
        assertTrue(FallbackLlm.isAuthError(RuntimeException("Authorization Token invalid, please check")))
        assertFalse(FallbackLlm.isAuthError(RuntimeException("LLM call failed after 4 attempts: HTTP 529")))
        assertFalse(FallbackLlm.isAuthError(RuntimeException("LLM HTTP 404: model not found")))
    }
}

/** JUnit4 ships assertNotEquals only in 4.13+; tiny local shim for clarity. */
private fun assertNotEquals(unexpected: Any, actual: Any) {
    if (unexpected == actual) {
        throw AssertionError("expected not to equal <$unexpected> but was equal")
    }
}
