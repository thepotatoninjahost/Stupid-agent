package host.vorv.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Selectable on-device models.
 *
 * Picked for the Samsung Galaxy S25 (verified 2026-10-31: 12 GB RAM across all
 * S25 variants, Snapdragon 8 Elite for Galaxy). Both models run via llama.cpp
 * (CPU-only, no GPU/NPU offload — verified from the
 * dev.ffmpegkit-maintained:llama-android 0.1.1 POM description:
 * "Prebuilt llama.cpp AAR for Android — on-device LLM inference, GGUF models,
 * arm64-v8a").
 *
 * MODEL CHOICE — why Coder, not the general Instruct:
 * Vorv is an autonomous CODING agent. Qwen2.5-Coder-7B-Instruct is the same
 * architecture as Qwen2.5-7B-Instruct (qwen2, same chat template, same Q4_K_M
 * quant) but trained specifically on code — so it loads through the same
 * llama.cpp path and the existing TOOL_CALL/ARGS/ANSWER prompt works unchanged,
 * but its tool-selection and code-generation are markedly better for the
 * agent's actual job. Same RAM/disk cost.
 *
 * URLs and file sizes verified 2026-10-31 by fetching the HuggingFace file
 * listings AND curl-ing the resolve URLs to confirm HTTP 200 + Content-Length:
 *   - 7B Coder Q4_K_M: official Qwen repo has a single unsplit file at
 *     4,683,073,536 bytes (4.68 GB). Verified.
 *   - 3B Coder Q4_K_M: official Qwen repo has a single unsplit file at
 *     2,104,932,800 bytes (2.10 GB). Verified.
 *
 * Throughput (tok/s) is NOT stated here — it depends on the device and is
 * reported live by the LLM via result.tokensPerSecond, surfaced in the
 * reasoning trace. We do not fabricate benchmark numbers.
 */
enum class ModelChoice(
    val id: String,
    val displayName: String,
    val url: String,
    val fileName: String,
    val approxBytes: Long,
    val shortDesc: String,
) {
    QWEN_7B(
        // Pref id stays "qwen7b" so existing installs don't get a pref reset
        // on upgrade — but the URL/filename now point at the Coder variant.
        id = "qwen7b",
        displayName = "Qwen2.5-Coder-7B-Instruct (Q4_K_M, 4.68 GB)",
        // Verified 2026-10-31: official Qwen repo has a single unsplit file.
        url = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
        approxBytes = 4_683_073_536L,
        shortDesc = "Default. Coding-specialized, instruction-tuned, supports " +
            "tool calling. Largest sensible fit on a 12 GB RAM phone via CPU llama.cpp.",
    ),
    QWEN_3B(
        id = "qwen3b",
        displayName = "Qwen2.5-Coder-3B-Instruct (Q4_K_M, 2.10 GB)",
        // Verified 2026-10-31: official Qwen repo has a single unsplit file.
        url = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        approxBytes = 2_104_932_800L,
        shortDesc = "Lighter + faster, weaker reasoning. Pick if 7B is too slow.",
    );

    companion object {
        fun fromId(id: String?): ModelChoice = entries.firstOrNull { it.id == id } ?: QWEN_7B

        /**
         * Filenames from prior versions that are no longer selectable. We delete
         * these on boot so an upgrade from a version that downloaded the old
         * Qwen2.5-7B-Instruct (non-Coder) variant doesn't leave 4.68 GB of
         * orphaned files in app private storage with no UI to clean them up.
         */
        val LEGACY_FILENAMES: List<String> = listOf(
            "Qwen2.5-7B-Instruct-Q4_K_M.gguf",           // pre-Coder swap (v0.3.1)
            "qwen2.5-7b-instruct-q4_k_m.gguf",           // pre-fix (v0.3.0) — never resolved but listed
            "qwen2.5-3b-instruct-q4_k_m.gguf",          // pre-Coder swap (v0.3.1)
            "qwen2.5-0.5b-instruct-q4_k_m.gguf",        // original 0.5B model (v0.2.0)
        )
    }
}

/**
 * Downloads and manages the local GGUF model file.
 * Files live in app private storage (filesDir/models/<choice.fileName>).
 */
object ModelManager {

    fun modelFile(ctx: Context, choice: ModelChoice): File =
        File(File(ctx.filesDir, "models").apply { mkdirs() }, choice.fileName)

    fun isDownloaded(ctx: Context, choice: ModelChoice): Boolean {
        val f = modelFile(ctx, choice)
        // Reject anything smaller than 70% of expected — partial/corrupt
        return f.exists() && f.length() > (choice.approxBytes * 7 / 10)
    }

    fun anyDownloaded(ctx: Context): ModelChoice? = ModelChoice.entries.firstOrNull { isDownloaded(ctx, it) }

    /**
     * Delete orphaned files from prior versions. Called once at app boot.
     * Idempotent — safe to call every boot. Only deletes files matching the
     * LEGACY_FILENAMES list, never anything user-selected now.
     */
    fun cleanupLegacyModels(ctx: Context): Int {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        var deleted = 0
        ModelChoice.LEGACY_FILENAMES.forEach { name ->
            val f = File(dir, name)
            if (f.exists()) {
                if (f.delete()) deleted++
            }
        }
        return deleted
    }

    /**
     * Stream the model to disk, emitting (0..100) progress.
     * Writes to a .part file first; renames on success so a killed download
     * never leaves a corrupt model file that `isDownloaded` would accept.
     *
     * Supports HTTP Range resumes — if a .part file exists, the download
     * continues from where it left off. Verified against HuggingFace's CDN
     * which returns 206 Partial Content for Range requests.
     */
    fun download(ctx: Context, choice: ModelChoice): Flow<Int> = flow {
        val target = modelFile(ctx, choice)
        val part = File(target.absolutePath + ".part")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val resumeFrom = if (part.exists()) part.length() else 0L

        val builder = Request.Builder()
            .url(choice.url)
            .addHeader("User-Agent", "Vorv/0.4 (local-first coding agent; S25 target)")
        if (resumeFrom > 0) {
            builder.addHeader("Range", "bytes=$resumeFrom-")
        }

        client.newCall(builder.build()).execute().use { res ->
            // 206 = partial content (resume OK); 200 = full restart (range ignored)
            val resumed = res.code == 206
            if (!res.isSuccessful && res.code != 206) {
                throw RuntimeException("Model download failed: HTTP ${res.code} — check connection and try again.")
            }
            val totalHeader = res.header("Content-Length")?.toLongOrNull() ?: choice.approxBytes
            val total = if (resumed) resumeFrom + totalHeader else totalHeader
            var written = if (resumed) resumeFrom else 0L

            val appendMode = resumed
            val tmp = java.io.FileOutputStream(part, appendMode).buffered()
            res.body?.byteStream()?.buffered()?.use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    tmp.write(buf, 0, n)
                    written += n
                    val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                    emit(pct)
                }
            }
            tmp.flush(); tmp.close()

            if (written < (choice.approxBytes * 7 / 10)) {
                part.delete()
                throw RuntimeException("Downloaded file too small ($written bytes) — likely corrupted. Try again.")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw RuntimeException("Could not finalize model file at ${target.absolutePath}")
            }
            emit(100)
        }
    }.flowOn(Dispatchers.IO)
}
