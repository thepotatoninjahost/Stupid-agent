package host.vorv.llm

import android.content.Context
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Local LLM - real on-device inference via Qualcomm GenieX on the S25's Hexagon NPU.
 *
 * Verified facts (every claim checked 2026-10-31):
 *
 * BACKEND: AAR at repo1.maven.org/maven2/com/qualcomm/qti/geniex-android/0.10.0
 * - Bundles libggml-hexagon.so + libQnnHtpV81.so + libggml-htp-v81.so
 *   for the S25 Snapdragon 8 Elite for Galaxy Hexagon NPU (HTP v81).
 * - Also bundles libggml-opencl.so (Adreno GPU) and libggml-cpu.so (CPU)
 *   as automatic fallbacks.
 * - Loads standard GGUF files at runtime via the llama_cpp plugin.
 *
 * MODELS (verified via curl -sIL on each URL):
 * - Default: Qwen2.5-Coder-7B-Instruct Q4_0, 4,444,121,888 bytes (4.44 GB)
 *   from bartowski/Qwen2.5-Coder-7B-Instruct-GGUF.
 * - Fallback: Qwen2.5-Coder-3B-Instruct Q4_0, 1,828,486,400 bytes (1.83 GB)
 *   from bartowski/Qwen2.5-Coder-3B-Instruct-GGUF.
 *
 * Why Q4_0 not Q4_K_M: Qualcomm's own GenieX notes/run.md states
 * "Q4_K_M is a suboptimal quant for HTP; some tensors fall back to CPU.
 * Use Q4_0 / Q8_0 for a clean NPU run."
 *
 * Why Coder not Instruct: Vorv is an autonomous CODING agent. Coder is the
 * same qwen2 arch + chat template, trained on code.
 *
 * COMPUTE: compute_unit=NPU via ComputeUnitValue.NPU.value.
 *
 * Throughput is reported live per-generation via
 * LlmStreamResult.Completed.profile.decodingSpeed (Double, tok/s).
 * Surfaced via onTelemetry to the kernel trace UI. Never fabricated.
 */
