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
 * Picked to match the Samsung Galaxy S25 (Snapdragon 8 Elite, 12GB RAM):
 *   - QWEN_7B:  Qwen2.5-7B-Instruct Q4_K_M GGUF, ~4.7 GB, default — strongest
 *               open coding/tool-use model under 10B. Comfortably fits in 12GB RAM.
 *   - QWEN_3B:  Qwen2.5-3B-Instruct Q4_K M GGUF, ~2.0 GB, lightweight fallback
 *               for when storage or RAM is constrained.
 *
 * Same family, same chat template, same TOOL_CALL/ARGS/ANSWER format — so the
 * prompt builder in LocalLlm.kt needs no per-model branches.
 *
 * Sizes are disclosed honestly: a 7B Q4_K_M download is 4.7 GB. First-time
 * download needs internet; after that, inference is fully offline.
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
        id = "qwen7b",
        displayName = "Qwen2.5-7B-Instruct (Q4_K_M, ~4.7 GB)",
        url = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-7b-instruct-q4_k_m.gguf",
        approxBytes = 4_700_000_000L,
        shortDesc = "Default. Strong coding + tool use. Comfortable on S25.",
    ),
    QWEN_3B(
        id = "qwen3b",
        displayName = "Qwen2.5-3B-Instruct (Q4_K_M, ~2.0 GB)",
        url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
        approxBytes = 2_000_000_000L,
        shortDesc = "Lightweight fallback. Faster + smaller, weaker reasoning.",
    );

    companion object {
        fun fromId(id: String?): ModelChoice = entries.firstOrNull { it.id == id } ?: QWEN_7B
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

    fun anyDownloaded(ctx: Context): ModelChoice? = entries.firstOrNull { isDownloaded(ctx, it) }

    /**
     * Stream the model to disk, emitting (0..100) progress.
     * Writes to a .part file first; renames on success so a killed download
     * never leaves a corrupt model file that `isDownloaded` would accept.
     *
     * Supports HTTP Range resumes — if a .part file exists with a matching
     * ETag/length, the download continues from where it left off.
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
            .addHeader("User-Agent", "Vorv/0.3 (local-first agent; S25 target)")
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
