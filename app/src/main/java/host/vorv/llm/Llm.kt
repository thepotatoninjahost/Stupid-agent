package host.vorv.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import host.vorv.llm.provider.ProviderRegistry
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
 * A failure that came back from Z.ai with an HTTP status, so callers can tell
 * "the servers are busy" (429, 5xx — worth trying another model) from "this
 * key or request is wrong" (401/403/400 — trying another model is pointless).
 * The message keeps the historical "LLM HTTP <code>: ..." shape because
 * [FallbackLlm.isAuthError] and the episode log already read it.
 */
class LlmHttpException(val code: Int, private val bodyText: String) :
    RuntimeException("LLM HTTP $code: $bodyText") {
    val overloaded: Boolean get() = code == 429 || code >= 500

    /**
     * The account cannot use this model at all — no credit, no quota, overdue
     * balance. Retrying is pointless and so is pretending it is a busy server:
     * a free-tier account hitting a paid model lands here, and the answer is
     * "use the free model", not "try again".
     */
    val notEntitled: Boolean
        get() {
            if (code == 402) return true
            val b = bodyText.lowercase()
            return listOf("insufficient", "no quota", "quota exceeded", "arrears", "balance", "billing")
                .any { b.contains(it) }
        }

    /**
     * The provider does not have this model id (retired or renamed). The next id
     * in the chain is a genuine second chance: one stale id used to end the whole
     * chain with "HTTP 404" while a working model sat behind it.
     */
    val unknownModel: Boolean
        get() = code == 404 || (code == 400 && bodyText.lowercase().contains("model"))
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
    val modelName: String = CloudModel.GLM_4_7_FLASH.id,
    client: OkHttpClient? = null,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val backoffMs: Long = 1_500L,
    private val provider: host.vorv.llm.provider.Provider? = null,
) : Llm {
    private val model: String get() = modelName
    override val label = "$model (cloud)"

    // H-5: whatever client a caller injects, a prompt is never re-sent to a
    // redirect target — so the policy is applied to the instance we use.
    private val client: OkHttpClient = (client ?: Companion.client).newBuilder()
        .followRedirects(false)
        .build()

    override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "$model requires an API key — add one in Setup tab." }
            // H-5: a cleartext provider address never gets a prompt, even if
            // it was stored before this check existed.
            host.vorv.llm.provider.ProviderUrl.rejectionReason(baseUrl)?.let {
                throw IllegalArgumentException(it)
            }

            fun buildBody(withTools: Boolean): String = JsonObject().apply {
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
                if (withTools && tools.isNotEmpty()) {
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

            val bodyWithTools = buildBody(true)
            val bodyNoTools = buildBody(false)
            // 0.9.18 — a provider (or a gateway in front of one) that rejects
            // tool schemas with 400 must still be usable: retry once as a plain
            // chat, and remember it for the rest of the task.
            var includeTools = tools.isNotEmpty()

            var lastErr: String? = null
            var lastCode = 0
            val url = ProviderRegistry.chatCompletionsUrl(baseUrl)
            for (attempt in 0 until maxAttempts) {
                try {
                    val payload = if (includeTools) bodyWithTools else bodyNoTools
                    val req = host.vorv.llm.provider.BrainHttp
                        .applyAuth(Request.Builder().url(url), provider, apiKey)
                        .addHeader("Accept-Language", "en-US,en")
                        .addHeader("Content-Type", "application/json")
                        .post(payload.toRequestBody("application/json".toMediaType()))
                        .build()

                    client.newCall(req).execute().use { res ->
                        // M-1 (code review, 2026-11): read a ceiling, not the
                        // whole stream — a huge or endless body used to be
                        // buffered in full (OOM), and an OutOfMemoryError is
                        // not an Exception, so it escaped every retry and the
                        // task died with no message.
                        val responseBody = readBounded(res.body)
                        // Redacted truth for Brain health: the address, the
                        // model, THAT a key was sent (never which), the code
                        // and the body head. A 401 then names its own cause
                        // instead of being guessed at in a screenshot.
                        BrainState.recordExchange(
                            BrainFacts.Exchange(
                                url = url,
                                model = model,
                                authorizationSent = apiKey.isNotBlank(),
                                code = res.code,
                                body = responseBody.take(600),
                                toolsSent = includeTools,
                            ),
                        )
                        if (res.code == 429 || res.code >= 500) {
                            lastCode = res.code
                            lastErr = "HTTP ${res.code}: ${responseBody.take(200)}"
                            if (attempt < maxAttempts - 1) {
                                // Non-blocking backoff (was Thread.sleep — audit M-2).
                                delay(backoffMs * (1L shl attempt) + (Math.random() * 500).toLong())
                            }
                            return@use
                        }
                        if (res.code in 300..399) {
                            // H-5: with redirects off, a 3xx is where the old
                            // client would have followed to another host and
                            // re-sent the prompt (and the key) there.
                            val location = res.header("Location") ?: "(no Location header)"
                            lastErr = "provider redirected the request to $location — redirects are refused"
                            throw LlmHttpException(res.code, lastErr)
                        }
                        if (!res.isSuccessful) {
                            val b = responseBody.lowercase()
                            if (res.code == 400 && includeTools &&
                                (b.contains("tool") || b.contains("function"))
                            ) {
                                // The tools schema itself was refused. That is not
                                // a broken key and not a busy provider — it is an
                                // incompatibility one retry can settle.
                                includeTools = false
                                lastCode = res.code
                                lastErr = "provider refused tool calling (HTTP 400); asking without tools"
                                return@use
                            }
                            // L-5 (code review, 2026-11): every other 4xx
                            // (400 bad request, 401/403 bad key, 404 wrong
                            // model, …) is DETERMINISTIC — the same request
                            // will fail identically. The old generic catch
                            // retried them for up to 4 attempts / ~10 s of
                            // dead air and burned quota for nothing. These
                            // now fail fast via [NonRetryableLlmException].
                            throw LlmHttpException(res.code, responseBody.take(300))
                        }
                        val json = try {
                            JsonParser.parseString(responseBody).asJsonObject
                        } catch (e: Exception) {
                            // Name the real cause: a bare "Expected BEGIN_OBJECT"
                            // told nobody which provider or how big it was.
                            throw RuntimeException(
                                "LLM reply was not a completion object (HTTP ${res.code}, " +
                                    "${responseBody.length} chars read): ${e.message}",
                                e,
                            )
                        }

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
                } catch (e: LlmHttpException) {
                    // L-5: deterministic client-side failures fail FAST —
                    // same request, same answer, no retry theater.
                    throw e
                } catch (e: Exception) {
                    lastErr = e.message
                    if (attempt < maxAttempts - 1) {
                        delay(backoffMs * (1L shl attempt))
                    }
                }
            }
            throw if (lastCode != 0) {
                LlmHttpException(lastCode, "after $maxAttempts attempts: $lastErr")
            } else {
                RuntimeException("LLM call failed after $maxAttempts attempts: $lastErr")
            }
        }

    companion object {
        private const val MAX_ATTEMPTS = 4

        /**
         * M-1: a completion body is read up to this many bytes, then the read
         * stops. UTF-8 yields at most one char per byte, so the char cap
         * below can only cut what the byte cap already bounded.
         */
        internal const val MAX_RESPONSE_BYTES = 2_000_000
        internal const val MAX_RESPONSE_CHARS = 1_000_000

        /** A probe only keeps 600 chars, so this ceiling is generous. */
        internal const val PROBE_BODY_BYTES = 64_000

        /**
         * Reads at most [maxBytes] of [body] and closes nothing (the caller's
         * `use` closes). A body that produced more is handed on with the
         * honest truncation marker instead of looking complete.
         */
        internal fun readBounded(
            body: okhttp3.ResponseBody?,
            maxBytes: Int = MAX_RESPONSE_BYTES,
        ): String {
            if (body == null) return ""
            val buffer = okio.Buffer()
            val source = body.source()
            var overflow = false
            while (buffer.size < maxBytes) {
                val read = source.read(buffer, (maxBytes - buffer.size).toLong())
                if (read == -1L) break
            }
            if (buffer.size >= maxBytes) {
                // One more byte tells us whether the ceiling cut anything.
                overflow = source.read(okio.Buffer(), 1L) != -1L
            }
            val text = buffer.readUtf8()
            return if (overflow) host.vorv.tools.capWithMarker(text, MAX_RESPONSE_CHARS) else text
        }

        /**
         * L-5 (code review, 2026-11): marks a DETERMINISTIC failure (HTTP
         * 4xx other than 429) that retrying cannot fix — [step] rethrows it
         * immediately instead of burning the retry budget on it.
         */
        /** Shared client — one connection pool for every CloudLlm instance. */
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dns(NetworkGuard.PublicDns)
            // H-5: a 307 would re-send the prompt body (and the key) to
            // whatever host the redirect names. A redirect is an answer we
            // refuse, not one we follow.
            .followRedirects(false)
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
            probeAt(apiKey, "https://api.z.ai/api/paas/v4", model)

        /** Same one-call proof, against any address that speaks this format. */
        suspend fun probeAt(
            apiKey: String,
            baseUrl: String,
            model: String,
            provider: host.vorv.llm.provider.Provider? = null,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("No API key saved yet."))
                host.vorv.llm.provider.ProviderUrl.rejectionReason(baseUrl)?.let {
                    return@withContext Result.failure(IllegalArgumentException(it))
                }
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
                    val url = host.vorv.llm.provider.ProviderRegistry.chatCompletionsUrl(baseUrl)
                    val req = host.vorv.llm.provider.BrainHttp
                        .applyAuth(Request.Builder().url(url), provider, apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute().use { res ->
                        // M-1: a probe reads 600 chars; never buffer more.
                        val text = readBounded(res.body, PROBE_BODY_BYTES)
                        val exchange = BrainFacts.Exchange(
                            url = url,
                            model = model,
                            authorizationSent = true,
                            code = res.code,
                            body = text.take(600),
                        )
                        BrainState.recordProbe(exchange)
                        if (!res.isSuccessful) {
                            return@withContext Result.failure(
                                RuntimeException("HTTP ${res.code}: ${text.take(200)}"),
                            )
                        }
                        val json = JsonParser.parseString(text).asJsonObject
                        val choices = json.getAsJsonArray("choices")
                        if (choices == null || choices.size() == 0) {
                            return@withContext Result.failure(
                                RuntimeException("No choices in response: ${text.take(200)}"),
                            )
                        }
                        val message = choices[0].asJsonObject.getAsJsonObject("message")
                        val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                            .ifBlank {
                                message.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                            }
                        if (content.isBlank()) {
                            // 0.9.18: an HTTP 200 with an empty answer is NOT
                            // proof the key works — it is the signature of a
                            // retired or renamed model id (screenshot 2: the
                            // probe said "key works" for exactly this reply).
                            return@withContext Result.failure(
                                RuntimeException(
                                    "HTTP 200, but model \"$model\" returned no text. " +
                                        "The key is not the problem — the model id is probably retired. " +
                                        "Pick one the provider still lists.",
                                ),
                            )
                        }
                        Result.success(content)
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
            // The chosen model first, then the others this account can reach:
            // one overloaded model must not end the task.
            CloudChainLlm(apiKey, listOf(cloudModel) + CloudModel.entries.filter { it != cloudModel }.sortedByDescending { it.free })
        }
        else -> {
            requireNotNull(local) { "Local mode selected but the model isn't downloaded yet. Open Setup → tap a model → Download (one time, ~1.8–4.4 GB depending on choice)." }
            local
        }
    }
}
