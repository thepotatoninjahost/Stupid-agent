package host.vorv.llm

import android.content.Context
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * ONE MODEL (owner's decision, 2026-11): OmniCoder-9B. The Qwen2.5-Coder 7B
 * and 3B are gone — not disabled, removed, with their weights deleted from
 * devices that already pulled them.
 *
 * URL and file size verified 2026-11-21: curl -sIL on
 * Tesslate/OmniCoder-9B-GGUF/resolve/main/omnicoder-9b-q4_0.gguf returned
 * HTTP 200 with content-length 5,313,356,096 bytes (5.31 GB).
 *
 * Throughput on the S25's Hexagon NPU (from Qualcomm AI Hub benchmarks, a
 * 8B-class w4a16 proxy): 11.2 tok/s decode peak, ~5 tok/s sustained under
 * thermal load. That is an EXPECTATION only — measured tok/s is surfaced live
 * via LlmStreamResult.Completed.profile.decodingSpeed after each generation.
 *
 * MODEL DOWNLOAD:
 *   Uses GenieX's ModelManagerWrapper.pullFlow() which handles HuggingFace
 *   downloads, checksums, resume-on-failure, and on-disk caching under
 *   filesDir/geniex/models/{org}/{repo}/. We do NOT roll our own downloader.
 */
enum class ModelChoice(
    val id: String,
    val displayName: String,
    val hfRepo: String,           // e.g. "Tesslate/OmniCoder-9B-GGUF"
    val hfFile: String,           // e.g. "omnicoder-9b-q4_0.gguf"
    val precision: String,        // GenieX precision tag, e.g. "Q4_0"
    val approxBytes: Long,        // For UI display only
    val shortDesc: String,
) {
    OMNICODER9B(
        id = "omnicoder9b",
        displayName = "OmniCoder-9B (Q4_0, 5.31 GB)",
        hfRepo = "Tesslate/OmniCoder-9B-GGUF",
        hfFile = "omnicoder-9b-q4_0.gguf",
        precision = "Q4_0",
        approxBytes = 5_313_356_096L,
        shortDesc = "The on-phone coder. Coding-specialised, runs on the Hexagon NPU " +
            "via GenieX's llama_cpp plugin.",
    );

    /**
     * GenieX identifies models by an "<org>/<repo>:<precision>" identifier
     * (e.g. "Tesslate/OmniCoder-9B-GGUF:Q4_0").
     */
    fun geniexModelId(): String = "$hfRepo:$precision"

    companion object {
        /** Any id that is not this build's model (including the removed qwen7b/qwen3b) maps to it. */
        fun fromId(id: String?): ModelChoice = entries.firstOrNull { it.id == id } ?: OMNICODER9B
    }
}

/**
 * Wraps GenieX's ModelManagerWrapper for the one on-device model.
 *
 * GenieX owns the on-disk layout under filesDir/geniex/models/{org}/{repo}/ —
 * we do NOT manage the file paths ourselves, just query them via getPaths().
 */
object ModelManager {

    private val initMutex = Mutex()
    @Volatile private var initialised = false
    @Volatile private var lastInitError: String? = null

    /** Non-null when the last [ensureInit] failed, in words the owner can read. */
    val initError: String? get() = lastInitError
    val isReady: Boolean get() = initialised

    /**
     * Initialize GenieX's model manager. Must be called once at app start,
     * BEFORE any pull/build call. Idempotent.
     */
    suspend fun init() {
        ModelManagerWrapper.init("")
    }

    /**
     * Step 4: the one gate every model path goes through. It runs the SDK once,
     * is safe to call from several coroutines, and — because a phone can be
     * short of memory for a moment — a failure is recorded and retried by the
     * next caller instead of bricking the session. Failure is thrown with a
     * sentence; it never returns quietly.
     */
    suspend fun ensureInit() {
        if (initialised) return
        initMutex.withLock {
            if (initialised) return
            try {
                ModelManagerWrapper.init("")
                initialised = true
                lastInitError = null
            } catch (t: Throwable) {
                lastInitError = "The on-device engine did not start: " +
                    (t.message ?: t.javaClass.simpleName)
                throw IllegalStateException(lastInitError!!, t)
            }
        }
    }

