package host.vorv.learn

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Step 7: the way the corpus leaves the phone.
 *
 * The file is the same JSONL [LearningLedger.exportJsonl] writes, put in a
 * folder that can be shared (Drive, email, a USB cable) and named with the
 * moment it was made, so two exports are never confused.
 *
 * Nothing here claims success on its own: every function returns what actually
 * happened, and the caller shows that text.
 */
object CorpusExport {

    const val FOLDER = "exports"

    data class Result(
        val ok: Boolean,
        val file: File?,
        val rows: Int,
        val message: String,
    )

    fun folder(context: Context): File =
        File(context.filesDir, FOLDER).apply { if (!exists() && !mkdirs()) { /* reported by the caller */ } }

    fun write(context: Context, jsonl: String, rows: Int, now: Long = System.currentTimeMillis()): Result {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val target = File(folder(context), "ledger-$stamp.jsonl")
        return try {
            target.writeText(jsonl, Charsets.UTF_8)
            val written = target.length()
            if (written <= 0L) {
                Result(false, target, rows, "The training file came out empty, so it was not kept.")
            } else {
                Result(
                    true,
                    target,
                    rows,
                    "Saved $rows rows (${written / 1024} KB) as ${target.name}.",
                )
            }
        } catch (t: Throwable) {
            target.delete()
            Result(false, null, rows, "Could not save the training file: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** A share sheet for [file], or null when the system has nothing to share with. */
    fun shareIntent(context: Context, file: File): Intent? {
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (t: Throwable) {
            return null
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Send the training file").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
