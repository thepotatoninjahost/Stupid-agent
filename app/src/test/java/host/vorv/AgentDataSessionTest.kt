package host.vorv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentDataSessionTest {
    @Test fun `cleared state cannot display or include deleted messages in a later prompt`() {
        val cleared = AgentState(
            messages = listOf(ChatMessage(role = "user", content = "private secret")),
            fullOutputView = FullOutputView("output", "private secret"),
            expandedTaskId = "old-task", memoryCount = 3, skillCount = 2,
            hasApiKey = true, screen = "settings",
        ).withoutAgentData()
        assertTrue(cleared.messages.isEmpty())
        assertNull(cleared.fullOutputView)
        assertNull(cleared.expandedTaskId)
        assertTrue(cleared.toolCallsForTask.isEmpty())
        assertEquals(0, cleared.memoryCount)
        assertEquals(0, cleared.skillCount)
        assertTrue(cleared.hasApiKey)
        assertEquals("settings", cleared.screen)
        val prompt = host.vorv.kernel.ConversationPrompt.build(
            "new question", cleared.messages.map {
                host.vorv.kernel.ConversationPrompt.Turn(it.role, it.content)
            }, localEngine = false,
        )
        assertFalse(prompt.contains("private secret"))
    }

    @Test fun `deletion drains pending work and rejects cancellation handler writes`() = runTest {
        val session = AgentDataSession(backgroundScope)
        val events = mutableListOf<String>()
        val finishing = CompletableDeferred<Unit>()
        session.scope.launch {
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                // Models a task's NonCancellable persistence on cancellation.
                withContext(NonCancellable) { finishing.await(); events += "old write" }
                session.scope.launch { events += "late write" }
            }
        }
        runCurrent()
        val deletion = launch { session.clear { events += "delete" } }
        runCurrent()
        assertTrue(session.clearing)
        assertTrue(events.isEmpty())
        session.scope.launch { events += "new task during deletion" }
        finishing.complete(Unit)
        deletion.join()
        runCurrent()
        assertEquals(listOf("old write", "delete"), events)
        assertFalse(session.clearing)
        session.scope.launch { events += "new conversation" }.join()
        assertEquals("new conversation", events.last())
    }

    @Test fun `queued startup restore and persistence cannot run after deletion`() = runTest {
        val session = AgentDataSession(backgroundScope)
        val messages = mutableListOf("private message")
        session.scope.launch { messages += "restored private message" }
        session.scope.launch { messages += "queued private response" }
        session.clear { messages.clear() }
        runCurrent()
        assertTrue(messages.isEmpty())
    }
}
