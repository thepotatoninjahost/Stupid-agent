package host.vorv.llm

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local LLM — real on-device inference via llama.cpp (GGUF).
 *
 * Default model: Qwen2.5-7B-Instruct Q4_K_M (~4.7 GB) — sized for the
 * Samsung Galaxy S25 (Snapdragon 8 Elite, 12 GB RAM). Picked because Qwen2.5-7B
 * is the strongest open coding + structured-output model under 10B.
 * Qwen2.5-3B (~2.0 GB) is offered as a smaller fallback.
 *
 * No auth, no cloud, no API key. Downloaded once from HuggingFace into the app's
 * private storage (see ModelManager). After that single download, inference is
 * fully offline.
 *
 * Small/quantized models can't reliably emit OpenAI-style function-call JSON,
 * so we use a rigid text format + regex parser:
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

    private val threads = maxOf(2, Runtime.getRuntime().availableProcessors() / 2)

    /**
     * Model handle — loaded ONCE on first use (native init takes seconds).
     * loadModel is suspend, so we memoize manually instead of `by lazy`.
     */
    private var loadedModel: dev.ffmpegkit.llama.LlamaModel? = null

    private suspend fun ensureModel(): dev.ffmpegkit.llama.LlamaModel {
        loadedModel?.let { return it }
        require(modelFile.exists() && modelFile.length() > 1_000_000) {
            "Local model file missing or corrupt at ${modelFile.absolutePath}"
        }
        val m = Llama.loadModel(
            modelPath = modelFile.absolutePath,
            config = LlamaConfig(contextSize = 4096, threads = threads),
        )
        // Once the model is up, enable vector embeddings for memory_recall.
        EmbeddingCache.attach { text ->
            val v = Llama.embed(m, text)
            FloatArray(v.size) { v[it] }
        }
        loadedModel = m
        return m
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
                            appendLine("Assistant called $tc.name with $tc.arguments")
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