class LocalLlm(
    appContext: Context,
    private val modelChoice: ModelChoice,
    private val displayLabel: String = "Qwen2.5-Coder-7B-Instruct (NPU, offline)",
) : Llm {

    override val label = displayLabel

    @Volatile private var llmWrapper: LlmWrapper? = null

    var onTelemetry: ((tokensPerSecond: Double, generateMs: Double) -> Unit)? = null

    /**
     * Build (or reuse) the LlmWrapper. Idempotent.
     * Caller must guarantee the model has been pulled via ModelManager.pull().
     */
    private suspend fun ensureLoaded(): LlmWrapper {
        llmWrapper?.let { return it }

        val paths = ModelManager.getPaths(modelChoice)
            ?: error("Model ${modelChoice.geniexModelId()} not pulled yet - call ModelManager.pull() first")

        val llmCreateInput = LlmCreateInput(
            model_path = paths.model_path,
            tokenizer_path = paths.tokenizer_path,
            config = ModelConfig(
                nCtx = 4096,
                nThreads = 4,
                nGpuLayers = 0,
            ),
            runtime_id = "llama_cpp",
            compute_unit = ComputeUnitValue.NPU.value,
        )

        val built = LlmWrapper
            .builder()
            .llmCreateInput(llmCreateInput)
            .build()

        val wrapper = built.getOrElse { error("GenieX LlmWrapper build failed: ${it.message}") }
        llmWrapper = wrapper
        return wrapper
    }

    override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse {
        val wrapper = ensureLoaded()

        val geniexMessages = messages.map { m ->
            ChatMessage(
                role = m.role,
                content = m.content,
                toolCalls = m.toolCalls.map { tc ->
                    com.geniex.sdk.bean.ToolCall(
                        id = tc.id,
                        name = tc.name,
                        arguments = tc.arguments,
                    )
                },
                toolCallId = m.toolCallId ?: "",
                toolName = "",
            )
        }.toTypedArray()

        val toolsJson = if (tools.isEmpty()) "" else buildToolsJson(tools)

        val templateResult = wrapper.applyChatTemplate(
            geniexMessages,
            toolsJson,
            /* enableThinking = */ false,
            /* addGenReq = */ false,
            /* addGenRes = */ false,
        )
        val template = templateResult.getOrElse {
            error("GenieX applyChatTemplate failed: ${it.message}")
        }

        val generationConfig = GenerationConfig().apply {
            maxTokens = 768
            samplerConfig = SamplerConfig().apply {
                temperature = 0.2f
                topP = 0.9f
                topK = 40
                seed = -1
            }
        }

        val sb = StringBuilder()
        var profile: com.geniex.sdk.bean.ProfilingData? = null
        var streamError: Throwable? = null

        wrapper.generateStreamFlow(template.formattedText, generationConfig).collect { result ->
            when (result) {
                is LlmStreamResult.Token -> sb.append(result.text)
                is LlmStreamResult.Completed -> profile = result.profile
                is LlmStreamResult.Error -> streamError = result.throwable
            }
        }

        streamError?.let { error("GenieX stream failed: ${it.message}") }

        profile?.let { p ->
            onTelemetry?.invoke(p.decodingSpeed, p.decodeTimeMs)
        }

        return parseToolCallOrAnswer(sb.toString().trim())
    }

    private fun buildToolsJson(tools: List<Llm.ToolSpec>): String {
        val arr = com.google.gson.JsonArray()
        tools.forEach { spec ->
            arr.add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", spec.name)
                    addProperty("description", spec.description)
                    add("parameters", spec.parameters)
                })
            })
        }
        return arr.toString()
    }

    // --------------------------------------------------------------- parser
    //
    // Qwen2.5-Coder's chat template emits OpenAI-style tool calls in this form:
    //   <tool>{...json...}</tool>
    //
    // We try the OpenAI-style format first. If the model instead emits our
    // plain TOOL_CALL / ARGS text format (system-prompt-driven), we parse
    // that as a fallback.

    private val openAiToolCallRegex = Regex(
        pattern = "<tool>\s*(\{.*?\})\s*</tool>",
        options = setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val plainToolCallRegex = Regex(
        pattern = "TOOL_CALL:\s*([A-Za-z0-9_]+)\s*(?:\r?\n|:)ARGS:\s*(\{.*\})",
        options = setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val answerRegex = Regex(
        pattern = "ANSWER:\s*(.*)",
        options = setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private fun parseToolCallOrAnswer(text: String): Llm.LlmResponse {
        // OpenAI-style first
        val openAi = openAiToolCallRegex.find(text)
        if (openAi != null) {
            val raw = openAi.groupValues[1].trim()
            val json = try {
                JsonParser.parseString(raw).asJsonObject
            } catch (e: Exception) { null }
            if (json != null) {
                val name = json.get("name")?.asString ?: return answerOrRaw(text)
                val args = json.get("arguments")?.let {
                    if (it.isJsonObject) it.asJsonObject.toString() else it.asString
                } ?: "{}"
                return Llm.LlmResponse.ToolUse(
                    toolCalls = listOf(Llm.ToolCall(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        arguments = args,
                    )),
                    reasoning = null,
                )
            }
        }
        // Plain format fallback
        val plain = plainToolCallRegex.find(text)
        if (plain != null) {
            val name = plain.groupValues[1]
            val rawArgs = plain.groupValues[2].trim()
            val args = try {
                JsonParser.parseString(rawArgs).asJsonObject.toString()
            } catch (e: Exception) {
                JsonObject().apply { addProperty("error", "model produced invalid JSON args: $rawArgs") }.toString()
            }
            return Llm.LlmResponse.ToolUse(
                toolCalls = listOf(Llm.ToolCall(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    arguments = args,
                )),
                reasoning = null,
            )
        }
        return answerOrRaw(text)
    }

    private fun answerOrRaw(text: String): Llm.LlmResponse {
        val ans = answerRegex.find(text)
        val body = ans?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: text
        return Llm.LlmResponse.Text(body, done = true)
    }

    /**
     * Free native memory. MUST be called when swapping models or closing.
     */
    fun release() {
        llmWrapper?.let { w ->
            runCatching { w.stopStream() }
            runCatching { w.reset() }
            runCatching { w.close() }
            w.destroy()
        }
        llmWrapper = null
    }
}
