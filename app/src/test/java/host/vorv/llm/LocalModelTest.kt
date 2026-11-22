package host.vorv.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTest {

    @Test
    fun `the prompt budget plus the output reserve fits the context window`() {
        assertTrue(
            LocalLlm.CONTEXT_BUDGET + LocalLlm.MAX_OUTPUT_TOKENS <= LocalLlm.N_CTX,
        )
        // 8192 window − 2048 output − 248 template margin. Pinned because the
        // 4096 window this replaced is exactly what produced "Input prompt too
        // long" on a three-word message (system prompt + tool schemas).
        assertEquals(5896, LocalLlm.CONTEXT_BUDGET)
    }

    @Test
    fun `a whole model is accepted`() {
        val big = 4_444_121_888L
        assertNull(verifyDownloaded("/data/model.gguf", big, big))
        assertNull(verifyDownloaded("/data/model.gguf", big + 4096, big))
        // Rounded published sizes: 99% is still fine.
        assertNull(verifyDownloaded("/data/model.gguf", big / 100 * 99, big))
    }

    @Test
    fun `a missing or truncated model is refused in words`() {
        val missing = verifyDownloaded(null, 0, 4_444_121_888L)
        assertNotNull(missing)
        assertTrue(missing!!.contains("no model file is on disk"))

        val empty = verifyDownloaded("/data/x.gguf", 0, 4_444_121_888L)
        assertTrue(empty!!.contains("no model file is on disk"))

        val half = verifyDownloaded("/data/x.gguf", 2_000_000_000L, 4_444_121_888L)
        assertTrue(half, half!!.contains("incomplete"))
        assertTrue(half.contains("resume"))
    }

    @Test
    fun `every model id is one identifier with its precision`() {
        ModelChoice.entries.forEach { choice ->
            assertEquals("${choice.hfRepo}:${choice.precision}", choice.geniexModelId())
            assertTrue("hfRepo must carry no precision suffix", !choice.hfRepo.contains(":"))
        }
    }

    @Test
    fun `a stored id maps back to itself and an unknown id is not silently accepted`() {
        ModelChoice.entries.forEach { assertEquals(it, ModelChoice.fromId(it.id)) }
        // Unknown ids fall back to the documented default rather than a null engine.
        assertEquals(ModelChoice.OMNICODER9B, ModelChoice.fromId("not-a-model"))
    }

    @Test
    fun `there is exactly one on-device model and it is Q4_0`() {
        assertEquals(1, ModelChoice.entries.size)
        assertEquals("Q4_0", ModelChoice.entries.single().precision)
    }
}
