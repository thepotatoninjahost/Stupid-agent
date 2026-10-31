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
 * Downloads and manages the local GGUF model file.
 *
 * Model: Qwen2.5-0.5B-Instruct Q4_K_M — official, un-gated, ~400MB.
 * Stored in the app's private files dir. One download, then fully offline.
 */
object ModelManager {

    const val MODEL_URL =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val MODEL_FILE_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val APPROX_BYTES = 400_000_000L

    fun modelFile(ctx: Context): File =
        File(File(ctx.filesDir, "models").apply { mkdirs() }, MODEL_FILE_NAME)

    fun isDownloaded(ctx: Context): Boolean {
        val f = modelFile(ctx)
        return f.exists() && f.length() > 100_000_000 // a valid Q4_K_M 0.5B is ~400MB; 100MB floor rejects partial files
    }

    /**
     * Stream the model to disk, emitting (0..100) progress.
     * Writes to a .part file first; renames on success so a killed download
     * never leaves a corrupt model that `isDownloaded` would accept.
     */
    fun download(ctx: Context): Flow<Int> = flow {
        val target = modelFile(ctx)
        val part = File(target.absolutePath + ".part")
        if (part.exists()) part.delete()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(MODEL_URL)
            .addHeader("User-Agent", "Vorv/0.2 (local-first agent)")
            .build()

        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                throw RuntimeException("Model download failed: HTTP ${res.code} — check connection and try again.")
            }
            val total = res.body?.contentLength() ?: APPROX_BYTES
            var written = 0L
            val tmp = part.outputStream().buffered()
            res.body?.byteStream()?.buffered()?.use { input ->
                val buf = ByteArray(64 * 1024)
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
            if (written < 100_000_000) {
                part.delete()
                throw RuntimeException("Downloaded file too small ($written bytes) — likely corrupted. Try again.")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw RuntimeException("Could not finalize model file.")
            }
            emit(100)
        }
    }.flowOn(Dispatchers.IO)
}
