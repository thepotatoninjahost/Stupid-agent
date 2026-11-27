package host.vorv

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import host.vorv.healer.CrashText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Second chance at a full crash trace, written from a fresh WorkManager process.
 *
 * WHY A WORKER AT ALL:
 *   The UncaughtExceptionHandler runs in the crashing process with the process
 *   about to be killed. File I/O there can lose the race, and WorkManager.enqueue
 *   is a request to run later — not a guarantee that anything was persisted
 *   before death. So the handler writes its report FIRST, synchronously, and
 *   only then enqueues this worker.
 *
 *   This worker therefore exists for the case where that write failed (no
 *   external storage, a transient I/O fault): it runs later in a stable
 *   process and retries. When the handler's report is already on disk it does
 *   nothing — one crash, one report.
 *
 *   (For native SIGSEGV crashes, no Java handler fires at all — see
 *   [BootTracer] and [host.vorv.healer.NativeCrashHistory].)
 */
class CrashReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        // The handler's synchronous report is authoritative; a second file for
        // the same crash would make the report list lie about how many
        // crashes there were.
        val alreadyReported = inputData.getString(KEY_REPORT_PATH)?.let { File(it) }
        if (alreadyReported != null && alreadyReported.isFile) {
            android.util.Log.i(TAG, "Crash already reported synchronously: ${alreadyReported.absolutePath}")
            return Result.success()
        }

        return runCatching {
            val threadName = inputData.getString(KEY_THREAD) ?: "unknown"
            val exceptionClass = inputData.getString(KEY_EXCEPTION_CLASS) ?: "unknown"
            val exceptionMessage = inputData.getString(KEY_EXCEPTION_MESSAGE) ?: ""
            val stackTrace = inputData.getString(KEY_STACK_TRACE) ?: ""

            // Audit M-10: millisecond resolution + pid in the filename — two
            // crashes in the same second no longer overwrite each other's logs.
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
            val crashDir = File(
                applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir,
                "crash_logs",
            ).apply { mkdirs() }
            val crashFile = File(crashDir, "crash_${timestamp}_pid${android.os.Process.myPid()}.txt")

            crashFile.writeText(
                CrashText.format(
                    threadName = threadName,
                    exceptionClass = exceptionClass,
                    message = exceptionMessage,
                    stackTrace = stackTrace,
                    bootTrace = BootTracer.readTrace(applicationContext),
                    writtenBy = "CrashReportWorker, in a fresh process after the crash",
                ),
            )

            android.util.Log.e(TAG, "Crash log written to ${crashFile.absolutePath}")
            Result.success()
        }.getOrElse {
            android.util.Log.e(TAG, "Failed to write crash log: ${it.message}", it)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Vorv/CrashReportWorker"

        const val KEY_THREAD = "thread"
        const val KEY_EXCEPTION_CLASS = "exception_class"
        const val KEY_EXCEPTION_MESSAGE = "exception_message"
        const val KEY_STACK_TRACE = "stack_trace"
        const val KEY_CRASH_NUMBER = "crash_number"

        /** Set when the crashing process already wrote this report itself. */
        const val KEY_REPORT_PATH = "report_path"

        const val UNIQUE_WORK_NAME = "vorv_crash_report"
    }
}
