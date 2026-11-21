package host.vorv.llm.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this pins (0.9.18): the owner picks OpenRouter, leaves the model box
 * empty, and the request still goes to Z.ai with a Z.ai model id — a 401/404
 * that looks like a bad key.
 *
 * 0.9.30: there is no chain and no Z.ai fallback model any more. A blank
 * address means the owner's default; an address with no model is refused.
 */
class BrainTargetTest {

    @Test
    fun `a blank address is the owner's default route`() {
        val t = BrainTarget.resolve("", "")
        assertEquals(BrainTarget.DEFAULT_BASE, t.baseUrl)
        assertEquals(BrainTarget.DEFAULT_MODEL, t.modelId)
        assertEquals("qwen/qwen3-coder", t.modelId)
        assertNull(BrainTarget.refusal("", ""))
    }

    @Test
    fun `a chosen address keeps its model, and trailing slashes are normalised`() {
        val t = BrainTarget.resolve("https://api.z.ai/api/paas/v4/", "some-model")
        assertEquals("https://api.z.ai/api/paas/v4", t.baseUrl)
        assertEquals("some-model", t.modelId)
    }

    @Test
    fun `another provider never receives a model id it was not given`() {
        val t = BrainTarget.resolve("https://openrouter.ai/api/v1", "qwen/qwen3-coder")
        assertEquals("https://openrouter.ai/api/v1", t.baseUrl)
        assertEquals("qwen/qwen3-coder", t.modelId)
        assertNull(BrainTarget.refusal("https://openrouter.ai/api/v1", "qwen/qwen3-coder"))
    }

    @Test
    fun `an address with no model is refused, and says what to do`() {
        assertNotNull(BrainTarget.refusal("https://openrouter.ai/api/v1", ""))
        assertTrue(BrainTarget.refusal("https://openrouter.ai/api/v1", "")!!.contains("Refresh list"))
        // H-5: a cleartext address is refused here too.
        assertNotNull(BrainTarget.refusal("http://openrouter.ai/api/v1", "qwen/qwen3-coder"))
    }
}
