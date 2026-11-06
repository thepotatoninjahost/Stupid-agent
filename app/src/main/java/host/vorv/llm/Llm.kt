package host.vorv.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import host.vorv.tools.NetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Cloud model catalog for Z.ai. The free tier (GLM-4.7-Flash) is the
 * DEFAULT so a fresh install with a key costs nothing to run; the paid
 * models are opt-in, selected explicitly in Setup (v0.7.0).
 *
 * Prices shown in [shortDesc] are the published per-million-token rates at
 * the time of the v0.7.0 build; the billing page at z.ai is the source of
 * truth if they ever change.
 */
enum class CloudModel(
    val id: String,
    val displayName: String,
    val shortDesc: String,
    val free: Boolean,
) {
    GLM_4_7_FLASH(
        "glm-4.7-flash",
        "GLM-4.7-Flash",
        "FREE tier — $0 per token, forever. Fast, 128K context. The default: a fresh key runs at no cost.",
        true,
    ),
    GLM_5_3_FLASH(
        "glm-5.3-flash",
        "GLM-5.3-Flash",
        "Current fast tier, paid per token (roughly a tenth of flagship rates).",
        false,
    ),
    GLM_5_3(
        "glm-5.3",
        "GLM-5.3",
        "Flagship — strongest reasoning, paid per token (published rate: $1.40/M in, $4.40/M out).",
        false,
    ),
    GLM_4_6(
        "glm-4.6",
        "GLM-4.6",
        "Vorv's original cloud engine, paid per token. Kept for compatibility.",
        false,
    );

    companion object {
        fun fromId(id: String?): CloudModel = entries.firstOrNull { it.id == id } ?: GLM_4_7_FLASH
    }
}

/**
 * LLM contract. Vorv uses a Z.ai GLM model (selected in Setup, free tier by
 * default) with native function calling in cloud mode, and Qwen2.5-Coder via
 * Qualcomm GenieX on the Hexagon NPU in local mode. Both implement this same
 * interface so the cognitive kernel does not change.
 */
interface Llm {
    /** Returns either a final answer or a tool call request. */
    suspend fun step(messages: List<Message>, tools: List<ToolSpec>): LlmResponse

    val label: String

    /**
     * Honest prompt budget (audit M-2): the number of PROMPT tokens the
     * engine can realistically accept. nCtx is 4096 for the local engine
     * and GenerationConfig reserves up to 2048 output tokens plus chat-
     * template overhead — so the kernel must keep the conversation under
     * ~1800 tokens for local mode or the engine truncates/fails opaquely.
     * The kernel compacts history to fit this budget. Cloud default is
     * generous and rarely triggers compaction.
     */
    val contextTokenBudget: Int get() = 100_000

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
 * Cloud LLM via Z.ai. Native OpenAI-style function calling.
 * Throws if apiKey is blank — caller must check first.
 *
 * v0.7.0: the model is a first-class choice ([CloudModel]), defaulting to the
 * FREE glm-4.7-flash tier — previously glm-4.6 was hardcoded and the user
 * had no way to change it or avoid paying.
 *
 * v0.6.0 hardening (audit findings M-2, M-3, M-4):
 *  - retry backoff uses non-blocking delay(), not Thread.sleep
 *  - total retry budget is bounded and reported honestly
 *  - defensive handling of an empty "choices" array
 *  - one shared OkHttpClient (connection pool reuse) with private-network
 *    egress blocked via NetworkGuard.PublicDns
 */
class CloudLlm(
    private val apiKey: String,
    private val baseUrl: String = "https://api.z.ai/api/paas/v4",
    private val model: String = CloudModel.GLM_4_7_FLASH.id,
    client: OkHttpClient? = null,
) : Llm {
    override val label = "$model (cloud)"

    private val client: OkHttpClient = client ?: Companion.client

    override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "$model requires an API key — add one in Setup tab." }

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
            for (attempt in 0 until MAX_ATTEMPTS) {
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
                            if (attempt < MAX_ATTEMPTS - 1) {
                                // Non-blocking backoff (was Thread.sleep — audit M-2).
                                delay(1500L * (1L shl attempt) + (Math.random() * 500).toLong())
                            }
                            return@use
                        }
                        if (!res.isSuccessful) {
                            throw RuntimeException("LLM HTTP ${res.code}: ${responseBody.take(300)}")
                        }
                        val json = JsonParser.parseString(responseBody).asJsonObject

