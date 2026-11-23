package host.vorv.llm

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Field 2026-11-23: "the cloud model and local model say the same thing" — both
 * showed `<tool_call><function=crash_logs>…` in chat under "[uncited] — no tool
 * evidence". A gateway can hand the model's own template markup through as
 * content instead of parsed `tool_calls`; that text is a call, not an answer.
 */
class CloudLlmLeakedCallTest {

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

    private fun llm() = CloudLlm(
        apiKey = "k",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        modelName = "qwen/qwen3-coder",
        client = OkHttpClient(),
        maxAttempts = 1,
        backoffMs = 0L,
    )

    private fun reply(content: String): String =
        """{"choices":[{"message":{"role":"assistant","content":${
            com.google.gson.JsonPrimitive(content)
        }},"finish_reason":"stop"}]}"""

    @Test
    fun `a cloud reply carrying template markup is a tool call, not chat text`() = runBlocking {
        val content = "<tool_call>\n<function=file_read>\n<parameter=path>\nnotes.txt\n" +
            "</parameter>\n</function>\n</tool_call>"
        server.enqueue(MockResponse().setResponseCode(200).setBody(reply(content)))

        val res = llm().step(
            listOf(Llm.Message("user", "read my notes")),
            listOf(Llm.ToolSpec("file_read", "Read a file", com.google.gson.JsonObject())),
        )
        val call = (res as? Llm.LlmResponse.ToolUse)?.toolCalls?.firstOrNull()
        assertEquals("file_read", call?.name)
        assertEquals("""{"path":"notes.txt"}""", call?.arguments)
    }

    @Test
    fun `a plain cloud answer is still an answer`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(reply("Nothing crashed.")))

        val res = llm().step(listOf(Llm.Message("user", "hi")), emptyList())
        assertTrue(res is Llm.LlmResponse.Text)
        assertEquals("Nothing crashed.", (res as Llm.LlmResponse.Text).content)
    }
}
