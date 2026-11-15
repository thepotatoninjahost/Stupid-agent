package host.vorv.llm

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The complaint these cover: the key was fine, Z.ai was just busy, and one
 * overloaded model ended the task while three other models sat unused.
 */
class CloudChainLlmTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun reply(text: String) = MockResponse().setResponseCode(200).setBody(
        """{"choices":[{"message":{"content":"$text"},"finish_reason":"stop"}]}""",
    )

    private fun busy() = MockResponse().setResponseCode(429).setBody("""{"error":"rate limited"}""")

    private fun chain(vararg models: CloudModel, now: () -> Long = { System.currentTimeMillis() }) =
        CloudChainLlm(
            apiKey = "k",
            models = models.toList(),
            baseUrl = server.url("/v4").toString().trimEnd('/'),
            clock = now,
            engineFor = { m ->
                CloudLlm(
                    apiKey = "k",
                    baseUrl = server.url("/v4").toString().trimEnd('/'),
                    modelName = m.id,
                    client = OkHttpClient(),
                    maxAttempts = 1,
                    backoffMs = 0L,
                )
            },
        )

    private val messages = listOf(Llm.Message("user", "hi"))

    @Test
    fun `an overloaded model falls over to the next one`() = runBlocking {
        server.enqueue(busy())
        server.enqueue(reply("answered by the second model"))
        val chain = chain(CloudModel.GLM_4_7_FLASH, CloudModel.GLM_5_3_FLASH)

        val response = chain.step(messages, emptyList())

        assertEquals("answered by the second model", (response as Llm.LlmResponse.Text).content)
        assertEquals("GLM-5.3-Flash", chain.lastModelUsed)
        assertEquals(2, server.requestCount)
        assertTrue("the busy model's reason must be recorded", chain.lastOverloadReason!!.contains("busy"))
    }

    @Test
    fun `a busy model is skipped for the next step instead of retried`() = runBlocking {
        var clock = 0L
        server.enqueue(busy())
        server.enqueue(reply("first answer"))
        server.enqueue(reply("second answer"))
        val chain = chain(CloudModel.GLM_4_7_FLASH, CloudModel.GLM_5_3_FLASH) { clock }

        chain.step(messages, emptyList())
        val second = chain.step(messages, emptyList())

        assertEquals("second answer", (second as Llm.LlmResponse.Text).content)
        // Three requests total: busy, answer, answer — the busy model was not
        // hammered again while it was cooling down.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `every model busy reports that the provider is busy, not that the key is bad`() = runBlocking {
        server.enqueue(busy())
        server.enqueue(busy())
        val chain = chain(CloudModel.GLM_4_7_FLASH, CloudModel.GLM_5_3)

        try {
            chain.step(messages, emptyList())
            fail("expected the whole chain to fail")
        } catch (e: RuntimeException) {
            val m = e.message.orEmpty()
            assertTrue("must name the models: $m", m.contains("GLM-4.7-Flash") && m.contains("GLM-5.3"))
            assertTrue("must say the provider is busy: $m", m.contains("not your key"))
            assertTrue("must offer the offline path: $m", m.contains("on-device"))
        }
    }

    @Test
    fun `a model the account has no credit for is skipped, not reported as an outage`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(402).setBody("""{"error":{"message":"insufficient balance"}}"""),
        )
        server.enqueue(reply("free model answered"))
        val chain = chain(CloudModel.GLM_5_3, CloudModel.GLM_4_7_FLASH)

        val response = chain.step(messages, emptyList())

        assertEquals("free model answered", (response as Llm.LlmResponse.Text).content)
        assertEquals("GLM-4.7-Flash", chain.lastModelUsed)
    }

    @Test
    fun `only paid models with no credit says so instead of blaming the key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":"insufficient balance"}"""))
        val chain = chain(CloudModel.GLM_5_3)

        try {
            chain.step(messages, emptyList())
            fail("expected a refusal")
        } catch (e: RuntimeException) {
            val m = e.message.orEmpty()
            assertTrue("must name the free option: $m", m.contains("GLM-4.7-Flash"))
            assertTrue("must not blame the key: $m", m.contains("no key is wrong"))
        }
    }

    @Test
    fun `the free model is tried before the paid ones by default`() {
        val chain = LlmRouter.pick("cloud", "k", local = null)
        val label = chain.label
        assertTrue("free tier must come first: $label", label.indexOf("GLM-4.7-Flash") < label.indexOf("GLM-5.3-Flash"))
    }

    @Test
    fun `a rejected key is not retried on another model`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid api key"}"""))
        val chain = chain(CloudModel.GLM_4_7_FLASH, CloudModel.GLM_5_3_FLASH)

        try {
            chain.step(messages, emptyList())
            fail("expected the 401 to surface")
        } catch (e: LlmHttpException) {
            assertEquals(401, e.code)
        }
        assertEquals("a 401 will be identical on every model", 1, server.requestCount)
    }
}
