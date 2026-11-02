package host.vorv

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import host.vorv.data.SecretsStore
import host.vorv.data.VorvDatabase
import host.vorv.data.VorvPrefs
import host.vorv.llm.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application-scoped wiring (audit findings H-4, L-7):
 *  - owns the DB, prefs, secrets and the ModelRepository as singletons
 *  - migrates the legacy plaintext API key into encrypted storage, once
 *  - releases the local LLM's native memory under system memory pressure
 *    (when no task is running — the engine rebuilds lazily)
 *
 * v0.6.2: installs a global uncaught-exception handler that writes the
 * full stack trace to getExternalFilesDir()/crash_logs/crash_<timestamp>.txt
 * so the user can share it after reopening the app. The previous handler
 * is chained so Android's "Vorv has stopped" dialog still shows.
 */
class VorvApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var db: VorvDatabase
        private set
    lateinit var prefs: VorvPrefs
        private set
    lateinit var secrets: SecretsStore
        private set
    lateinit var modelRepository: ModelRepository
        private set

    override fun onCreate() {
        // Install the crash logger FIRST, before anything else can throw.
        installCrashLogger()
        super.onCreate()

        prefs = VorvPrefs(this)
        secrets = SecretsStore(this)
        db = androidx.room.Room.databaseBuilder(
            this,
            VorvDatabase::class.java,
            "vorv.db"
        )
            // Real migrations only — NO destructive fallback (audit H-5).
            .addMigrations(*VorvDatabase.ALL_MIGRATIONS)
            .build()
        modelRepository = ModelRepository(this)

        // One-shot migration: plaintext DataStore API key → encrypted storage (C-5).
        appScope.launch {
            runCatching { secrets.migrateFromLegacy(prefs) }
                .onFailure { android.util.Log.e(TAG, "legacy key migration failed: ${it.message}", it) }
        }
    }

    /**
     * Global crash logger. Writes the full stack trace to a file in the app's
     * external files dir (accessible via Samsung's "My Files" app under
     * Android/data/host.vorv/files/crash_logs/), then chains to the previous
     * handler so the standard "Vorv has stopped" dialog still appears.
     *
     * The crash log is also checked by [MainActivity] on next launch — if a
     * crash log exists, it's displayed on screen with a Share button so the
     * user can send it without needing adb.
     */
    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable)
            } catch (e: Exception) {
                // The crash logger itself must never eat the original crash.
                android.util.Log.e(TAG, "crash logger failed: ${e.message}", e)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val crashDir = File(getExternalFilesDir(null), "crash_logs").apply { mkdirs() }
        val crashFile = File(crashDir, "crash_${timestamp}.txt")

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("========================================")
        pw.println("Vorv crash report")
        pw.println("========================================")
        pw.println("Timestamp: ${Date()}")
        pw.println("App version: ${packageManager.getPackageInfo(packageName, 0).versionName} (${BuildConfig.VERSION_CODE})")
        pw.println("Build type: ${BuildConfig.BUILD_TYPE}")
        pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        pw.println("Thread: ${thread.name} (id=${thread.id})")
        pw.println("Process: ${android.os.Process.myPid()}")
        pw.println("========================================")
        pw.println("Stack trace:")
        pw.println("========================================")
        throwable.printStackTrace(pw)
        pw.println()
        pw.println("========================================")
        pw.println("Caused by chain:")
        pw.println("========================================")
        var cause: Throwable? = throwable.cause
        while (cause != null) {
            pw.println("--- Cause: ${cause.javaClass.name}: ${cause.message} ---")
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        pw.flush()
        crashFile.writeText(sw.toString())

        // Also log to logcat for adb users.
        android.util.Log.e(TAG, "CRASH LOGGED TO: ${crashFile.absolutePath}")
        android.util.Log.e(TAG, sw.toString())
    }

    /**
     * Returns the most recent crash log file, or null if none exists.
     * Called by [MainActivity] to display the crash on next launch.
     */
    fun getLatestCrashLog(): File? {
        val crashDir = File(getExternalFilesDir(null), "crash_logs")
        if (!crashDir.exists()) return null
        return crashDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * Delete a crash log after the user has acknowledged/shared it.
     */
    fun clearCrashLog(file: File) {
        runCatching { file.delete() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            appScope.launch {
                runCatching { modelRepository.onTrimMemory() }
            }
        }
    }

    private companion object {
        const val TAG = "Vorv/Application"
    }
}