    /**
     * Whether a model has been pulled and is ready to load.
     * Uses hfRepo (no precision suffix) — same convention as pull().
     */
    suspend fun isDownloaded(choice: ModelChoice): Boolean {
        return ModelManagerWrapper.getPaths(choice.hfRepo) != null
    }

    /**
     * The repair model: the one on-device model, when it is on disk.
     *
     * Patch generation (self-fix, self-repair, crash analysis) uses
     * [ModelChoice.OMNICODER9B] when it is downloaded. There is no weaker
     * second choice any more — a model that is not there is reported honestly
     * rather than replaced by a smaller one.
     *
     * @return the downloaded coder model, or null if none is on disk
     *         (callers report that honestly; nothing is auto-pulled).
     */
    suspend fun bestRepairModel(): ModelChoice? =
        if (isDownloaded(ModelChoice.OMNICODER9B)) ModelChoice.OMNICODER9B else null

    /**
     * Honest engine label for repair work — names the model that actually
     * wrote the patch. Surfaces in logs and tool output.
     */
    fun repairLabel(choice: ModelChoice, purpose: String): String =
        "${choice.displayName.substringBefore(" (")} ($purpose)"

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
            model_name = choice.hfRepo,         // e.g. "Tesslate/OmniCoder-9B-GGUF"
            precision = choice.precision,        // e.g. "Q4_0" — passed separately
            hub = HubSource.HUGGINGFACE,
            local_path = "",    // empty = use GenieX default cache dir
            hf_token = "",      // public repo, no token needed
            chipset = "",       // empty = auto-detect (S25 → Snapdragon 8 Elite)
            display_name = choice.displayName,
            model_type = ModelType.LLM,
        )
        return ModelManagerWrapper.pullFlow(input).mapNotNull { pullEventPercent(it) }
            .onCompletion { cause ->
                if (cause == null) verifyOrThrow(choice)
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Step 4: "completed" is a claim by the downloader, so it is checked on
     * disk. A pull that says 100% but left no model, or a file far smaller
     * than the published size, must fail loudly — a half model on disk is the
     * classic silent success.
     */
    private suspend fun verifyOrThrow(choice: ModelChoice) {
        val paths = ModelManagerWrapper.getPaths(choice.hfRepo)
        val problem = verifyDownloaded(
            modelPath = paths?.model_path,
            actualBytes = paths?.model_path?.let { java.io.File(it).length() } ?: 0L,
            expectedBytes = choice.approxBytes,
        )
        if (problem != null) throw IllegalStateException(problem)
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

    /**
     * The weights of the removed Qwen models, deleted from devices that still
     * hold them (owner decision, 2026-11). Runs from the boot scan, before any
     * pull can start. Only these two directories are touched — nothing else
     * under geniex/models is.
     */
    fun cleanupRemovedModels(ctx: Context) {
        val removed = listOf(
            "geniex/models/bartowski/Qwen2.5-Coder-7B-Instruct-GGUF",
            "geniex/models/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF",
        )
        for (relative in removed) {
            val dir = java.io.File(ctx.filesDir, relative)
            if (!dir.exists()) continue
            val gone = runCatching { dir.deleteRecursively() }.getOrDefault(false)
            android.util.Log.i(
                "VorvModel",
                if (gone) "removed the weights of a model this build no longer uses: $relative"
                else "could not remove $relative (leaving it in place)",
            )
        }
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

/**
 * Step 4: is what the downloader claims actually on disk?
 *
 * Pure, so the rule is tested without a phone. Returns null when the model is
 * whole, or one sentence naming what is wrong. A tolerance is applied because
 * the published size is rounded; anything under 98% is treated as truncated.
 */
internal fun verifyDownloaded(modelPath: String?, actualBytes: Long, expectedBytes: Long): String? {
    if (modelPath.isNullOrBlank() || actualBytes <= 0L) {
        return "The download said it finished, but no model file is on disk. Tap Download again."
    }
    if (expectedBytes > 0L && actualBytes < (expectedBytes * 98) / 100) {
        val have = actualBytes / 1_048_576
        val want = expectedBytes / 1_048_576
        return "The model file is incomplete: $have MB of about $want MB. Tap Download again — " +
            "the phone will resume rather than start over."
    }
    return null
}
