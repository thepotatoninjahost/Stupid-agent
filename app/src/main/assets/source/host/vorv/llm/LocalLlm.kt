package host.vorv.llm

import android.content.Context
import android.util.Log
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig

/**
 * Local LLM - real on-device inference via Qualcomm GenieX on the S25's Hexagon NPU.
 *
 * BACKEND: AAR at repo1.maven.org/maven2/com/qualcomm/qti/geniex-android/0.10.0
 * - Bundles libggml-hexagon.so + libQnnHtpV81.so + libggml-htp-v81.so
 *   for the S25 Snapdragon 8 Elite for Galaxy Hexagon NPU (HTP v81).
 * - Also bundles libggml-opencl.so (Adreno GPU) and libggml-cpu.so (CPU)
 *   as automatic fallbacks.
 * - Loads standard GGUF files at runtime via the llama_cpp plugin.
 *
 * MODELS:
 * - Default: Qwen2.5-Coder-7B-Instruct Q4_0, 4,444,121,888 bytes (4.44 GB)
 *   from bartowski/Qwen2.5-Coder-7B-Instruct-GGUF.
 * - Fallback: Qwen2.5-Coder-3B-Instruct Q4_0, 1,828,486,400 bytes (1.83 GB)
 *   from bartowski/Qwen2.5-Coder-3B-Instruct-GGUF.
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

    /**
     * Honest prompt budget (audit M-2): nCtx is 4096 and GenerationConfig
     * reserves up to 2048 output tokens plus chat-template overhead — so the
     * kernel must keep the PROMPT (messages) under ~1800 tokens or the
     * engine truncates/fails opaquely. The kernel compacts history to fit.
     */
    override val contextTokenBudget: Int get() = 1_800

    /** Which ModelChoice this engine instance wraps (used by ModelRepository). */
    val modelId: String get() = modelChoice.id

    @Volatile private var llmWrapper: LlmWrapper? = null

    var onTelemetry: ((tokensPerSecond: Double, generateMs: Double) -> Unit)? = null

    /**
     * Build (or reuse) the LlmWrapper. Idempotent.
     * Caller must guarantee the model has been pulled via ModelManager.pull().
     */
    private suspend fun ensureLoaded(): LlmWrapper {
        llmWrapper?.let { return it }

        val paths = ModelManager.getPaths(modelChoice)
            ?: error("Model ${modelChoice.hfRepo} not pulled yet - call ModelManager.pull() first")

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
            messages = geniexMessages,
            tools = toolsJson,
            enableThinking = false,
            // addGenerationPrompt defaults to true; parallelToolCalls defaults to true
        )
        val template = templateResult.getOrElse {
            error("GenieX applyChatTemplate failed: ${it.message}")
        }

        val generationConfig = GenerationConfig().apply {
            // 2048, not 768: long file_write payloads were getting truncated
            // mid-JSON (audit finding M-1), producing unparseable tool calls.
            maxTokens = 2048
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

        wrapper.generateStreamFlow(
            prompt = template.formattedText,
            config = generationConfig,
        ).collect { result ->
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

        return ToolCallParser.parse(sb.toString().trim())
    }

    private fun buildToolsJson(tools: List<Llm.ToolSpec>): String {
        val arr = com.google.gson.JsonArray()
        tools.forEach { spec ->
            arr.add(com.google.gson.JsonObject().apply {
                addProperty("type", "function")
                add("function", com.google.gson.JsonObject().apply {
                    addProperty("name", spec.name)
                    addProperty("description", spec.description)
                    add("parameters", spec.parameters)
                })
            })
        }
        return arr.toString()
    }

    /**
     * Free native memory. MUST be called when swapping models or closing.
     * Failures are logged, not swallowed (audit finding M-11) — a failed
     * native destroy is the kind of thing you want in logcat when the next
     * LlmWrapper build mysteriously fails.
     */
    fun release() {
        llmWrapper?.let { w ->
            runCatching { w.close() }   // close() internally calls destroy()
                .onFailure {
                    Log.w("Vorv/LocalLlm", "native release() failed: ${it.message}", it)
                }
        }
        llmWrapper = null
    }
}