                        // Defensive: some error shapes return an empty choices array (audit M-4).
                        val choices = json.getAsJsonArray("choices")
                        if (choices == null || choices.size() == 0) {
                            throw RuntimeException("LLM returned no choices: ${responseBody.take(300)}")
                        }
                        val choice = choices[0].asJsonObject
                        val msg = choice.getAsJsonObject("message")
                        val finish = choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString ?: "stop"
                        val content = msg.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val toolCallsArr = msg.getAsJsonArray("tool_calls")

                        return@withContext if (toolCallsArr != null && toolCallsArr.size() > 0) {
                            val calls = toolCallsArr.map { tcEl ->
                                val tc = tcEl.asJsonObject
                                val fn = tc.getAsJsonObject("function")
                                Llm.ToolCall(
                                    id = tc.get("id")?.takeIf { !it.isJsonNull }?.asString
                                        ?: java.util.UUID.randomUUID().toString(),
                                    name = fn.get("name").asString,
                                    arguments = fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString ?: "{}",
                                )
                            }
                            Llm.LlmResponse.ToolUse(calls, reasoning = content.takeIf { it.isNotBlank() })
                        } else {
                            Llm.LlmResponse.Text(content, done = finish == "stop")
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // N-L1 (VRA-2026-1103): cancellation is not a retryable
                    // failure — the task was timed out or cancelled. Retrying
                    // it would keep a dead task's worker busy for up to
                    // MAX_ATTEMPTS more calls in the background.
                    throw e
                } catch (e: Exception) {
                    lastErr = e.message
                    if (attempt < MAX_ATTEMPTS - 1) {
                        delay(1500L * (1L shl attempt))
                    }
                }
            }
            throw RuntimeException("LLM call failed after $MAX_ATTEMPTS attempts: $lastErr")
        }

    companion object {
        private const val MAX_ATTEMPTS = 4

        /** Shared client — one connection pool for every CloudLlm instance. */
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dns(NetworkGuard.PublicDns)
            .build()

        /**
         * v0.7.0 — a REAL one-round-trip verification of key + model, fired
         * when a key is saved or a cloud model is picked. Verifiable
         * execution, applied to configuration: the Setup screen shows the
         * model's actual reply (or the actual error string from Z.ai), never
         * a guess. Single attempt, no retries — a probe failing IS the answer.
         *
         * Cost: ~8 output tokens. On the free default model, $0. On the paid
         * flagship, ~4 thousandths of a cent.
         */
        suspend fun probe(apiKey: String, model: String): Result<String> =
            withContext(Dispatchers.IO) {
                if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("No API key saved yet."))
                try {
                    val body = JsonObject().apply {
                        addProperty("model", model)
                        addProperty("temperature", 0f)
                        addProperty("max_tokens", 8)
                        add("messages", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("role", "user")
                                addProperty("content", "Reply with exactly: VORV_KEY_OK")
                            })
                        })
                    }.toString()
                    val req = Request.Builder()
                        .url("https://api.z.ai/api/paas/v4/chat/completions")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute().use { res ->
                        val text = res.body?.string().orEmpty()
                        if (!res.isSuccessful) {
                            return@withContext Result.failure(
                                RuntimeException("HTTP ${res.code}: ${text.take(200)}")
                            )
                        }
                        val json = JsonParser.parseString(text).asJsonObject
                        val choices = json.getAsJsonArray("choices")
                        if (choices == null || choices.size() == 0) {
                            return@withContext Result.failure(
                                RuntimeException("No choices in response: ${text.take(200)}")
                            )
                        }
                        val content = choices[0].asJsonObject
                            .getAsJsonObject("message").get("content")
                            ?.takeIf { !it.isJsonNull }?.asString ?: ""
                        Result.success(content.ifBlank { "(empty reply, HTTP 200)" })
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
    }
}

/**
 * Picks the reasoning engine:
 *  - "local"  → llama.cpp on-device via GenieX (requires the model downloaded once, ~1.8–4.4 GB)
 *  - "cloud"  → the selected [CloudModel] on Z.ai (free tier by default; requires a Z.ai API key)
 * If the requested engine isn't available, throws with a REAL, actionable error — never a stub.
 */
object LlmRouter {
    fun pick(mode: String, apiKey: String, local: LocalLlm?, cloudModel: CloudModel = CloudModel.GLM_4_7_FLASH): Llm = when (mode) {
        "cloud" -> {
            require(apiKey.isNotBlank()) { "Cloud mode needs a Z.ai API key — add one in Setup." }
            CloudLlm(apiKey, model = cloudModel.id)
        }
        else -> {
            requireNotNull(local) { "Local mode selected but the model isn't downloaded yet. Open Setup → tap a model → Download (one time, ~1.8–4.4 GB depending on choice)." }
            local
        }
    }
}
