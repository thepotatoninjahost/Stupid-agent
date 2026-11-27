package host.vorv.llm.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router and the registry are the answer to "the one cloud model was overloaded and the app
 * had nothing else". These tests pin the behaviour that makes that answer real:
 * one address form, the owner's own provider honoured, and a broken key never
 * silently preferred over a working one.
 */
class ProviderRegistryTest {

    @Test
    fun `every preset has a unique id and an https address`() {
        val ids = ProviderRegistry.presets.map { it.id }
        assertEquals("preset ids must be unique", ids.size, ids.toSet().size)
        ProviderRegistry.presets.forEach { p ->
            // The owner's own entry starts blank on purpose: it is filled in
            // from Setup, and an invented address would be a lie.
            if (p.baseUrl.isBlank()) {
                assertEquals("only the owner's own entry may start empty", "custom", p.id)
                return@forEach
            }
            assertTrue("${p.id} must use https", p.baseUrl.startsWith("https://"))
            assertTrue("${p.id} must not end in a slash", !p.baseUrl.endsWith("/"))
        }
    }

    @Test
    fun `the ids named in the model screenshots are never offered`() {
        val blocked = listOf(
            "openai/gpt-oss-20b",
            "openai/gpt-oss-safeguard-20b",
            "openai/gpt-oss-120b",
            "meta-llama/llama-prompt-guard-2-22m",
            "meta-llama/llama-prompt-guard-2-86m",
            "allam-2-7b",
            "canopylabs/orpheus-arabic-saudi",
        )
        blocked.forEach { assertTrue("$it must be hidden", ProviderRegistry.isRemovedModelId(it)) }
        // Case and padding come from a provider's own list, so they must not
        // open a hole in the filter.
        assertTrue(ProviderRegistry.isRemovedModelId("  OpenAI/GPT-OSS-20B "))
        // Neighbours are not swept up with them.
        assertFalse(ProviderRegistry.isRemovedModelId("openai/gpt-oss-20b-vision"))
        assertFalse(ProviderRegistry.isRemovedModelId("google/gemma-4-31b-it:free"))
        assertEquals(8, ProviderRegistry.hiddenModelCount(blocked + listOf("z-ai/glm-5")))
    }

    @Test
    fun `every route the app has shipped with is present, and a saved id resolves to itself`() {
        assertEquals(
            listOf(
                "openrouter", "huggingface",
                "deepseek", "groq", "cerebras", "together", "fireworks", "mistral",
                "google", "openai", "anthropic",
                "custom",
            ),
            ProviderRegistry.presets.map { it.id },
        )
        // The 2026-11-25 cut to three routes deleted nine working providers and
        // left any saved selection pointing at nothing. A saved id must resolve
        // to itself again instead of silently landing on the default route.
        listOf(
            "deepseek", "groq", "cerebras", "together", "fireworks", "mistral",
            "google", "openai", "anthropic",
        ).forEach { id ->
            assertEquals(id, ProviderRegistry.orDefault(id).id)
            assertNotNull("$id must carry an address", ProviderRegistry.byId(id)?.baseUrl)
        }
        // A typed address still works for a provider that is not shipped.
        val adHoc = ProviderRegistry.byIdOrCustom("mine", "https://llm.example.test/v1")
        assertEquals("https://llm.example.test/v1", adHoc.baseUrl)
    }

    @Test
    fun `Hugging Face is offered, and says it is not a no-cost route`() {
        val hf = ProviderRegistry.byId("huggingface")!!
        assertEquals("https://router.huggingface.co/v1", hf.baseUrl)
        assertEquals("/models", hf.modelsPath)
        // Their pricing doc: free accounts get no monthly credits.
        assertFalse(hf.freeTier)
        assertTrue(hf.notes.contains("no monthly credits"))
        assertTrue(hf.suggestions.any { it.endsWith(":fastest") })
    }

    @Test
    fun `the default provider is OpenRouter with a free model the owner can run`() {
        assertEquals("openrouter", ProviderRegistry.DEFAULT_ID)
        val d = ProviderRegistry.byId(ProviderRegistry.DEFAULT_ID)!!
        assertEquals("https://openrouter.ai/api/v1", d.baseUrl)
        assertEquals("openrouter/free", d.defaultModel)
        // An install that still holds "z.ai" (or nothing) must land on the
        // default route rather than on a provider that no longer exists.
        assertEquals(ProviderRegistry.DEFAULT_ID, ProviderRegistry.orDefault("").id)
        assertEquals(ProviderRegistry.DEFAULT_ID, ProviderRegistry.orDefault("z.ai").id)
        assertEquals("key.openrouter", host.vorv.data.SecretsStore.prefNameFor("openrouter"))
        // The legacy slot is still readable, so an old key is never lost.
        assertEquals("zai_api_key", host.vorv.data.SecretsStore.prefNameFor("z.ai"))
    }

