package host.vorv.kernel

import host.vorv.kernel.ConversationPrompt.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Night session 2026-11-08 — tests for the amnesia fix. Before this, every
 * send() handed the kernel ONLY the new message: "try again" arrived as two
 * words in a void and the model honestly answered "I don't have context for
 * what you'd like me to try again" (screen recording 20:32, after a 5-minute
 * task timeout). These tests pin the contract: recent turns ride along,
 * budgets are honest per engine, the new message is never mangled.
 */
class ConversationPromptTest {

    @Test
    fun `no history returns the message unchanged`() {
        assertEquals("try again", ConversationPrompt.build("try again", emptyList(), localEngine = false))
    }

    @Test
    fun `history is included before the new message`() {
        val history = listOf(
            Turn("user", "you just crashed again."),
            Turn("assistant", "Task timed out after 5 minutes and was stopped. Original task: \"diagnose the crash\"."),
        )
        val prompt = ConversationPrompt.build("try again", history, localEngine = false)

        assertTrue(prompt.contains("[Recent conversation"))
        assertTrue(prompt.contains("User: you just crashed again."))
        assertTrue(prompt.contains("Vorv: Task timed out after 5 minutes"))
        assertTrue(prompt.contains("[New message]"))
        assertTrue(prompt.endsWith("try again"))
        // Order: history before the new message.
        assertTrue(prompt.indexOf("you just crashed again.") < prompt.indexOf("[New message]"))
    }

    @Test
    fun `assistant turns are labeled Vorv and user turns User`() {
        val history = listOf(
            Turn("user", "hello"),
            Turn("assistant", "hi"),
        )
        val prompt = ConversationPrompt.build("q", history, localEngine = false)
        assertTrue(prompt.contains("User: hello"))
        assertTrue(prompt.contains("Vorv: hi"))
    }

    @Test
    fun `local engine keeps a tighter window than cloud`() {
        // Zero-padded markers: "message 12" must not contain "message 1" —
        // unpadded markers made the assertFalse checks self-defeating
        // (caught by CI, run 42469102622).
        val history = (1..12).map { Turn("user", "message %02d here".format(it)) }

        val local = ConversationPrompt.build("now", history, localEngine = true)
        val cloud = ConversationPrompt.build("now", history, localEngine = false)

        // Local: only the last 4 turns fit.
        assertFalse(local.contains("message 01 here"))
        assertFalse(local.contains("message 08 here"))
        assertTrue(local.contains("message 12 here"))
        // Cloud: the last 10 fit.
        assertFalse(cloud.contains("message 01 here"))
        assertFalse(cloud.contains("message 02 here"))
        assertTrue(cloud.contains("message 03 here"))
        assertTrue(cloud.contains("message 12 here"))
    }

    @Test
    fun `oversized turns are truncated to the per-message budget`() {
        val fat = "x".repeat(5000)
        val prompt = ConversationPrompt.build("now", listOf(Turn("user", fat)), localEngine = true)
        // Local budget: 200 chars per message. One truncated turn + labels,
        // never the full 5000.
        assertFalse(prompt.contains("x".repeat(400)))
        assertTrue(prompt.contains("x".repeat(200)))
    }

    @Test
    fun `total budget stops the history with an honest omission note`() {
        // 10 turns × 400 chars = 4000 > cloud budget 3000 → the note must
        // appear and only the OLDEST turns may be dropped. Markers lead each
        // turn (head-truncation to 400 chars would eat a trailing marker)
        // and are zero-padded ("marker-10" must not contain "marker-01").
        // This test caught a real design flaw on CI (run 42469102622): the
        // budget walk used to run oldest→newest and silently dropped the
        // NEWEST turns — the exact amnesia this class exists to fix.
        val history = (1..10).map { Turn("assistant", "marker-%02d done".format(it) + "y".repeat(400)) }
        val prompt = ConversationPrompt.build("now", history, localEngine = false)

        assertTrue(prompt.contains("older turns omitted"))
        assertFalse(prompt.contains("marker-01 done"))
        assertFalse(prompt.contains("marker-02 done"))
        assertTrue(prompt.contains("marker-10 done"))
        assertTrue(prompt.contains("marker-04 done"))
    }

    @Test
    fun `newlines inside history turns are flattened not dropped`() {
        val history = listOf(Turn("user", "line one\nline two"))
        val prompt = ConversationPrompt.build("now", history, localEngine = false)
        assertTrue(prompt.contains("line one line two"))
    }
}
