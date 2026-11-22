package host.vorv.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The owner's "Input prompt too long" on a three-word message came from asking
 * for a 2048-token reply on top of a prompt that already filled the window.
 * The reply allowance must come out of the window that is left.
 */
class LocalLlmBudgetTest {

    @Test
    fun `a small prompt still gets the full output reserve`() {
        assertEquals(
            LocalLlm.MAX_OUTPUT_TOKENS,
            LocalLlm.replyTokensFor(promptTokens = 500, ctx = LocalLlm.N_CTX),
        )
    }

    @Test
    fun `a big prompt shrinks the reply instead of overflowing the window`() {
        val prompt = LocalLlm.N_CTX - 800
        val reply = LocalLlm.replyTokensFor(prompt, LocalLlm.N_CTX)!!
        assertEquals(800 - LocalLlm.PROMPT_SAFETY, reply)
    }

    @Test
    fun `a prompt that leaves no room is refused with null, not a negative allowance`() {
        assertNull(LocalLlm.replyTokensFor(LocalLlm.N_CTX - 100, LocalLlm.N_CTX))
        assertNull(LocalLlm.replyTokensFor(LocalLlm.N_CTX + 500, LocalLlm.N_CTX))
    }

    @Test
    fun `a realistic agent prompt shrinks the reply on the old window and not on the new one`() {
        // System prompt (2.7 KB) + tool roster (2.3 KB) + rendered tool schemas
        // (~12 KB on a big roster) at the kernel's 4 chars/token heuristic.
        val promptTokens = 17_000 / 4
        assertNull(
            "4096 left no room for this prompt, which is why the window was raised",
            LocalLlm.replyTokensFor(promptTokens, LocalLlm.FALLBACK_N_CTX),
        )
        assertEquals(
            LocalLlm.MAX_OUTPUT_TOKENS,
            LocalLlm.replyTokensFor(promptTokens, LocalLlm.N_CTX),
        )
    }
}