    @Test
    fun `chat url is built once, without doubling the path`() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            ProviderRegistry.chatCompletionsUrl("https://openrouter.ai/api/v1"),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            ProviderRegistry.chatCompletionsUrl("https://openrouter.ai/api/v1/"),
        )
        // An owner who pasted the full endpoint (a very common paste) must not
        // end up calling /chat/completions/chat/completions.
        assertEquals(
            "https://example.test/v1/chat/completions",
            ProviderRegistry.chatCompletionsUrl("https://example.test/v1/chat/completions"),
        )
    }

    @Test
    fun `an unknown id becomes the owner's own provider, not a crash`() {
        val p = ProviderRegistry.byIdOrCustom("mine", "https://llm.example.test/v1")
        assertEquals("https://llm.example.test/v1", p.baseUrl)
        assertEquals("mine", p.label)
        // One custom slot, one key slot: two different pasted addresses must
        // not quietly share (or each need) a separate key.
        assertEquals("key.custom", p.keyId)
    }

    @Test
    fun `the owner's provider with no key is never ordered first`() {
        val router = ProviderRouter()
        val custom = Provider.custom("https://llm.example.test/v1")
        val zai = ProviderRegistry.byId(ProviderRegistry.DEFAULT_ID)!!
        val order = router.order(
            chosen = custom.id,
            candidates = listOf(custom, zai),
            hasKey = { it.id == zai.id },
        )
        assertEquals(listOf(zai.id), order.map { it.id })
    }

    @Test
    fun `a broken key is tried after a working one`() {
        val router = ProviderRouter()
        val a = Provider("a", "A", "https://a.test/v1")
        val b = Provider("b", "B", "https://b.test/v1")
        router.record("a", code = 401)
        val order = router.order("a", listOf(a, b), hasKey = { true })
        assertEquals("the chosen provider still goes first while it is usable", "a", order.first().id)
        // But a provider that is not the chosen one and has a broken key is
        // last, so one dead key cannot cost a task its first attempt.
        val second = router.order("b", listOf(a, b), hasKey = { true })
        assertEquals(listOf("b", "a"), second.map { it.id })
    }

    @Test
    fun `out of credit cools down far longer than a busy provider`() {
        var now = 1_000_000L
        val router = ProviderRouter(clock = { now })
        val busy = router.record("busy", code = 429)
        val broke = router.record("broke", code = 402)
        assertTrue("a rate limit must not disable a provider for long",
            busy.until - now in 1..120_000)
        assertTrue("no credit must not be retried every minute",
            broke.until - now >= 10 * 60_000)
        assertEquals(ProviderRouter.Health.BUSY, router.state("busy").health)
        assertEquals(ProviderRouter.Health.NO_CREDIT, router.state("broke").health)
    }

    @Test
    fun `a good answer clears the previous failure`() {
        val router = ProviderRouter()
        router.record("a", code = 429)
        assertEquals(ProviderRouter.Health.BUSY, router.state("a").health)
        router.record("a", code = 200)
        assertEquals(ProviderRouter.Health.OK, router.state("a").health)
        assertEquals(0L, router.state("a").until)
    }
    @Test
    fun `no preset offers a GLM model, and a live listing cannot offer one either`() {
        val offered = ProviderRegistry.presets.flatMap { it.suggestions + it.defaultModel }
        assertTrue("no compiled GLM id may survive: $offered", offered.none { ProviderRegistry.isRemovedModelId(it) })
        assertTrue(ProviderRegistry.isRemovedModelId("glm-4.7-flash"))
        assertTrue(ProviderRegistry.isRemovedModelId("z-ai/glm-4.7"))
        assertTrue(ProviderRegistry.isRemovedModelId("GLM-5.3-Flash"))
        assertTrue(!ProviderRegistry.isRemovedModelId("qwen/qwen3-coder"))
        assertEquals(null, ProviderRegistry.byId("z.ai"))
    }
}
