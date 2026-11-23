package host.vorv.llm

import com.google.gson.JsonObject
import host.vorv.data.entity.ToolCall
import host.vorv.kernel.CognitiveKernel
import host.vorv.kernel.KernelEventSink
import host.vorv.tools.Tool
import host.vorv.tools.ToolContext
import host.vorv.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The brain, run for real: the same [CloudLlm] and the same
 * [CognitiveKernel] the app uses, against the provider in Setup. No fake
 * engine, no mocked HTTP — the point of this test is that a real model, given
 * the real tool schemas, asks for a tool and then answers with what came back.
 *
 * Skipped when no key is present, so CI stays green without one. Run it with
 * `OPENROUTER_API_KEY=… ./gradlew :app:testDebugUnitTest --tests 'host.vorv.llm.LiveBrainTest'`.
 */
@RunWith(RobolectricTestRunner::class)
class LiveBrainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Normalized through the same guard the app uses: an environment value
    // holding several keys (a paste artefact) is not one key, and this test
    // skips instead of asserting anything about it.
    private val key: String = runCatching {
        host.vorv.llm.provider.ApiKey.normalize(System.getenv("OPENROUTER_API_KEY").orEmpty())
    }.getOrDefault("")
    private val base = System.getenv("VORV_LIVE_BASE").orEmpty().ifBlank { "https://openrouter.ai/api/v1" }
    private val model = System.getenv("VORV_LIVE_MODEL").orEmpty().ifBlank { "qwen/qwen3-coder-next" }

    private fun engine() = CloudLlm(apiKey = key, baseUrl = base, modelName = model)

    /** A real tool: it computes, so an answer can only carry the right number
     *  if the round-trip through the tool actually happened. */
    private class MultiplyTool : Tool {
        override val name = "multiply"
        override val description = "Multiply two integers and return the product."
        override fun schema(): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("a", JsonObject().apply { addProperty("type", "integer") })
                add("b", JsonObject().apply { addProperty("type", "integer") })
            })
            add("required", com.google.gson.JsonArray().apply { add("a"); add("b") })
        }
        override val timeoutMs = 30_000L
        override val maxCallsPerTask = 4

        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
            val a = input.get("a").asLong
            val b = input.get("b").asLong
            return ToolResult(true, "${a * b}", 0L)
        }
    }

    private class QuietSink : KernelEventSink {
        val episodes = mutableListOf<Pair<String, String>>()
        override suspend fun logEpisode(kind: String, summary: String, detailsJson: String) {
            episodes.add(kind to summary)
        }
        override suspend fun insertToolCall(call: ToolCall) = Unit
        override suspend fun recordSkillResult(toolName: String, ok: Boolean) = Unit
        override suspend fun setTaskIterations(taskId: String, iterations: Int) = Unit
    }

    @Test
    fun `a real model answers in words`() = runBlocking {
        assumeTrue("no single OPENROUTER_API_KEY in the environment", key.isNotBlank())
        val r = engine().step(
            listOf(Llm.Message("user", "Reply with exactly the word: pong")),
            emptyList(),
        )
        val text = (r as? Llm.LlmResponse.Text)?.content.orEmpty()
        println("LIVE text=$text")
        assertTrue("the reply carried no text: $r", text.isNotBlank())
    }

    /**
     * The whole loop, not just the HTTP call: the model is given one real tool,
     * has to ask for it, the kernel has to run it, and the final answer has to
     * carry the number the tool computed.
     */
    @Test
    fun `the brain calls a real tool and answers with its result`() = runBlocking {
        assumeTrue("no single OPENROUTER_API_KEY in the environment", key.isNotBlank())
        val sink = QuietSink()
        val kernel = CognitiveKernel(sink, listOf(MultiplyTool()), tmp.newFolder("out"))
        val result = kernel.run(
            "live-1",
            "Use the multiply tool to compute 17 * 23. Answer with the product only.",
            engine(),
            null,
        )
        println("LIVE answer=${result.answer.take(300)} toolCalls=${result.toolCalls} ok=${result.ok}")
        assertTrue("the task failed: ${result.answer}", result.ok)
        assertEquals("the tool must actually be called", 1, result.toolCalls)
        assertTrue("the answer must carry the product: ${result.answer}", result.answer.contains("391"))
    }
}
