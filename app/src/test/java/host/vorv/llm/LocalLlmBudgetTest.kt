package host.vorv.llm

import com.geniex.sdk.bean.ComputeUnitValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

/**
 * GenieX's ModelConfig documents nGpuLayers = -1 as "all layers" and 0 as
 * "none", and its JNI forces 0 for CPU. A build that asked for the NPU with
 * 0 offloaded nothing, so it was really a CPU engine.
 */
class LocalLlmLayersTest {

    @Test
    fun `the NPU gets every layer`() {
        assertEquals(-1, LocalLlm.gpuLayersFor(ComputeUnitValue.NPU.value))
    }

    @Test
    fun `the GPU gets every layer`() {
        assertEquals(-1, LocalLlm.gpuLayersFor(ComputeUnitValue.GPU.value))
    }

    @Test
    fun `the CPU build offloads nothing`() {
        assertEquals(0, LocalLlm.gpuLayersFor(ComputeUnitValue.CPU.value))
    }
}

/**
 * Field report 2026-11-22, on the phone: "prompt budget 5896 tokens after
 * compaction" and "the prompt is about 7971 tokens" on the same call, on an
 * 8192 window. Compaction counted message text; the chat template also renders
 * the tool schemas. The roster is prompt, so it is paid before the history.
 */
class LocalPromptBudgetTest {

    private fun tool(name: String, schemaChars: Int) = Llm.ToolSpec(
        name = name,
        description = "does $name",
        parameters = com.google.gson.JsonObject().apply {
            addProperty("type", "object")
            addProperty("pad", "x".repeat(schemaChars))
        },
    )

    @Test
    fun `no tools cost nothing`() {
        assertEquals(0, LocalLlm.toolRosterTokens(emptyList()))
    }

    @Test
    fun `a bigger roster costs more prompt`() {
        val two = LocalLlm.toolRosterTokens(List(2) { tool("t$it", 200) })
        val forty = LocalLlm.toolRosterTokens(List(40) { tool("t$it", 200) })
        assertTrue("40 tools must cost more than 2: $forty vs $two", forty > two)
        assertTrue("a 40-tool roster is not free: $forty", forty > 1_000)
    }

    @Test
    fun `the budget is the window minus the reply, the template and the roster`() {
        assertEquals(3_821, LocalLlm.promptBudgetFor(8_192, 2_048, 248, 2_075))
    }

    @Test
    fun `a roster larger than the window leaves zero, never a negative budget`() {
        assertEquals(0, LocalLlm.promptBudgetFor(4_096, 2_048, 248, 5_000))
    }

    @Test
    fun `before a load, and after release, the budget assumes the smallest window`() {
        // release() sets loadedCtx to 0; quoting the old (or the top) window
        // would compact for a window the next load may not get.
        assertEquals(LocalLlm.FALLBACK_N_CTX, LocalLlm.effectiveWindow(0))
        assertEquals(8_192, LocalLlm.effectiveWindow(8_192))
        assertEquals(LocalLlm.N_CTX, LocalLlm.effectiveWindow(LocalLlm.N_CTX))
    }

    @Test
    fun `the window ladder starts at 16384 on a 12 GB phone`() {
        assertEquals(listOf(16_384, 8_192, 4_096), LocalLlm.contextLadder(12L * 1024 * 1024 * 1024))
    }

    @Test
    fun `a 12 GB phone reports 11 GB, not 12 GiB, and still gets 16384`() {
        // Field 2026-11-23: the ladder compared GiB against 11.0, but RAM is
        // sold in decimal GB. An S25-class 12 GB phone reports MemTotal of
        // ~11.4 GB = 10.6 GiB, fell to the 8192 rung, and the owner kept
        // hitting the window the fix had already outgrown.
        assertEquals(listOf(16_384, 8_192, 4_096), LocalLlm.contextLadder(11_400_000_000L))
        // 11 GB exactly is the bar, in GB.
        assertEquals(listOf(16_384, 8_192, 4_096), LocalLlm.contextLadder(11_000_000_000L))
    }

    @Test
    fun `a 6 GB phone still gets the 4096 rung`() {
        // Decimal GB must not promote a phone that cannot hold the 8192 window.
        assertEquals(listOf(4_096), LocalLlm.contextLadder(5_900_000_000L))
        assertEquals(listOf(8_192, 4_096), LocalLlm.contextLadder(7_400_000_000L))
    }

    @Test
    fun `an 8 GB phone gets 8192, never the window it cannot hold`() {
        assertEquals(listOf(8_192, 4_096), LocalLlm.contextLadder(8L * 1024 * 1024 * 1024))
    }

    @Test
    fun `a small phone still gets an engine`() {
        assertEquals(listOf(4_096), LocalLlm.contextLadder(4L * 1024 * 1024 * 1024))
    }

    @Test
    fun `the owner's 10 000-token ask is met on the 16384 window`() {
        // 40 tools with a ~250-char schema is the roster the phone measured
        // at ~2000 prompt tokens when the 8192 window overflowed.
        val roster = List(40) { tool("t$it", 220) }
        val budget = LocalLlm.promptBudgetFor(
            16_384, LocalLlm.MAX_OUTPUT_TOKENS, LocalLlm.TEMPLATE_MARGIN,
            LocalLlm.toolRosterTokens(roster),
        )
        assertTrue("the history budget must clear 10 000 tokens, was $budget", budget > 10_000)
    }

    @Test
    fun `the roster is subtracted from the budget the kernel compacts against`() {
        val roster = List(40) { tool("t$it", 400) }
        val withTools = LocalLlm.promptBudgetFor(
            LocalLlm.N_CTX, LocalLlm.MAX_OUTPUT_TOKENS, LocalLlm.TEMPLATE_MARGIN,
            LocalLlm.toolRosterTokens(roster),
        )
        assertTrue(
            "a 40-tool roster must leave less than the flat ${LocalLlm.CONTEXT_BUDGET} budget: $withTools",
            withTools < LocalLlm.CONTEXT_BUDGET,
        )
        assertTrue("the budget must stay positive on the full window: $withTools", withTools > 0)
    }
}
