package host.vorv.llm

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local LLM — real on-device inference via llama.cpp (GGUF).
 *
 * BACKEND (verified 2026-10-31 by inspecting the compiled AAR at
 * repo1.maven.org/maven2/dev/ffmpegkit-maintained/llama-android/0.1.1/):
 *   - The AAR ships only libggml-base.so, libggml-cpu.so, libggml.so,
 *     libllama.so, libllama_jni.so for arm64-v8a. There is NO
 *     libggml-vulkan.so. This build is CPU-only.
 *   - LlamaConfig.gpuLayers exists in the API but is unused — the S25's
 *     Adreno 830 GPU and Hexagon NPU are NOT engaged. All inference
 *     runs on the 8 Oryon CPU cores.
 *
 * MODELS (verified 2026-10-31 against HuggingFace file listings):
 *   - Default: Qwen2.5-7B-Instruct Q4_K_M, 4.68 GB, single file from
 *     bartowski's mirror (official Qwen repo splits it into 2 parts).
 *   - Fallback: Qwen2.5-3B-Instruct Q4_K_M, 2.10 GB, single file from
 *     official Qwen repo.
 *
 * Throughput is NOT promised in advance — it depends on the device.
 * `result.tokensPerSecond` (Float, verified field on LlamaResult) is
 * surfaced after each call via [onTelemetry] so the UI shows the real,
 * measured value.
 *
 * Small/quantized models can't reliably emit OpenAI-style function-call
 * JSON, so we use a rigid text format + regex parser:
 *
 *   TOOL_CALL: <name>
 *   ARGS: {"k": "v"}
 *
 *   ANSWER: <final text>
 */
