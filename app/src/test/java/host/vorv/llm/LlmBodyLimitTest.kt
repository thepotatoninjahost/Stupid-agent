package host.vorv.llm

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** M-1: a completion body is read to a ceiling, and a bad reply names itself. */
class LlmBodyLimitTest {

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

    private fun llm(attempts: Int = 1) = CloudLlm(
        apiKey = "k",
        baseUrl = server.url("/v4").toString().trimEnd('/'),
        modelName = "some-model",
        client = OkHttpClient(),
        maxAttempts = attempts,
        backoffMs = 0L,
    )

    @Test
    fun `readBounded stops at the ceiling and says so`() {
        val big = "x".repeat(CloudLlm.MAX_RESPONSE_BYTES + 5_000)
        val text = CloudLlm.readBounded(big.toResponseBody("text/plain".toMediaType()))
        assertTrue("the read must be bounded", text.length <= CloudLlm.MAX_RESPONSE_CHARS + 200)
        assertTrue(text.contains("…(truncated at ${CloudLlm.MAX_RESPONSE_CHARS} chars"))
    }

    @Test
    fun `readBounded leaves a small body exactly as sent`() {
        val small = """{"ok":true}"""
        assertTrue(CloudLlm.readBounded(small.toResponseBody()) == small)
        assertTrue(CloudLlm.readBounded(null).isEmpty())
    }

    @Test
    fun `a ten megabyte reply is cut and reported, never buffered whole`() = runBlocking {
        val prefix = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"pad":""""
        val huge = prefix + "\"" + "y".repeat(10_000_000) + "\"}"
        server.enqueue(MockResponse().setResponseCode(200).setBody(huge))

        try {
            llm(attempts = 1).step(listOf(Llm.Message("user", "hi")), emptyList())
            fail("a cut-off completion is not a completion")
        } catch (e: RuntimeException) {
            val message = e.message!!
            assertTrue(message.contains("not a completion object"))
            val read = Regex("""(\d+) chars read""").find(message)?.groupValues?.get(1)?.toInt()
            assertTrue("the read must stop at the ceiling, got: $message", read != null && read < 2_100_000)
        }
    }

    @Test
    fun `a reply just under the ceiling is used unchanged`() = runBlocking {
        val text = "z".repeat(1_500_000)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"$text"},"finish_reason":"stop"}]}""",
            ),
        )
        val response = llm().step(listOf(Llm.Message("user", "hi")), emptyList())
        assertTrue((response as Llm.LlmResponse.Text).content == text)
    }

    @Test
    fun `a reply that is not a completion names the real cause`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>gateway</html>"))
        try {
            llm(attempts = 1).step(listOf(Llm.Message("user", "hi")), emptyList())
            fail("expected a named failure")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("LLM reply was not a completion object"))
            assertTrue(e.message!!.contains("HTTP 200"))
            assertTrue(e.message!!.contains("chars read"))
        }
    }
}
