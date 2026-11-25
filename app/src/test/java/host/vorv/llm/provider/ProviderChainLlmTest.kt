package host.vorv.llm.provider

import host.vorv.llm.CloudLlm
import host.vorv.llm.Llm
import host.vorv.llm.LlmHttpException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Gate 0: two independent providers, one broken, chat still answers — and the
 * label names the one that actually answered.
 */
class ProviderChainLlmTest {

    private lateinit var first: MockWebServer
    private lateinit var second: MockWebServer

    @Before
    fun setUp() {
        first = MockWebServer().apply { start() }
        second = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { first.shutdown() }
        runCatching { second.shutdown() }
    }

    private fun reply(text: String) = MockResponse().setResponseCode(200).setBody(
        """{"choices":[{"message":{"content":"$text"},"finish_reason":"stop"}]}""",
    )

    private fun status(code: Int, body: String = """{"error":"no"}""") =
        MockResponse().setResponseCode(code).setBody(body)

    private fun route(id: String, server: MockWebServer, model: String = "m-$id") = ProviderChainLlm.Route(
        providerId = id,
        label = "$id · $model",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "key-$id",
        modelId = model,
    )

    private fun chain(vararg routes: ProviderChainLlm.Route) = ProviderChainLlm(
        routes = routes.toList(),
        engineFor = { r ->
            CloudLlm(
                apiKey = r.apiKey,
                baseUrl = r.baseUrl,
                modelName = r.modelId,
                client = OkHttpClient(),
                maxAttempts = 1,
                backoffMs = 0L,
            )
        },
    )

    private val messages = listOf(Llm.Message("user", "hi"))

    @Test
    fun `a dead first provider is a detour, not the end of the task`() = runBlocking {
        first.enqueue(status(503))
        second.enqueue(reply("the second provider answered"))
        val chain = chain(route("broken", first), route("working", second))

        val response = chain.step(messages, emptyList<Llm.ToolSpec>())

        assertEquals("the second provider answered", (response as Llm.LlmResponse.Text).content)
        assertEquals("working · m-working", chain.answeredBy)
        assertTrue("the label must name the provider that answered", chain.label.startsWith("working · "))
        assertTrue("the reason must be kept", chain.lastFailure!!.contains("broken · m-broken"))
    }

    @Test
    fun `a refused key at one provider falls over to the other`() = runBlocking {
        first.enqueue(status(401, """{"error":"invalid api key"}"""))
        second.enqueue(reply("answered anyway"))
        val chain = chain(route("wrongkey", first), route("goodkey", second))

        chain.step(messages, emptyList<Llm.ToolSpec>())

        assertEquals("goodkey · m-goodkey", chain.answeredBy)
        assertTrue(chain.lastFailure!!.contains("refused the key"))
    }

    @Test
    fun `a malformed request is not retried at another provider`() = runBlocking {
        first.enqueue(status(400, """{"error":"messages[0].role is invalid"}"""))
        val chain = chain(route("strict", first), route("other", second))

        try {
            chain.step(messages, emptyList<Llm.ToolSpec>())
            fail("a request every provider would reject must be rethrown")
        } catch (e: LlmHttpException) {
            assertEquals(400, e.code)
        }
        assertEquals("the second provider must not be called", 0, second.requestCount)
        assertNull(chain.answeredBy)
    }

    @Test
    fun `when no provider answers the error names each one and why`() = runBlocking {
        first.enqueue(status(429))
        second.enqueue(status(402, """{"error":"insufficient balance"}"""))
        val chain = chain(route("busy", first), route("broke", second))

        try {
            chain.step(messages, emptyList<Llm.ToolSpec>())
            fail("every provider failed, so the step must fail")
        } catch (e: RuntimeException) {
            val text = e.message ?: ""
            assertTrue(text, text.contains("busy · m-busy"))
            assertTrue(text, text.contains("broke · m-broke"))
            assertTrue("the sentence is not a shrug", text.contains("No provider could answer"))
        }
        assertNull("nothing answered, so nothing is named", chain.answeredBy)
    }

    @Test
    fun `a provider without a key is never called`() = runBlocking {
        second.enqueue(reply("only one answer"))
        val keyless = route("nokey", first).copy(apiKey = "")
        val chain = chain(keyless, route("withkey", second))

        chain.step(messages, emptyList<Llm.ToolSpec>())

        assertEquals(0, first.requestCount)
        assertEquals("withkey · m-withkey", chain.answeredBy)
    }

    @Test
    fun `the route builder puts the chosen provider first and only keyed ones after it`() {
        val keys = mapOf("openrouter" to "okey", "huggingface" to "hkey")
        val routes = ProviderChainLlm.routes(
            chosen = ProviderRegistry.byId(ProviderRegistry.DEFAULT_ID)!!,
            chosenBaseUrl = "https://openrouter.ai/api/v1",
            chosenModelId = "qwen/qwen3-coder",
            keyFor = { keys[it.id] ?: "" },
        )

        assertEquals("openrouter", routes.first().providerId)
        assertEquals("qwen/qwen3-coder", routes.first().modelId)
        assertEquals(listOf("openrouter", "huggingface"), routes.map { it.providerId })
        assertTrue("the model must be named", routes[1].label.contains("·"))
        assertTrue("a keyed provider keeps its own address", routes[1].baseUrl.contains("huggingface.co"))
    }

    @Test
    fun `a chain with no providers is refused instead of answering into the void`() {
        try {
            ProviderChainLlm(emptyList())
            fail("an empty chain cannot answer anything")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at least one"))
        }
    }
}
