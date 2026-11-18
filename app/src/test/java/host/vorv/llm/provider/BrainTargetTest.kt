package host.vorv.llm.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this pins: the owner picks OpenRouter, leaves the model box empty, and
 * the request still goes to Z.ai with a Z.ai model id — a 401/404 that looks
 * like a bad key. Only Z.ai may use the fallback chain.
 */
class BrainTargetTest {

    @Test
    fun `no address means the z ai chain`() {
        val t = BrainTarget.resolve("", "", "glm-4.7-flash")
        assertTrue(t.useChain)
        assertEquals(BrainTarget.ZAI_BASE, t.baseUrl)
        assertEquals("glm-4.7-flash", t.modelId)
    }

    @Test
    fun `z ai with a named model is one engine, not the chain`() {
        val t = BrainTarget.resolve("https://api.z.ai/api/paas/v4/", "glm-4.6", "glm-4.7-flash")
        assertFalse(t.useChain)
        assertEquals("https://api.z.ai/api/paas/v4", t.baseUrl)
        assertEquals("glm-4.6", t.modelId)
    }

    @Test
    fun `another provider never falls back to a z ai model`() {
        val t = BrainTarget.resolve("https://openrouter.ai/api/v1", "qwen/qwen3-coder", "glm-4.7-flash")
        assertFalse(t.useChain)
        assertEquals("https://openrouter.ai/api/v1", t.baseUrl)
        assertEquals("qwen/qwen3-coder", t.modelId)
    }

    @Test
    fun `another provider with no model is refused, and says what to do`() {
        val t = BrainTarget.resolve("https://openrouter.ai/api/v1", "", "glm-4.7-flash")
        assertFalse(t.useChain)
        assertEquals("", t.modelId)
        assertNotNull(BrainTarget.refusal("https://openrouter.ai/api/v1", ""))
        assertTrue(BrainTarget.refusal("https://openrouter.ai/api/v1", "")!!.contains("Refresh list"))
        assertNull(BrainTarget.refusal("https://openrouter.ai/api/v1", "qwen/qwen3-coder"))
        assertNull(BrainTarget.refusal("", ""))
    }
}
