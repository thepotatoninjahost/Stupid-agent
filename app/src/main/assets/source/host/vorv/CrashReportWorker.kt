package host.vorv

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the full crash trace to a file from a fresh WorkManager process.
 *
 * WHY A WORKER (not the UncaughtExceptionHandler directly):
 *   The handler runs in the crashing process with maybe 10-50ms before the
 *   process is killed. File I/O in that window races the kill — the write
 *   might not flush to disk. WorkManager.enqueue() is a fast IPC + transactional
 *   Room write that SURVIVES process death. The worker then runs later in a
 *   fresh, stable process with all the time it needs to write the full trace.
 *
 *   So the handler's job is: capture the trace into a WorkManager input, enqueue
 *   this worker, then die. The worker's job is: write it out reliably.
 *
 *   (For native SIGSEGV crashes, the handler doesn't fire at all — see
 *   [BootTracer] for the breadcrumb approach that catches those.)
 */
class CrashReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        return runCatching {
            val threadName = inputData.getString(KEY_THREAD) ?: "unknown"
            val exceptionClass = inputData.getString(KEY_EXCEPTION_CLASS) ?: "unknown"
            val exceptionMessage = inputData.getString(KEY_EXCEPTION_MESSAGE) ?: ""
            val stackTrace = inputData.getString(KEY_STACK_TRACE) ?: ""
            val crashNumber = inputData.getInt(KEY_CRASH_NUMBER, 0)

            // Audit M-10: second-resolution filenames made two crashes in the
            // same second overwrite each other. Milliseconds + pid are unique.
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
            val crashDir = File(applicationContext.getExternalFilesDir(null), "crash_logs").apply { mkdirs() }
            val crashFile = File(crashDir, "crash_${timestamp}_pid${android.os.Process.myPid()}.txt")

            val sb = StringBuilder()
            sb.appendLine("========================================")
            sb.appendLine("Vorv crash report (written by CrashReportWorker)")
            sb.appendLine("========================================")
            sb.appendLine("Timestamp: ${Date()}")
            sb.appendLine("Crash number in loop: $crashNumber")
            sb.appendLine("Thread: $threadName")
            sb.appendLine("Exception: $exceptionClass")
            sb.appendLine("Message: $exceptionMessage")
            sb.appendLine("========================================")
            sb.appendLine("Stack trace:")
            sb.appendLine("========================================")
            sb.appendLine(stackTrace)
            sb.appendLine()
            sb.appendLine("========================================")
            sb.appendLine("Boot trace (breadcrumbs from this launch):")
            sb.appendLine("========================================")
            sb.appendLine(BootTracer.readTrace(applicationContext))

            crashFile.writeText(sb.toString())

            android.util.Log.e("Vorv/CrashReportWorker", "Crash log written to ${crashFile.absolutePath}")
            Result.success()
        }.getOrElse {
            android.util.Log.e("Vorv/CrashReportWorker", "Failed to write crash log: ${it.message}", it)
            Result.retry()
        }
    }

    companion object {
        const val KEY_THREAD = "thread"
        const val KEY_EXCEPTION_CLASS = "exception_class"
        const val KEY_EXCEPTION_MESSAGE = "exception_message"
        const val KEY_STACK_TRACE = "stack_trace"
        const val KEY_CRASH_NUMBER = "crash_number"
        const val UNIQUE_WORK_NAME = "vorv_crash_report"
    }
}
