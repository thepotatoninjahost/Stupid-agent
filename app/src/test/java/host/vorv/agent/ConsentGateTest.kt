package host.vorv.agent

import host.vorv.AgentState
import host.vorv.tools.SessionGrants
import host.vorv.tools.ShellTool
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentGateTest {

    private fun gate(timeoutMs: Long = 60_000L): Pair<ConsentGate, MutableStateFlow<AgentState>> {
        val state = MutableStateFlow(AgentState())
        return ConsentGate(state, timeoutMs) to state
    }

    @Test
    fun `allow reaches the tool that asked`() = runTest {
        SessionGrants.resetForTest()
        val (gate, state) = gate()
        val answer = async { gate.request("Vorv wants to write a file. Allow?") }
        runCurrent()

        val pending = state.value.pendingConfirmation ?: error("the question was never shown")
        assertEquals("Vorv wants to write a file. Allow?", pending.message)
        assertFalse("only the shell consent offers the session grant", pending.offerSessionGrant)

        gate.respond(pending.id, true)
        runCurrent()

        assertTrue(answer.await())
        assertNull("the dialog comes down once answered", state.value.pendingConfirmation)
    }

    @Test
    fun `deny reaches the tool that asked`() = runTest {
        val (gate, state) = gate()
        val answer = async { gate.request("Vorv wants to write a file. Allow?") }
        runCurrent()

        gate.respond(state.value.pendingConfirmation!!.id, false)
        runCurrent()

        assertFalse(answer.await())
    }

    @Test
    fun `silence is no and the dialog comes down`() = runTest {
        val (gate, state) = gate(timeoutMs = 5_000L)
        val answer = async { gate.request("Vorv wants to write a file. Allow?") }
        runCurrent()
        assertTrue(state.value.pendingConfirmation != null)

        advanceTimeBy(5_001L)       // virtual time past the timeout
        runCurrent()
        assertFalse("an unanswered question must not act", answer.await())
        assertNull(state.value.pendingConfirmation)
    }

    @Test
    fun `an answer to a question that is gone changes nothing`() = runTest {
        val (gate, state) = gate()
        val answer = async { gate.request("Vorv wants to write a file. Allow?") }
        runCurrent()
        val id = state.value.pendingConfirmation!!.id

        gate.respond("some-other-id", true)
        runCurrent()
        assertTrue("the wrong answer must not settle this question", state.value.pendingConfirmation != null)

        gate.respond(id, true)
        runCurrent()
        assertTrue(answer.await())
    }

    @Test
    fun `only the shell question offers the session grant`() = runTest {
        SessionGrants.resetForTest()
        val (gate, state) = gate()
        val answer = async {
            gate.request("${ShellTool.CONSENT_PREFIX}\n\nls -la\n\nAllow?")
        }
        runCurrent()
        assertTrue(state.value.pendingConfirmation!!.offerSessionGrant)

        gate.respond(state.value.pendingConfirmation!!.id, false)
        runCurrent()
        assertFalse(answer.await())
    }
}
