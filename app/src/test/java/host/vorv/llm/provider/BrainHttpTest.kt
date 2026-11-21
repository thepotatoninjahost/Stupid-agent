package host.vorv.llm.provider

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainHttpTest {

    private fun headers(provider: Provider?, key: String): Map<String, String> =
        BrainHttp.applyAuth(Request.Builder().url("https://example.com/v1/chat/completions"), provider, key)
            .build()
            .headers
            .toMap()

    @Test
    fun `a key goes in the bearer header by default`() {
        assertEquals("Bearer abc", headers(null, "abc")["Authorization"])
    }

    @Test
    fun `a provider that wants x-api-key gets it without a prefix`() {
        val anthropic = ProviderRegistry.byId("anthropic")!!
        assertEquals("abc", headers(anthropic, "abc")["x-api-key"])
        assertNull(headers(anthropic, "abc")["Authorization"])
    }

    @Test
    fun `a blank key adds no header at all`() {
        assertTrue(headers(null, "   ").isEmpty())
        assertTrue(headers(Provider.custom("https://x"), "").isEmpty())
    }

    @Test
    fun `a refused key is explained as a key problem`() {
        val s = BrainHttp.explain(401, "{\"error\":\"Missing Authentication header\"}", "OpenRouter", "x/y")
        assertTrue(s, s.contains("rejected the key"))
        assertTrue(s, s.contains("OpenRouter"))
    }

    @Test
    fun `a retired model points at Refresh list`() {
        assertTrue(BrainHttp.explain(404, "not found", "Z.ai", "no-such-model").contains("Refresh list"))
        assertTrue(BrainHttp.explain(400, "{\"message\":\"model not found\"}", "Z.ai", "no-such-model").contains("Refresh list"))
    }

    @Test
    fun `busy and broke and outage are told apart`() {
        assertTrue(BrainHttp.explain(429, "", "Groq", "m").contains("not your key"))
        assertTrue(BrainHttp.explain(402, "", "OpenAI", "m").contains("no credit"))
        assertTrue(BrainHttp.explain(503, "", "Cerebras", "m").contains("outage"))
        assertTrue(BrainHttp.explain(null, "", "DeepSeek", "m").contains("could not be reached"))
    }

    @Test
    fun `an empty success is called what it is`() {
        val s = BrainHttp.explain(200, "{\"choices\":[]}", "OpenAI", "gone")
        assertTrue(s, s.contains("probably retired"))
    }
}
