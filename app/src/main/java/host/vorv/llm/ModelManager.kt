package host.vorv.llm

import android.content.Context
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Selectable on-device models.
 *
 * ENGINE (verified 2026-10-31 by inspecting the GenieX AAR at
 * repo1.maven.org/maven2/com/qualcomm/qti/geniex-android/0.10.0/):
 *   - Qualcomm's first-party Android runtime, BSD-3-Clause, no NDA, no login.
 *   - The AAR ships 52 .so files for arm64-v8a including:
 *       libggml-hexagon.so                              → Hexagon NPU backend
 *       libggml-htp-v73.so, v75, v79, v81, v85          → HTP runtime libs
 *       libQnnHtpV79.so, V81.so + Stub variants          → QNN C API for HTP
 *       libggml-opencl.so                                → Adreno GPU backend
 *       libggml-cpu.so                                   → CPU fallback
 *       libgeniex_plugin_llama_cpp.so + qairt.so         → GenieX plugins
 *       libllama.so + libllama-common.so                 → llama.cpp core
 *   - Hexagon HTP v81 = the S25's Snapdragon 8 Elite for Galaxy NPU.
 *
 * MODEL CHOICE — why Q4_0, not Q4_K_M:
 *   Qualcomm's own GenieX docs (notes/run.md) state:
 *     "For llama.cpp, pick the Q4_0 precision — it has the best Hexagon NPU
 *      support. Q4_K_M is a suboptimal quant for HTP; some tensors fall back
 *      to CPU. Use Q4_0 / Q8_0 for a clean NPU run."
 *   So we use Q4_0 even though it's slightly larger per quality point —
 *   the NPU runs cleanly without CPU fallback.
 *
 * URLs and file sizes verified 2026-10-31 via curl -sIL → HTTP 200 +
 * content-length confirmed:
 *   - 7B Coder Q4_0: bartowski's repo, single unsplit file at
 *     4,444,121,888 bytes (4.44 GB)
 *   - 3B Coder Q4_0: bartowski's repo, single unsplit file at
 *     1,828,486,400 bytes (1.83 GB)
 *
 * Throughput on the S25's Hexagon NPU (from Qualcomm AI Hub benchmarks):
 *   - Llama-3.1-8B-Instruct w4a16 (closest proxy): 11.2 tok/s decode peak
 *   - Real-world sustained under thermal load: ~5 tok/s
 *   - For Qwen2.5-Coder-7B Q4_0: expect 8-12 tok/s peak, 4-6 sustained.
 *   These are EXPECTATIONS only — real measured tok/s is surfaced live via
 *   LlmStreamResult.Completed.profile.decodingSpeed after each generation.
 *
 * MODEL DOWNLOAD:
 *   Uses GenieX's ModelManagerWrapper.pullFlow() which handles HuggingFace
 *   downloads, checksums, resume-on-failure, and on-disk caching under
 *   filesDir/geniex/models/{org}/{repo}/. We do NOT roll our own downloader.
 */
enum class ModelChoice(
    val id: String,
    val displayName: String,
    val hfRepo: String,           // e.g. "bartowski/Qwen2.5-Coder-7B-Instruct-GGUF"
    val hfFile: String,           // e.g. "Qwen2.5-Coder-7B-Instruct-Q4_0.gguf"
    val precision: String,        // GenieX precision tag, e.g. "Q4_0"
    val approxBytes: Long,        // For UI display only
    val shortDesc: String,
) {
    QWEN_7B(
        id = "qwen7b",
        displayName = "Qwen2.5-Coder-7B-Instruct (Q4_0, 4.44 GB)",
        hfRepo = "bartowski/Qwen2.5-Coder-7B-Instruct-GGUF",
        hfFile = "Qwen2.5-Coder-7B-Instruct-Q4_0.gguf",
        precision = "Q4_0",
        approxBytes = 4_444_121_888L,
        shortDesc = "Default. Coding-specialized, runs on Hexagon NPU via " +
            "GenieX's llama_cpp plugin. Largest sensible fit on the S25.",
    ),
    QWEN_3B(
        id = "qwen3b",
        displayName = "Qwen2.5-Coder-3B-Instruct (Q4_0, 1.83 GB)",
        hfRepo = "bartowski/Qwen2.5-Coder-3B-Instruct-GGUF",
        hfFile = "Qwen2.5-Coder-3B-Instruct-Q4_0.gguf",
        precision = "Q4_0",
        approxBytes = 1_828_486_400L,
        shortDesc = "Lighter + faster, weaker reasoning. Pick if 7B is too slow.",
    );

    /**
     * GenieX identifies models by an "<org>/<repo>:<precision>" identifier
     * (e.g. "bartowski/Qwen2.5-Coder-7B-Instruct-GGUF:Q4_0").
     */
    fun geniexModelId(): String = "$hfRepo:$precision"

    companion object {
        fun fromId(id: String?): ModelChoice = entries.firstOrNull { it.id == id } ?: QWEN_7B
    }
}

/**
 * Wraps GenieX's ModelManagerWrapper for our two model choices.
 *
 * GenieX owns the on-disk layout under filesDir/geniex/models/{org}/{repo}/ —
 * we do NOT manage the file paths ourselves, just query them via getPaths().
 */
object ModelManager {

    /**
     * Initialize GenieX's model manager. Must be called once at app start,
     * BEFORE any pull/build call. Idempotent.
     */
    suspend fun init() {
        ModelManagerWrapper.init("")
    }

