package host.vorv.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * LLM abstraction. Two backends:
 *   - Local: MLC LLM Android, Llama 3.2 1B. (Stub for now — real impl in Phase 4.)
 *   - Cloud: Z.ai GLM-4.6 with native function calling.
 *
 * Both expose the same interface so the cognitive kernel doesn't care which is active.
 */
sealed interface Llm {
    /** Returns either a final answer or a tool call request. */
    suspend fun step(messages: List<Message>, tools: List<ToolSpec>): LlmResponse

    val label: String
    val isLocal: Boolean

    data class Message(
        val role: String,        // "system" | "user" | "assistant" | "tool"
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolCallId: String? = null,
    )

    data class ToolCall(val id: String, val name: String, val arguments: String)

    data class ToolSpec(
        val name: String,
        val description: String,
        val parameters: JsonObject,
    )

    /** Either finish with text, or request a tool call. */
    sealed class LlmResponse {
        data class Text(val content: String, val done: Boolean) : LlmResponse()
        data class ToolUse(val toolCalls: List<ToolCall>, val reasoning: String?) : LlmResponse()
    }
}

/**
 * Cloud LLM via Z.ai GLM-4.6. Native OpenAI-style function calling.
 * If apiKey is blank, refuses to call.
 */
class CloudLlm(
    private val apiKey: String,
    private val baseUrl: String = "https://api.z.ai/api/paas/v4",
    private val model: String = "glm-4.6",
) : Llm {
    override val label = "GLM-4.6 (cloud)"
    override val isLocal = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Cloud LLM requires an API key" }

            val body = JsonObject().apply {
                addProperty("model", model)
                addProperty("temperature", 0.4)
                addProperty("max_tokens", 2048)
                add("messages", com.google.gson.JsonArray().apply {
                    messages.forEach { m ->
                        add(JsonObject().apply {
                            addProperty("role", m.role)
                            addProperty("content", m.content)
                            if (m.toolCalls.isNotEmpty()) {
                                add("tool_calls", com.google.gson.JsonArray().apply {
                                    m.toolCalls.forEach { tc ->
                                        add(JsonObject().apply {
                                            addProperty("id", tc.id)
                                            addProperty("type", "function")
                                            add("function", JsonObject().apply {
                                                addProperty("name", tc.name)
                                                addProperty("arguments", tc.arguments)
                                            })
                                        })
                                    }
                                })
                            }
                            m.toolCallId?.let { addProperty("tool_call_id", it) }
                        })
                    }
                })
                if (tools.isNotEmpty()) {
                    add("tools", com.google.gson.JsonArray().apply {
                        tools.forEach { spec ->
                            add(JsonObject().apply {
                                addProperty("type", "function")
                                add("function", JsonObject().apply {
                                    addProperty("name", spec.name)
                                    addProperty("description", spec.description)
                                    add("parameters", spec.parameters)
                                })
                            })
                        }
                    })
                }
            }.toString()

            var lastErr: String? = null
            for (attempt in 0..3) {
                try {
                    val req = Request.Builder()
                        .url("$baseUrl/chat/completions")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Accept-Language", "en-US,en")
                        .addHeader("Content-Type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()

                    client.newCall(req).execute().use { res ->
                        val responseBody = res.body?.string().orEmpty()
                        if (res.code == 429 || res.code >= 500) {
                            lastErr = "HTTP ${res.code}: ${responseBody.take(200)}"
                            Thread.sleep(1500L * (1L shl attempt) + (Math.random() * 500).toLong())
                            return@use
                        }
                        if (!res.isSuccessful) {
                            throw RuntimeException("LLM HTTP ${res.code}: ${responseBody.take(300)}")
                        }
                        val json = JsonParser.parseString(responseBody).asJsonObject
                        val choice = json.getAsJsonArray("choices")[0].asJsonObject
                        val msg = choice.getAsJsonObject("message")
                        val finish = choice.get("finish_reason")?.asString ?: "stop"
                        val content = msg.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val toolCallsArr = msg.getAsJsonArray("tool_calls")

                        return@withContext if (toolCallsArr != null && toolCallsArr.size() > 0) {
                            val calls = toolCallsArr.map { tcEl ->
                                val tc = tcEl.asJsonObject
                                val fn = tc.getAsJsonObject("function")
                                Llm.ToolCall(
                                    id = tc.get("id")?.asString ?: java.util.UUID.randomUUID().toString(),
                                    name = fn.get("name").asString,
                                    arguments = fn.get("arguments")?.asString ?: "{}",
                                )
                            }
                            Llm.LlmResponse.ToolUse(calls, reasoning = content.takeIf { it.isNotBlank() })
                        } else {
                            Llm.LlmResponse.Text(content, done = finish == "stop")
                        }
                    }
                } catch (e: Exception) {
                    lastErr = e.message
                    Thread.sleep(1500L * (1L shl attempt))
                }
            }
            throw RuntimeException("LLM call failed after retries: $lastErr")
        }
}

/**
 * Stub local LLM — returns "not yet bundled" message.
 * Will be replaced with MLC LLM Android integration in Phase 4.
 */
class LocalLlmStub : Llm {
    override val label = "Local (stub — MLC pending)"
    override val isLocal = true

    override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse {
        // If user is asking a question that doesn't need a tool, we can respond minimally.
        val lastUser = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        return Llm.LlmResponse.Text(
            content = "Local LLM not yet bundled. Question was: \"$lastUser\".\n\n" +
                "MLC LLM Android integration is pending — for now, enable cloud mode in Settings to use GLM-4.6.",
            done = true,
        )
    }
}

/**
 * Picks the right LLM based on the user's preference and key availability.
 */
class LlmRouter(
    private val local: Llm = LocalLlmStub(),
    private val cloudFactory: (String) -> Llm = { key -> CloudLlm(key) },
) {
    fun pick(mode: String, apiKey: String): Llm = when {
        mode == "cloud" && apiKey.isNotBlank() -> cloudFactory(apiKey)
        else -> local
    }
}
