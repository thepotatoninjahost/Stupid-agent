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

    /** The file the engine was actually built from (trained generation or base). */
    @Volatile private var loadedModelPath = ""

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
     * Prompt budget of the window that is actually loaded, without the tool
     * roster (GenieX fixes nCtx at load; the NPU may grant less than [N_CTX]).
     * Engines that render tools into the prompt use [promptBudget] instead.
     */
    override val contextTokenBudget: Int
        get() = promptBudgetFor(windowOrFull(), MAX_OUTPUT_TOKENS, TEMPLATE_MARGIN, 0)

    /**
     * Audit (field, 2026-11-22): the phone reported "prompt budget 5896 after
     * compaction" and then "the prompt is about 7971 tokens" on the same call.
     * Both numbers were true: 5896 counted message text, 7971 was what the
     * template built once the tool schemas were rendered into it. The roster
     * is prompt, so it is paid for before the history gets its share.
     */
    override fun promptBudget(tools: List<Llm.ToolSpec>): Int =
        promptBudgetFor(windowOrFull(), MAX_OUTPUT_TOKENS, TEMPLATE_MARGIN, toolRosterTokens(tools))

    /** The window actually allocated — the NPU can grant less than [N_CTX]. */
    private fun windowOrFull(): Int = if (loadedCtx > 0) loadedCtx else N_CTX

    /** Which ModelChoice this engine instance wraps (used by ModelRepository). */
    val modelId: String get() = modelChoice.id

    companion object {
        /**
         * Context the engine allocates, largest rung first.
         *
         * 4096 was too small for this agent: the system prompt alone is ~2.7 KB
         * and the tool roster ~2.3 KB, and the chat template renders the tool
         * SCHEMAS on top of that — so "input prompt too long" could fire on a
         * three-word message. 8192 gave the prompt room but only ~3800 tokens
         * of it once the reply, the template and the roster are paid for, so
         * the owner asked for 10 000+: this ladder tops out at 16384, which
         * leaves ~12 000 tokens of history.
         *
         * The cost is the KV cache, and it is knowable from the GGUF itself.
         * qwen35 is a hybrid: "full_attention_interval = 4" means only 8 of the
         * 32 blocks keep a KV cache (llama.cpp's qwen3next graph allocates KV
         * only for the non-recurrent layers; the other 24 hold a fixed
         * recurrent state of ~27 MB in total). So the cache is
         * 8 x 4 KV heads x (256 + 256) x 2 bytes = 32 KiB per token:
         * 8192 = 256 MiB, 16384 = 512 MiB. Weights (5.31 GB) dominate, which is
         * why the gate below is about free RAM, not about the window.
         */
        const val N_CTX = 16_384
        /** Window tried when the larger ones cannot be allocated. */
        const val FALLBACK_N_CTX = 4_096
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

        /**
         * Prompt tokens the tool roster itself costs. The chat template renders
         * every schema into the prompt, so this is prompt the history cannot
         * have. Pure and measured with the same chars/token rule the kernel
         * uses, so both sides count the same way.
         */
        fun toolRosterTokens(tools: List<Llm.ToolSpec>): Int =
            if (tools.isEmpty()) 0
            else kotlin.math.ceil(buildToolsJson(tools).length / CHARS_PER_TOKEN).toInt()

        /**
         * What is left for messages once the reply, the template and the tool
         * roster are paid for. Never negative: a roster that alone exceeds the
         * window leaves zero, which the caller reports instead of folding
         * history forever.
         */
        fun promptBudgetFor(window: Int, replyReserve: Int, templateMargin: Int, toolTokens: Int): Int =
            (window - replyReserve - templateMargin - toolTokens).coerceAtLeast(0)

        /**
         * Windows to try, largest first, for a device with [totalRamBytes].
         * 16384 needs room for the 5.31 GB of weights plus 512 MiB of KV cache
         * plus Android itself, so it is only attempted on an 11 GB device or
         * better; an 8 GB phone keeps the 8192 rung it already ran. 4096 is always
         * the last rung: a small window beats no engine at all. Pure, so the
         * gate is pinned by a test instead of discovered by an OOM on a phone.
         */
        fun contextLadder(totalRamBytes: Long): List<Int> {
            val gib = totalRamBytes / (1024.0 * 1024.0 * 1024.0)
            val ladder = ArrayList<Int>(3)
            if (gib >= 11.0) ladder.add(16_384)
            if (gib >= 7.0) ladder.add(8_192)
            ladder.add(FALLBACK_N_CTX)
            return ladder
        }

        /** The roster as the chat template receives it: part of the prompt. */
        internal fun buildToolsJson(tools: List<Llm.ToolSpec>): String {
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
         * Layers to offload for a compute unit. GenieX's ModelConfig documents
         * -1 as "all layers" and 0 as "none"; its JNI forces 0 for CPU. Asking
         * for the NPU while passing 0 offloaded nothing, so the build that
         * called itself "NPU" was really running on the CPU.
         */
        fun gpuLayersFor(unit: String?): Int =
            if (unit == ComputeUnitValue.CPU.value) 0 else -1
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
            config = ModelConfig(nCtx = ctx, nThreads = 4, nGpuLayers = gpuLayersFor(unit)),
            runtime_id = "llama_cpp",
            compute_unit = unit,
        )
        suspend fun attempt(unit: String?, ctx: Int): Result<LlmWrapper> {
            val built = LlmWrapper.builder().llmCreateInput(input(unit, ctx)).build()
            built.onSuccess {
                loadedModelPath = modelPath
                // The app states what it loaded, not what the menu hoped for.
                host.vorv.llm.BrainState.update {
                    it.copy(
                        localModelFile = java.io.File(modelPath).name,
                        localWindowTokens = ctx,
                        localComputeUnit = if (unit == ComputeUnitValue.NPU.value) "NPU" else "CPU",
                    )
                }
            }
            return built
        }

        // Largest window the device can hold first, NPU before CPU at each
        // rung. The label says which window won, because a 4096-token engine
        // that pretends to be 16384 is how "prompt too long" surprises the
        // owner again.
        val ladder = contextLadder(deviceTotalRamBytes())
        val failures = ArrayList<String>(ladder.size * 2)
        for (ctx in ladder) {
            val npu = attempt(ComputeUnitValue.NPU.value, ctx)
            if (npu.isSuccess) {
                loadedCtx = ctx
                return npu
            }
            failures.add("NPU $ctx: ${npu.exceptionOrNull()?.message}")
            Log.w("Vorv/LocalLlm", "NPU build at $ctx failed (${npu.exceptionOrNull()?.message}); trying CPU", npu.exceptionOrNull())

            val cpu = attempt(ComputeUnitValue.CPU.value, ctx)
            if (cpu.isSuccess) {
                cpuFallback = true
                loadedCtx = ctx
                Log.w("Vorv/LocalLlm", "running on CPU at $ctx tokens of context")
                return cpu
            }
            failures.add("CPU $ctx: ${cpu.exceptionOrNull()?.message}")
            Log.w("Vorv/LocalLlm", "CPU build at $ctx failed (${cpu.exceptionOrNull()?.message}); trying the next window", cpu.exceptionOrNull())
        }
        cpuFallback = false
        return Result.failure(
            IllegalStateException("no window in $ladder would load — " + failures.joinToString("; ")),
        )
    }

    /** Total RAM, the budget the KV cache has to come out of. */
    private fun deviceTotalRamBytes(): Long = try {
        val am = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem
    } catch (e: Exception) {
        Log.w("Vorv/LocalLlm", "could not read total RAM (${e.message}); assuming 8 GB", e)
        8L * 1024 * 1024 * 1024
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
        host.vorv.llm.BrainState.update { it.copy(lastPromptTokens = promptTokens) }
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
        var chunks = 0

        wrapper.generateStreamFlow(
            prompt = template.formattedText,
            config = generationConfig,
        ).collect { result ->
            when (result) {
                is LlmStreamResult.Token -> {
                    chunks++
                    sb.append(result.text)
                }
                is LlmStreamResult.Completed -> profile = result.profile
                is LlmStreamResult.Error -> streamError = result.throwable
            }
        }

        // The engine's own words are kept, and the numbers that decide whether
        // a prompt can fit are put next to them. Never a guessed cause: this
        // text is what the chat bubble shows.
        streamError?.let { cause ->
            val message = "the on-device model (${java.io.File(loadedModelPath).name}, " +
                "${if (cpuFallback) "CPU" else "NPU"}, window $loadedCtx) could not take " +
                "~$promptTokens prompt tokens with a $replyTokens-token reply allowance. " +
                "GenieX said: ${cause.message}"
            BrainState.update { it.copy(lastRuntimeError = message) }
            throw IllegalStateException(message, cause)
        }

        profile?.let { p ->
            onTelemetry?.invoke(p.decodingSpeed, p.decodeTimeMs)
        }

        // An empty generation is a fact, not an answer. Say what the engine did
        // instead of showing a blank bubble or inventing a cause.
        val text = sb.toString().trim()
        if (text.isEmpty()) {
            val report = profile?.let { p ->
                "the engine reports ${p.generatedTokens} generated tokens, stop reason " +
                    "\"${p.stopReason}\" at %.1f tokens/s".format(p.decodingSpeed)
            } ?: "the engine sent no completion report"
            val message = "the on-device model (${java.io.File(loadedModelPath).name}, " +
                "${if (cpuFallback) "CPU" else "NPU"}, window $loadedCtx) produced no text: " +
                "prompt ~$promptTokens tokens, reply allowance $replyTokens, $chunks stream chunks, " +
                "$report. Nothing was put in the bubble because nothing was generated."
            BrainState.update { it.copy(lastRuntimeError = message) }
            throw IllegalStateException(message)
        }

        BrainState.update { it.copy(lastRuntimeError = null) }

        // H1b: the parser needs the real tool roster so JSON DATA carrying a
        // "name" key on the bare/prose path is treated as an answer, not
        // hijacked into a phantom tool call.
        return ToolCallParser.parse(text, tools.map { it.name }.toSet())
    }

    private fun estimateTokens(text: String): Int =
        kotlin.math.ceil(text.length / CHARS_PER_TOKEN).toInt()

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
