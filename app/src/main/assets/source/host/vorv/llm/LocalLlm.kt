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
    private val appContext: Context,
    private val modelChoice: ModelChoice,
    private val displayLabel: String = "Qwen2.5-Coder-7B-Instruct (NPU, offline)",
) : Llm {

    @Volatile private var cpuFallback = false

    /** Set when a trained generation was the first choice and would not load. */
    @Volatile private var championFailure: String? = null

    /** Which of the owner's own generations is answering, if any (0 = none). */
    @Volatile private var servingGeneration: Int = 0

    /**
     * Says what is actually answering. Never claims NPU after a fallback, and
     * never implies the owner's trained model is answering when it is not.
     */
    override val label: String
        get() {
            val silicon = if (cpuFallback) " (CPU fallback)" else ""
            val failed = championFailure
            return when {
                failed != null -> "$displayLabel$silicon — trained generation did not load ($failed)"
                servingGeneration > 0 -> "your trained model, generation $servingGeneration$silicon"
                else -> "$displayLabel$silicon"
            }
        }

    /** The trained generation now answering, or 0. Read by the header and the Brain screen. */
    fun trainedGeneration(): Int = servingGeneration

    /**
     * Honest prompt budget (audit M-2): nCtx is 4096 and GenerationConfig
     * reserves up to 2048 output tokens plus chat-template overhead — so the
     * kernel must keep the PROMPT (messages) under ~1800 tokens or the
     * engine truncates/fails opaquely. The kernel compacts history to fit.
     */
    override val contextTokenBudget: Int get() = CONTEXT_BUDGET

    /** The real ceiling: GenerationConfig.maxTokens below. */
    override val maxOutputTokens: Int get() = MAX_OUTPUT_TOKENS

    /** Which ModelChoice this engine instance wraps (used by ModelRepository). */
    val modelId: String get() = modelChoice.id

    companion object {
        /** Context the engine allocates. */
        const val N_CTX = 4096
        /** Output reserve: file_write payloads need 2048 (audit M-1). */
        const val MAX_OUTPUT_TOKENS = 2048
        /** Chat template + BOS/EOS overhead measured on a 2-message prompt. */
        const val TEMPLATE_MARGIN = 248
        /** Prompt tokens the kernel may use: the rest of the window. */
        const val CONTEXT_BUDGET = N_CTX - MAX_OUTPUT_TOKENS - TEMPLATE_MARGIN
    }

    @Volatile private var llmWrapper: LlmWrapper? = null

    var onTelemetry: ((tokensPerSecond: Double, generateMs: Double) -> Unit)? = null

    /**
     * Build (or reuse) the LlmWrapper. Idempotent.
     * Caller must guarantee the model has been pulled via ModelManager.pull().
     */
    private suspend fun ensureLoaded(): LlmWrapper {
        llmWrapper?.let { return it }
        ModelManager.ensureInit()

        val paths = ModelManager.getPaths(modelChoice)
            ?: error("Model ${modelChoice.hfRepo} not pulled yet - call ModelManager.pull() first")

        // A GPU generation that won on this phone's own tests is tried first.
        // If it cannot load, the failure is said out loud and the base model
        // answers — a trained model that will not start is never a silent
        // downgrade and never an app that will not talk.
        val trained = trainedChampion()
        if (trained != null) {
            val built = buildEngine(trained.file, trained.tokenizer)
            if (built.isSuccess) {
                servingGeneration = trained.generation
                championFailure = null
                llmWrapper = built.getOrThrow()
                Log.i("Vorv/LocalLlm", "serving trained generation ${trained.generation} from ${trained.file}")
                return built.getOrThrow()
            }
            championFailure = built.exceptionOrNull()?.message ?: "unknown reason"
            Log.w(
                "Vorv/LocalLlm",
                "trained generation ${trained.generation} did not load (${championFailure}); using ${modelChoice.hfRepo}",
                built.exceptionOrNull(),
            )
            servingGeneration = 0
        }

        // Step 4: the Hexagon path can fail on a firmware or memory hiccup.
        // Falling back to CPU is correct; falling back SILENTLY is not, so the
        // label changes and the reason is logged (inside build()).
        val wrapper = buildEngine(paths.model_path, paths.tokenizer_path).getOrElse { failure ->
            throw IllegalStateException(
                "The on-device model could not start (${failure.message}).",
                failure,
            )
        }
        llmWrapper = wrapper
        return wrapper
    }

    /** The champion GPU generation, if one is recorded and its file still verifies. */
    private fun trainedChampion(): Trained? {
        val record = try {
            val root = host.vorv.learn.BrainPaths.dir(appContext)
            host.vorv.learn.GenerationStore(root).servingGpuModel()
                ?.let { it to java.io.File(root, it.artifactPath) }
        } catch (e: Exception) {
            Log.w("Vorv/LocalLlm", "could not read the generation index: ${e.message}", e)
            null
        } ?: return null
        if (!record.second.isFile) return null
        return Trained(record.first.id, record.second.absolutePath, tokenizer = null)
    }

    private data class Trained(val generation: Int, val file: String, val tokenizer: String?)

    /** One build attempt with an explicit model file, NPU then CPU. */
    private suspend fun buildEngine(modelPath: String, tokenizerPath: String?): Result<LlmWrapper> {
        fun input(unit: String?) = LlmCreateInput(
            model_path = modelPath,
            tokenizer_path = tokenizerPath ?: "",
            config = ModelConfig(nCtx = N_CTX, nThreads = 4, nGpuLayers = 0),
            runtime_id = "llama_cpp",
            compute_unit = unit,
        )
        val npu = LlmWrapper.builder().llmCreateInput(input(ComputeUnitValue.NPU.value)).build()
        if (npu.isSuccess) return npu
        val npuError = npu.exceptionOrNull()
        Log.w("Vorv/LocalLlm", "NPU build failed (${npuError?.message}); retrying on CPU", npuError)
        val cpu = LlmWrapper.builder().llmCreateInput(input(ComputeUnitValue.CPU.value)).build()
        return if (cpu.isSuccess) {
            cpuFallback = true
            cpu
        } else {
            cpuFallback = false
            Result.failure(
                IllegalStateException(
                    "NPU: ${npuError?.message}; CPU: ${cpu.exceptionOrNull()?.message}",
                ),
            )
        }
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
            // MAX_OUTPUT_TOKENS, not 768: long file_write payloads were still
            // truncated mid-JSON (audit finding M-1) — and raising the number
            // only moves the wall. Callers must chunk: a reply cut at this
            // ceiling comes back from ToolCallParser with truncated = true and
            // the kernel refuses to run it (see LlmResponse.ToolUse).
            maxTokens = MAX_OUTPUT_TOKENS
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

        // H1b: the parser needs the real tool roster so JSON DATA carrying a
        // "name" key on the bare/prose path is treated as an answer, not
        // hijacked into a phantom tool call.
        return ToolCallParser.parse(sb.toString().trim(), tools.map { it.name }.toSet())
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