    /**
     * Whether a model has been pulled and is ready to load.
     * Uses hfRepo (no precision suffix) — same convention as pull().
     */
    suspend fun isDownloaded(choice: ModelChoice): Boolean {
        return ModelManagerWrapper.getPaths(choice.hfRepo) != null
    }

    /**
     * Strongest-first model selection for REPAIR work (self-fix, self-repair,
     * crash analysis) — v0.6.9.
     *
     * Patch generation must use the BEST coder model on the device: the 7B
     * whenever it is downloaded. The 3B is used ONLY when the 7B is absent.
     * Nothing is disabled — but the weak model is never chosen to write code
     * repairs while the strong one sits on disk (user feedback: "what good
     * is a 3B model going to do" for fixes — correct; a 3B is a chat toy,
     * not a repair engineer).
     *
     * The verifier gates (PatchVerifier, signature, consent, probation
     * rollback) stay on regardless of which model generates a patch, so even
     * the 3B fallback can never land a partial or broken fix — it just fails
     * honestly more often.
     *
     * @return the strongest downloaded coder model, or null if none is on
     *         disk (callers report that honestly; nothing is auto-pulled).
     */
    suspend fun bestRepairModel(): ModelChoice? = when {
        isDownloaded(ModelChoice.QWEN_7B) -> ModelChoice.QWEN_7B
        isDownloaded(ModelChoice.QWEN_3B) -> ModelChoice.QWEN_3B
        else -> null
    }

    /**
     * Honest engine label for repair work — says which model actually
     * generated the patch, and flags when it had to be the 3B because the
     * 7B is not on the device. Surfaces in logs and tool output.
     */
    fun repairLabel(choice: ModelChoice, purpose: String): String = when (choice) {
        ModelChoice.QWEN_7B -> "Qwen2.5-Coder-7B ($purpose)"
        ModelChoice.QWEN_3B -> "Qwen2.5-Coder-3B ($purpose; 7B not downloaded)"
    }

    /**
     * Stream-pull a model from HuggingFace via GenieX's ModelManagerWrapper.
     * Emits integer percent (0..100). GenieX handles checksums, resume,
     * and on-disk caching.
     *
     * The Flow emits Progress events with per-file byte counts. We aggregate
     * across files (a single .gguf is one file, but GenieX supports multi-
     * file model bundles for VLM) into a single 0-100 percent.
     *
     * FIX (rc=-100009 AUTH error): GenieX's ModelPullInput.model_name expects
     * just the HuggingFace org/repo path WITHOUT a precision suffix. The
     * precision is passed separately as `precision = "Q4_0"`. Verified format
     * from Qualcomm's official geniex_chat_android sample app's
     * assets/model_list.json which uses:
     *   "modelName": "bartowski/microsoft_Phi-4-mini-instruct-GGUF"
     *   "quant": "Q4_0"
     */
    fun pull(choice: ModelChoice): Flow<Int> {
        val input = ModelPullInput(
            model_name = choice.hfRepo,         // e.g. "bartowski/Qwen2.5-Coder-7B-Instruct-GGUF"
            precision = choice.precision,        // e.g. "Q4_0" — passed separately
            hub = HubSource.HUGGINGFACE,
            local_path = "",    // empty = use GenieX default cache dir
            hf_token = "",      // public repo, no token needed
            chipset = "",       // empty = auto-detect (S25 → Snapdragon 8 Elite)
            display_name = choice.displayName,
            model_type = ModelType.LLM,
        )
        return ModelManagerWrapper.pullFlow(input).mapNotNull { pullEventPercent(it) }
    }

    /**
     * Get the local file paths for a pulled model. Returns null if not pulled yet.
     * Uses hfRepo (no precision suffix) — same convention as pull().
     */
    suspend fun getPaths(choice: ModelChoice): com.geniex.sdk.bean.ModelPaths? {
        return ModelManagerWrapper.getPaths(choice.hfRepo)
    }

    /**
     * Delete a pulled model from disk. Safe to call if not pulled.
     * Uses hfRepo (no precision suffix) — same convention as pull().
     */
    suspend fun remove(choice: ModelChoice) {
        ModelManagerWrapper.remove(choice.hfRepo)
    }

    /**
     * Legacy models from v0.4.0 (CPU-only llama.cpp + Q4_K_M) need cleanup
     * on upgrade. These lived in filesDir/models/ — GenieX uses a different
     * path so we just wipe the old dir if it exists.
     */
    fun cleanupLegacyModelsDir(ctx: Context) {
        val legacy = java.io.File(ctx.filesDir, "models")
        if (!legacy.exists()) return
        runCatching { legacy.deleteRecursively() }
    }
}

/**
 * M7 (code review, 2026-11): one pull event → percent, or null when the
 * event carries NO real progress. The old mapping emitted 0 for unknown
 * events AND for Progress events whose total was not yet known — flickering
 * the download bar back to 0% mid-download. null means "emit nothing": the
 * bar never moves backwards. Errors still throw (honest failure).
 * Pure over the SDK types, so it is unit-testable.
 */
internal fun pullEventPercent(event: ModelManagerWrapper.PullEvent): Int? = when (event) {
    is ModelManagerWrapper.PullEvent.Progress -> {
        val total = event.files.sumOf { it.total_bytes }
        val done = event.files.sumOf { it.downloaded_bytes }
        if (total <= 0L) null else ((done * 100) / total).toInt().coerceIn(0, 100)
    }
    is ModelManagerWrapper.PullEvent.Completed -> 100
    is ModelManagerWrapper.PullEvent.Error ->
        throw RuntimeException("Model pull failed (code=${event.code}): ${event.message}")
    else -> null
}