class LocalLlm(
    appContext: Context,
    private val modelFile: File,
    private val displayLabel: String = "Qwen2.5-7B (local, offline)",
) : Llm {

    override val label = displayLabel

    /**
     * Use 4 of the 8 Oryon cores. Leaves headroom for the foreground
     * service + UI thread so the device doesn't jank during inference.
     */
    private val threads = maxOf(2, Runtime.getRuntime().availableProcessors() / 2)

    /**
     * Model handle — loaded ONCE on first use (native init takes seconds).
     * `Llama.loadModel` is suspend, so we memoize manually instead of `by lazy`.
     *
     * Verified API surface (from bytecode disassembly of Llama.class):
     *   Llama.loadModel(modelPath: String, config: LlamaConfig): suspend -> LlamaModel
     *   Llama.complete(model: LlamaModel, prompt: String, systemPrompt: String,
     *                  maxTokens: Int): suspend -> LlamaResult
     *   Llama.embed(model: LlamaModel, text: String): suspend -> List<Float>
     *   Llama.releaseModel(model: LlamaModel): void   (non-suspend — frees native memory)
     *
     * LlamaResult fields (verified from bytecode):
     *   text: String, tokensGenerated: Int, tokensPerSecond: Float,
     *   promptEvalTimeMs: Long, generateTimeMs: Long
     */
    private var loadedModel: LlamaModel? = null

    /**
     * Optional callback invoked after each `complete()` call with the real,
     * measured tok/s and generate-time. The kernel uses this to emit a
     * telemetry TraceStep so the UI shows actual device numbers — never
     * fabricated estimates.
     */
    var onTelemetry: ((tokensPerSecond: Float, generateMs: Long) -> Unit)? = null

    private suspend fun ensureModel(): LlamaModel {
        loadedModel?.let { return it }
        require(modelFile.exists() && modelFile.length() > 1_000_000) {
            "Local model file missing or corrupt at ${modelFile.absolutePath}"
        }
        val m = Llama.loadModel(
            modelPath = modelFile.absolutePath,
            config = LlamaConfig(
                contextSize = 4096,
                threads = threads,
                gpuLayers = 0, // No Vulkan backend in this AAR — CPU only.
                temperature = 0.2f,   // Low temp for rigid TOOL_CALL/ANSWER format adherence.
                topP = 0.9f,
                topK = 40,
                seed = -1,            // -1 = random
            ),
        )
        // Once the model is up, enable vector embeddings for memory_recall.
        EmbeddingCache.attach { text ->
            val v = Llama.embed(m, text)
            FloatArray(v.size) { v[it] }
        }
        loadedModel = m
        return m
    }

    /**
     * Free native memory. MUST be called when swapping models or closing,
     * otherwise the GGUF mmap stays resident. Verified non-suspend API.
     */
    fun release() {
        loadedModel?.let { Llama.releaseModel(it) }
        loadedModel = null
    }

    override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse =
        withContext(Dispatchers.Default) {
            val model = ensureModel()
            val system = messages.firstOrNull { it.role == "system" }?.content ?: ""
            val flattened = flattenConversation(messages)
            val prompt = buildPrompt(system, flattened, tools)

            val result = Llama.complete(
                model,
                prompt = prompt,
                systemPrompt = SYSTEM_HEADER,
                maxTokens = 768,
            )

            // Verified field on LlamaResult — real measured telemetry.
            onTelemetry?.invoke(result.tokensPerSecond, result.generateTimeMs)

            parseToolCallOrAnswer(result.text.trim())
        }

    // ------------------------------------------------------------------ prompt

    private fun buildPrompt(system: String, conversation: String, tools: List<Llm.ToolSpec>): String = buildString {
        appendLine(system)
        appendLine()
        if (tools.isNotEmpty()) {
            appendLine("TOOLS:")
            tools.forEach { t ->
                val params = t.parameters.getAsJsonObject("properties")?.keySet()?.joinToString(", ") ?: ""
                appendLine("- ${t.name}($params): ${t.description}")
            }
            appendLine()
        }
        appendLine("Respond in EXACTLY one of these two formats:")
        appendLine("To call a tool:")
        appendLine("TOOL_CALL: <tool_name>")
        appendLine("ARGS: <single-line JSON object>")
        appendLine("Or to give the final answer:")
        appendLine("ANSWER: <your answer>")
        appendLine()
        appendLine(conversation)
    }

    /**
     * Flatten the full message history (including tool results) into one prompt block,
     * because llama.cpp's complete() is single-shot.
     */
    private fun flattenConversation(messages: List<Llm.Message>): String = buildString {
        messages.dropWhile { it.role == "system" }.forEach { m ->
            when (m.role) {
                "user" -> appendLine("User: ${m.content}")
                "assistant" -> {
                    if (m.toolCalls.isNotEmpty()) {
                        m.toolCalls.forEach { tc ->
                            appendLine("Assistant called ${tc.name} with ${tc.arguments}")
                        }
                    }
                    if (m.content.isNotBlank()) appendLine("Assistant: ${m.content}")
                }
                "tool" -> appendLine("Tool result: ${m.content.take(1500)}")
            }
        }
        appendLine()
        append("Your turn. TOOL_CALL + ARGS, or ANSWER:")
    }

    // ------------------------------------------------------------------ parser

    private val toolCallRegex = Regex(
        pattern = """TOOL_CALL:\s*([A-Za-z0-9_]+)\s*(?:\r?\n|:)ARGS:\s*(\{.*\})""",
        option = RegexOption.DOT_MATCHES_ALL,
    )
    private val answerRegex = Regex("""ANSWER:\s*(.*)""", RegexOption.DOT_MATCHES_ALL)

    private fun parseToolCallOrAnswer(text: String): Llm.LlmResponse {
        val tc = toolCallRegex.find(text)
        if (tc != null) {
            val name = tc.groupValues[1]
            val rawArgs = tc.groupValues[2].trim()
            val args = try {
                JsonParser.parseString(rawArgs).asJsonObject
            } catch (e: Exception) {
                JsonObject().apply { addProperty("error", "model produced invalid JSON args: $rawArgs") }
            }
            return Llm.LlmResponse.ToolUse(
                toolCalls = listOf(Llm.ToolCall(id = java.util.UUID.randomUUID().toString(), name = name, arguments = args.toString())),
                reasoning = null,
            )
        }
        val ans = answerRegex.find(text)
        val body = ans?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: text
        return Llm.LlmResponse.Text(body, done = true)
    }

    companion object {
        private const val SYSTEM_HEADER =
            "You are Vorv, an autonomous agent running fully offline on the user's phone."
    }
}

/**
 * In-memory embedding cache for vector-search memory_recall.
 * Populated at write time when the local model is loaded.
 */
object EmbeddingCache {
    @Volatile var active: Boolean = false
        private set

    private var embedFn: (suspend (String) -> FloatArray?)? = null
    private val vectors = HashMap<String, FloatArray>()

    fun attach(embedder: suspend (String) -> FloatArray?) {
        embedFn = embedder
        active = true
    }

    suspend fun embed(text: String, key: String): FloatArray? {
        val fn = embedFn ?: return null
        val v = try { fn(text) } catch (e: Exception) { null } ?: return null
        store(key, v)
        return v
    }

    fun store(key: String, vec: FloatArray) { synchronized(vectors) { vectors[key] = vec } }
    fun remove(key: String) { synchronized(vectors) { vectors.remove(key) } }

    /** Return cached keys ranked by cosine similarity to `queryVec`. */
    fun rank(queryVec: FloatArray, limit: Int): List<Pair<String, Double>> = synchronized(vectors) {
        vectors.entries
            .map { (k, v) -> k to cosine(queryVec, v) }
            .sortedByDescending { it.second }
            .take(limit)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i].toDouble() * a[i]; nb += b[i].toDouble() * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}
