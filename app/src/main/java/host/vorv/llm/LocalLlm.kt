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
 * MODEL:
 * - OmniCoder-9B Q4_0, 5,313,356,096 bytes (5.31 GB), from
 *   Tesslate/OmniCoder-9B-GGUF. It is the only on-device model.
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
    private val displayLabel: String = "OmniCoder-9B (NPU, offline)",
) : Llm {

    @Volatile private var cpuFallback = false

    /** Window the engine actually got. A smaller one is said out loud. */
    @Volatile private var loadedCtx = N_CTX

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
            val window = if (loadedCtx < N_CTX) " — $loadedCtx-token window" else ""
            return when {
                failed != null -> "$displayLabel$silicon — trained generation did not load ($failed)"
                servingGeneration > 0 -> "your trained model, generation $servingGeneration$silicon$window"
                else -> "$displayLabel$silicon$window"
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

    /** Which ModelChoice this engine instance wraps (used by ModelRepository). */
    val modelId: String get() = modelChoice.id

    companion object {
        /**
         * Context the engine allocates.
         *
         * 4096 was too small for this agent: the system prompt alone is ~2.7 KB
         * and the tool roster ~2.3 KB, and the chat template renders the tool
         * SCHEMAS on top of that — so "input prompt too long" could fire on a
         * three-word message. 8192 gives the prompt room AND keeps the 2048
         * output reserve.
         */
        const val N_CTX = 8192
        /** Window tried when the NPU cannot allocate [N_CTX]. */
        const val FALLBACK_N_CTX = 4096
        /** Output reserve: file_write payloads need 2048 (audit M-1). */
        const val MAX_OUTPUT_TOKENS = 2048
        /** Chat template + BOS/EOS overhead measured on a 2-message prompt. */
        const val TEMPLATE_MARGIN = 248
        /** Prompt tokens the kernel may use: the rest of the window. */
        const val CONTEXT_BUDGET = N_CTX - MAX_OUTPUT_TOKENS - TEMPLATE_MARGIN
        /** Below this much room left for a reply, the prompt itself is the problem. */
        const val MIN_OUTPUT_TOKENS = 256
        /** The kernel's own heuristic (CognitiveKernel: chars/4 ≈ tokens). */
        const val CHARS_PER_TOKEN = 4.0
        /** Tokens kept clear so the estimate can never be exactly at the edge. */
        const val PROMPT_SAFETY = 64

        /**
         * Output allowance that fits the rest of the window, or null when the
         * prompt leaves no usable room. Pure, so the arithmetic is pinned by a
         * unit test instead of only discovered on a phone.
         */
        fun replyTokensFor(promptTokens: Int, ctx: Int): Int? {
            val room = ctx - promptTokens - PROMPT_SAFETY
            return if (room < MIN_OUTPUT_TOKENS) null else minOf(MAX_OUTPUT_TOKENS, room)
        }
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
        fun input(unit: String?, ctx: Int) = LlmCreateInput(
            model_path = modelPath,
            tokenizer_path = tokenizerPath ?: "",
            config = ModelConfig(nCtx = ctx, nThreads = 4, nGpuLayers = 0),
            runtime_id = "llama_cpp",
            compute_unit = unit,
        )
        suspend fun attempt(unit: String?, ctx: Int): Result<LlmWrapper> =
            LlmWrapper.builder().llmCreateInput(input(unit, ctx)).build()

        // Full window first; only if BOTH units refuse it does the smaller
        // window come back — and then the label says so, because a 4096-token
        // engine that pretends to be 8192 is how "prompt too long" surprises
        // the owner again.
        val npu = attempt(ComputeUnitValue.NPU.value, N_CTX)
        if (npu.isSuccess) {
            loadedCtx = N_CTX
            return npu
        }
        val npuError = npu.exceptionOrNull()
        Log.w("Vorv/LocalLlm", "NPU build at $N_CTX failed (${npuError?.message}); retrying on CPU", npuError)
        val cpu = attempt(ComputeUnitValue.CPU.value, N_CTX)
        if (cpu.isSuccess) {
            cpuFallback = true
            loadedCtx = N_CTX
            return cpu
        }
        val cpuError = cpu.exceptionOrNull()
        Log.w("Vorv/LocalLlm", "CPU build at $N_CTX failed (${cpuError?.message}); retrying at $FALLBACK_N_CTX", cpuError)

        val npuSmall = attempt(ComputeUnitValue.NPU.value, FALLBACK_N_CTX)
        if (npuSmall.isSuccess) {
            cpuFallback = false
            loadedCtx = FALLBACK_N_CTX
            Log.w("Vorv/LocalLlm", "running at $FALLBACK_N_CTX tokens of context; the long prompt may not fit")
            return npuSmall
        }
        val cpuSmall = attempt(ComputeUnitValue.CPU.value, FALLBACK_N_CTX)
        if (cpuSmall.isSuccess) {
            cpuFallback = true
            loadedCtx = FALLBACK_N_CTX
            Log.w("Vorv/LocalLlm", "running on CPU at $FALLBACK_N_CTX tokens of context")
            return cpuSmall
        }
        cpuFallback = false
        return Result.failure(
            IllegalStateException(
                "NPU: ${npuError?.message}; CPU: ${cpuError?.message}; " +
                    "also failed at $FALLBACK_N_CTX (NPU: ${npuSmall.exceptionOrNull()?.message}; " +
                    "CPU: ${cpuSmall.exceptionOrNull()?.message})",
            ),
        )
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

        // The window is finite and the prompt is not small: the system prompt
        // with the tool schemas can be a few thousand tokens before the user
        // has typed anything. Reserve the reply inside what is LEFT, instead of
        // asking for 2048 on top of a prompt that already exceeds the window —
        // that mismatch is what GenieX reported as "Input prompt too long" for
        // a three-word message.
        val promptTokens = estimateTokens(template.formattedText)
        val replyTokens = replyTokensFor(promptTokens, loadedCtx)
            ?: throw IllegalStateException(
                "The prompt is about $promptTokens tokens and this model's window is $loadedCtx, " +
                    "so there is no room to answer (needs at least $MIN_OUTPUT_TOKENS). " +
                    "Start a new chat to drop the history, or free memory so the full $N_CTX-token window loads.",
            )

        val generationConfig = GenerationConfig().apply {
            // 2048, not 768: long file_write payloads were getting truncated
            // mid-JSON (audit finding M-1), producing unparseable tool calls.
            maxTokens = replyTokens
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

    private fun estimateTokens(text: String): Int =
        kotlin.math.ceil(text.length / CHARS_PER_TOKEN).toInt()

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
