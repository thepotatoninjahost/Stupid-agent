package host.vorv.llm

import host.vorv.llm.BrainDoctor.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.9.18 — every failure the owner saw must name itself and its remedy. */
class BrainDoctorTest {

    private fun facts(
        key: Boolean = true,
        keyError: String? = null,
        base: String = "https://api.z.ai/api/paas/v4",
        model: String = "glm-4.7-flash",
        probe: BrainFacts.Exchange? = null,
        exchange: BrainFacts.Exchange? = null,
        downloaded: Boolean = true,
        bytes: Long = 4_444_000_000L,
        expected: Long = 4_444_121_888L,
        engineError: String? = null,
    ) = BrainFacts(
        llmMode = "cloud",
        providerId = "z.ai",
        providerLabel = "Z.ai",
        baseUrl = base,
        modelId = model,
        keyPresent = key,
        keyReadError = keyError,
        lastProbe = probe,
        lastExchange = exchange,
        localModelId = "qwen7b",
        localModelName = "Qwen2.5-Coder-7B-Instruct",
        localDownloaded = downloaded,
        localBytes = bytes,
        localExpectedBytes = expected,
        localEngineError = engineError,
        freeStorageBytes = 50_000_000_000L,
        abis = listOf("arm64-v8a"),
    )

    private fun check(f: BrainFacts, name: String) =
        BrainDoctor.diagnose(f).firstOrNull { it.name == name } ?: error("no check named $name")

    @Test
    fun `a saved key with an empty reply is a retired model, said in those words`() {
        val c = check(
            facts(
                probe = BrainFacts.Exchange(
                    url = "https://api.z.ai/api/paas/v4/chat/completions",
                    model = "glm-4.7-flash",
                    authorizationSent = true,
                    code = 200,
                    body = """{"choices":[{"message":{"content":""}}]}""",
                ),
            ),
            "last test",
        )
        assertEquals(State.FAIL, c.state)
        assertTrue(c.detail.contains("no text") || c.detail.contains("HTTP 200"))
        assertTrue(c.remedy!!.contains("Refresh list"))
    }

    @Test
    fun `a key that the store could not read is not called missing`() {
        val c = check(facts(key = false, keyError = "Keystore error"), "key")
        assertEquals(State.FAIL, c.state)
        assertTrue(c.detail.contains("could not be read"))
        assertTrue(c.remedy!!.contains("enter the key"))
    }

    @Test
    fun `a wrong address is caught before any request`() {
        val c = check(facts(base = "openrouter.ai/api/v1"), "address")
        assertEquals(State.FAIL, c.state)
        assertTrue(c.detail.contains("https://"))
    }

    @Test
    fun `a short download is called short, with both sizes`() {
        val c = check(facts(bytes = 2_000_000_000L), "on-phone coder")
        assertEquals(State.FAIL, c.state)
        assertTrue(c.detail.contains("4.4 GB"))
        assertTrue(c.detail.contains("2.0 GB"))
    }

    @Test
    fun `an engine build failure is reported with its text`() {
        val c = check(facts(engineError = "model file is not a GGUF"), "model engine")
        assertEquals(State.FAIL, c.state)
        assertTrue(c.detail.contains("GGUF"))
    }

    @Test
    fun `a healthy brain is all OK`() {
        val checks = BrainDoctor.diagnose(
            facts(
                probe = BrainFacts.Exchange(
                    url = "https://api.z.ai/api/paas/v4/chat/completions",
                    model = "glm-4.7-flash",
                    authorizationSent = true,
                    code = 200,
                    body = """{"choices":[{"message":{"content":"VORV_KEY_OK"}}]}""",
                ),
            ),
        )
        assertTrue(checks.none { it.state == State.FAIL })
        assertTrue(BrainDoctor.report(facts()).startsWith("Vorv brain health"))
    }

    @Test
    fun `a request that went out without the key header is a bug, and is named`() {
        val c = check(
            facts(
                exchange = BrainFacts.Exchange(
                    url = "https://api.z.ai/api/paas/v4/chat/completions",
                    model = "glm-4.7-flash",
                    authorizationSent = false,
                    code = 401,
                    body = "Missing Authentication header",
                ),
            ),
            "last request",
        )
        assertEquals(State.FAIL, c.state)
        assertTrue(c.detail.contains("WITHOUT the key header"))
    }
}
