package host.vorv.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.9.18 — the four screenshots of 2026-11-17, turned into rules.
 *
 * Each test is a state the owner actually saw, and the answer the app must
 * give now instead of "I have no brain to think with yet".
 */
class BrainChoiceResolveTest {

    private fun facts(
        mode: String = "cloud",
        key: Boolean = false,
        model: String = "",
        local: Boolean = false,
        probe: BrainFacts.Exchange? = null,
        exchange: BrainFacts.Exchange? = null,
        keyError: String? = null,
    ) = BrainFacts(
        llmMode = mode,
        providerId = "z.ai",
        providerLabel = "Z.ai",
        baseUrl = "https://api.z.ai/api/paas/v4",
        modelId = model,
        keyPresent = key,
        keyReadError = keyError,
        lastProbe = probe,
        lastExchange = exchange,
        localModelId = "qwen7b",
        localModelName = "Qwen2.5-Coder-7B-Instruct",
        localDownloaded = local,
    )

    private fun ok200(text: String, at: Long = 1_000L) = BrainFacts.Exchange(
        url = "https://x/v1/chat/completions",
        model = "m",
        authorizationSent = true,
        code = 200,
        body = """{"choices":[{"message":{"content":"$text"}}]}""",
        at = at,
    )

    @Test
    fun `an empty 200 is not a working brain`() {
        val r = BrainChoice.resolve(
            facts(key = true, model = "glm-4.7-flash", probe = ok200("")),
        )
        assertFalse(r.usable)
        assertTrue(r.reason!!.contains("no text"))
        assertTrue(r.reason!!.contains("retired"))
    }

    @Test
    fun `a custom model with no key is labelled and refused with the same reason`() {
        val r = BrainChoice.resolve(facts(key = false, model = "NVIDIA: Nemotron 3 Ultra"))
        assertFalse(r.usable)
        // The header may not claim a brain that is not there; the explanation
        // underneath must name the exact missing thing.
        assertTrue(r.label.contains("No brain"))
        assertTrue(r.notice!!.contains("no key"))
        assertTrue(r.reason!!.contains("no key saved for Z.ai"))
    }

    @Test
    fun `a key that the provider refuses stops the claim, not just the message`() {
        val r = BrainChoice.resolve(
            facts(
                key = true,
                model = "m",
                exchange = BrainFacts.Exchange(
                    url = "https://openrouter.ai/api/v1/chat/completions",
                    model = "m",
                    authorizationSent = true,
                    code = 401,
                    body = """{"error":{"message":"Missing Authentication header"}}""",
                    at = 2_000L,
                ),
            ),
        )
        assertFalse(r.usable)
        assertTrue(r.reason!!.contains("401"))
        assertTrue(r.reason!!.contains("refused the key"))
    }

    @Test
    fun `an unreadable key is never reported as a missing key`() {
        val r = BrainChoice.resolve(facts(key = false, model = "m", keyError = "Keystore error"))
        assertFalse(r.usable)
        assertTrue(r.label.contains("No brain"))
        assertTrue(r.notice!!.contains("unreadable"))
        assertTrue(r.reason!!.contains("could not be read"))
    }

    @Test
    fun `a cloud failure hands over to a downloaded coder, and says why`() {
        val r = BrainChoice.resolve(facts(key = true, model = "m", local = true, probe = ok200("")))
        assertEquals("local", r.mode)
        assertTrue(r.usable)
        assertNotNull(r.notice)
        assertTrue(r.notice!!.startsWith("cloud:"))
    }

    @Test
    fun `a provider that is merely busy is still worth trying`() {
        val r = BrainChoice.resolve(
            facts(
                key = true,
                model = "m",
                exchange = BrainFacts.Exchange(
                    url = "https://x/v1/chat/completions",
                    model = "m",
                    authorizationSent = true,
                    code = 429,
                    body = "busy",
                    at = 3_000L,
                ),
            ),
        )
        assertTrue(r.usable)
    }

    @Test
    fun `nothing at all names both fixes in one sentence`() {
        val r = BrainChoice.resolve(facts())
        assertFalse(r.usable)
        assertTrue(r.label.contains("No brain yet"))
        assertTrue(r.notice!!.contains("no key"))
        assertTrue(r.notice!!.contains("on-phone coder"))
    }

    @Test
    fun `the newest exchange wins over an older probe`() {
        val r = BrainChoice.resolve(
            facts(
                key = true,
                model = "m",
                probe = ok200("VORV_KEY_OK", at = 1_000L),
                exchange = BrainFacts.Exchange(
                    url = "https://x/v1/chat/completions",
                    model = "m",
                    authorizationSent = true,
                    code = 404,
                    body = "not found",
                    at = 5_000L,
                ),
            ),
        )
        assertFalse(r.usable)
        assertTrue(r.reason!!.contains("404"))
    }
}
