package host.vorv.llm.provider

import org.junit.Assert.assertEquals
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
    fun `z ai keeps the address and key slot existing installs already use`() {
        val z = ProviderRegistry.byId(ProviderRegistry.ZAI_ID)
        assertNotNull(z)
        assertEquals("https://api.z.ai/api/paas/v4", z!!.baseUrl)
        // The 0.6.0 install wrote KEY_ZAI; the per-provider store must keep
        // that exact slot, or every upgrade loses its key.
        assertEquals("zai_api_key", host.vorv.data.SecretsStore.prefNameFor("z.ai"))
        assertEquals("key.openrouter", host.vorv.data.SecretsStore.prefNameFor("openrouter"))
    }

    @Test
    fun `chat url is built once, without doubling the path`() {
        assertEquals(
            "https://api.z.ai/api/paas/v4/chat/completions",
            ProviderRegistry.chatCompletionsUrl("https://api.z.ai/api/paas/v4"),
        )
        assertEquals(
            "https://api.z.ai/api/paas/v4/chat/completions",
            ProviderRegistry.chatCompletionsUrl("https://api.z.ai/api/paas/v4/"),
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
        val zai = ProviderRegistry.byId(ProviderRegistry.ZAI_ID)!!
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
}
