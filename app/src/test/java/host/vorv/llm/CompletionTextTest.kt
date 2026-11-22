package host.vorv.llm

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's key test said "HTTP 200 but the reply carried no text" for a
 * model that had answered. These pin every reply shape a gateway really sends,
 * so that verdict can never again be produced by a shape the parser missed.
 */
class CompletionTextTest {

    private fun message(json: String) =
        JsonParser.parseString("""{"message":$json}""").asJsonObject.get("message")

    @Test
    fun `content sent as parts reads as an answer`() {
        val m = message("""{"role":"assistant","content":[{"type":"text","text":"VORV_KEY_OK"}]}""")
        assertEquals("VORV_KEY_OK", CompletionText.of(m))
        assertEquals("VORV_KEY_OK", CompletionText.content(m))
    }

    @Test
    fun `a reply that arrives in reasoning still counts`() {
        assertEquals("VORV_KEY_OK", CompletionText.of(message("""{"content":"","reasoning":"VORV_KEY_OK"}""")))
        assertEquals("VORV_KEY_OK", CompletionText.of(message("""{"reasoning_content":"VORV_KEY_OK"}""")))
    }

    @Test
    fun `a null content does not crash and keeps reasoning`() {
        assertEquals("thought", CompletionText.of(message("""{"content":null,"reasoning":"thought"}""")))
        assertEquals("", CompletionText.of(message("""{"content":null}""")))
    }

    @Test
    fun `an empty 200 quotes the provider's own error`() {
        val reason = CompletionText.emptyReplyReason(
            """{"error":{"message":"qwen/qwen3-coder is not a valid model ID","code":400},"choices":[]}""",
        )
        assertTrue(reason, reason.contains("not a valid model ID"))
    }

    @Test
    fun `an empty 200 names the finish reason, or the body when it has neither`() {
        val length = CompletionText.emptyReplyReason(
            """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""",
        )
        assertTrue(length, length.contains("finish_reason=length"))
        val plain = CompletionText.emptyReplyReason("""{"choices":[]}""")
        assertTrue(plain, plain.startsWith("body: "))
    }

    @Test
    fun `health and chat read the same reply text`() {
        val facts = BrainFacts.Exchange(
            url = "https://openrouter.ai/api/v1/chat/completions",
            model = "qwen/qwen3-coder",
            authorizationSent = true,
            code = 200,
            body = """{"choices":[{"message":{"content":[{"type":"text","text":"hello from parts"}]}}]}""",
        )
        assertEquals("hello from parts", facts.replyText)
        assertTrue(facts.answered)
    }
}
