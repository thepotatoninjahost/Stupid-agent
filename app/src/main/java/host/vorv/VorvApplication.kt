package host.vorv

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Process
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import host.vorv.data.SecretsStore
import host.vorv.data.VorvDatabase
import host.vorv.data.VorvPrefs
import host.vorv.llm.ModelRepository
import host.vorv.watchdog.HotPatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application-scoped wiring.
 *
 * AUDIT H-1 FIX — PROCESS GUARD: this Application class runs in EVERY
 * process of the app, including ":watchdog". The audited code ran the full
 * onCreate in the watchdog process too — DB init, model repo, and worst of
 * all the HEARTBEAT WRITER, which poisoned the very heartbeat the watchdog
 * uses to detect main-process death. Now:
 *   - the MAIN process runs the full initialization;
 *   - ":watchdog" (and any other non-main process) runs only the crash
 *     handler and returns — the service lives on its own.
 *   - heartbeats are written exclusively from the main process.
 *
 * AUDIT L-7 FIX — BOOT MARKER FIRST: BootTracer.start() (the separator +
 * BOOT_STARTED breadcrumb) is now the FIRST thing onCreate does, BEFORE the
 * hot-patch step. Previously the hotpatch breadcrumbs were appended to the
 * PREVIOUS boot's section, so a crash during hot-patching was
 * misclassified as a crash of the previous boot.
 *
 * PROBATION (correctness gate): when a previous-boot crash is detected at
 * startup, HotPatcher.quarantineIfCrashed rolls back any patch that
 * overrides the crashed class within its 72h probation window — a fix that
 * makes things worse undoes itself.
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

    /**
     * Set to true by MainActivity if the previous boot crashed (no BOOT_COMPLETE
     * marker) AND the crash count >= 2. When true, GenieX init is skipped so the
     * app can boot without the native lib that's crashing.
     */
    @Volatile
    var safeMode = false
        private set

    /** Patches rolled back this boot by the probation gate (for the UI). */
    @Volatile
    var rolledBackPatches: List<String> = emptyList()
        private set

    override fun onCreate() {
        // L-7: the boot marker must exist BEFORE any step that can crash —
        // including the crash handler install and the hot-patch load.
        BootTracer.start(this)

        // L5 (code review, 2026-11): SECOND, not first — the boot marker
        // above must already exist so a crash in here is attributable to
        // THIS boot. The old comment claimed "first" and had drifted from
        // the code's actual (correct) order.
        installCrashHandler()
        super.onCreate()

        if (isWatchdogProcess()) {
            // H-1: the watchdog process gets NO app initialization. It must
            // not init Room/GenieX, and above all it must not write
            // heartbeats — that poisoned the crash detection for good.
            BootTracer.step(this, "watchdog_process_skip", "pid=${Process.myPid()}")
            Log.i(TAG, "Watchdog process detected — application init skipped")
            return
        }

        // Load verified, user-approved hot-patches BEFORE app code runs.
        // (Honest limitation: already-loaded classes can't be unloaded, so
        // a newly-registered patch only takes effect after a restart. The
        // watchdog orchestrates that restart.)
        BootTracer.step(this, "hotpatch_load_begin")
        val appliedPatches = runCatching { HotPatcher.loadPendingPatches(this) }
            .onFailure { BootTracer.step(this, "hotpatch_load_failed", it.message) }
            .getOrDefault(emptyList())
        BootTracer.step(this, "hotpatch_load_end", "applied=${appliedPatches.size}")

        BootTracer.step(this, "check_previous_boot")
        // Detect crash loop: if the previous boot didn't complete, increment
        // the crash counter. If we're at 2+ crashes, enter safe mode.
        if (!BootTracer.previousBootCompleted(this)) {
            val count = BootTracer.incrementCrashCount(this)
            BootTracer.step(this, "previous_boot_crashed", "count=$count")

            // Probation gate: a patch that was followed by a crash in the
            // class it overrides, within 72h of applying, is rolled back.
            val crashedClass = runCatching {
                val latest = getLatestCrashLog()
                latest?.let { f ->
                    host.vorv.healer.CrashParser.extractCrashedClass(f.readText())
                }
            }.getOrNull()
            rolledBackPatches = runCatching {
                HotPatcher.quarantineIfCrashed(this, crashedClass)
            }.getOrDefault(emptyList())
            if (rolledBackPatches.isNotEmpty()) {
                BootTracer.step(this, "probation_rollback",
                    "patches=${rolledBackPatches.joinToString(",")}")
                Log.w(TAG, "Probation rollback applied: $rolledBackPatches")
            }

            if (count >= 2) {
                safeMode = true
                BootTracer.step(this, "SAFE_MODE_ENABLED", "skipping non-essential init")
            }
        } else {
            BootTracer.step(this, "previous_boot_ok")
        }

        // Start the watchdog service in a separate process. It survives this
        // process's crashes and orchestrates clean restarts.
        BootTracer.step(this, "start_watchdog")
        runCatching {
            val intent = android.content.Intent(this, host.vorv.watchdog.WatchdogService::class.java)
            startService(intent)
        }.onFailure { BootTracer.step(this, "start_watchdog_failed", it.message) }

        // Write initial heartbeat — MAIN process only (H-1).
        host.vorv.watchdog.WatchdogService.writeHeartbeat(this, "app_oncreate")

        BootTracer.step(this, "init_prefs")
        prefs = VorvPrefs(this)
        BootTracer.step(this, "init_secrets")
        secrets = SecretsStore(this)
        BootTracer.step(this, "init_room_begin")
        db = androidx.room.Room.databaseBuilder(
            this,
            VorvDatabase::class.java,
            "vorv.db"
        )
            .addMigrations(*VorvDatabase.ALL_MIGRATIONS)
            .build()
        BootTracer.step(this, "init_room_end")
        BootTracer.step(this, "init_model_repo")
        modelRepository = ModelRepository(this)

        // Start a background heartbeat writer — keeps the watchdog informed
        // that this process is alive and which step it's on.
        startHeartbeatWriter()

        // One-shot migration: plaintext DataStore API key → encrypted storage (C-5).
        appScope.launch {
            runCatching { secrets.migrateFromLegacy(prefs) }
                .onFailure { Log.e(TAG, "legacy key migration failed: ${it.message}", it) }
        }
    }

    /**
     * H-1: true when running in the ":watchdog" (or any non-main) process.
     * Application.getProcessName() is available from API 28; minSdk is 31.
     */
    private fun isWatchdogProcess(): Boolean =
        Application.getProcessName() != packageName

    /**
     * Background thread that writes a heartbeat every 5s. The watchdog reads
     * this to detect when this process has died (stale heartbeat = crash).
     */
    private var heartbeatThread: Thread? = null
    @Volatile private var heartbeatRunning = false

    private fun startHeartbeatWriter() {
        heartbeatRunning = true
        heartbeatThread = Thread {
            while (heartbeatRunning) {
                try {
                    host.vorv.watchdog.WatchdogService.writeHeartbeat(this, "alive")
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) { /* keep going */ }
            }
        }.apply { isDaemon = true; name = "vorv-heartbeat" }
        heartbeatThread?.start()
    }

    /**
     * Global crash handler. For Java/Kotlin exceptions, enqueues a WorkManager
     * job that writes the full trace in a fresh process (survives this process
     * being killed). For native SIGSEGV, this handler doesn't fire — see
     * [BootTracer] breadcrumbs for that case.
     */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                enqueueCrashReport(thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "crash handler failed: ${e.message}", e)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun enqueueCrashReport(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)

        val crashNumber = BootTracer.getCrashCount(this)
        val data = Data.Builder()
            .putString(CrashReportWorker.KEY_THREAD, thread.name)
            .putString(CrashReportWorker.KEY_EXCEPTION_CLASS, throwable.javaClass.name)
            .putString(CrashReportWorker.KEY_EXCEPTION_MESSAGE, throwable.message ?: "")
            .putString(CrashReportWorker.KEY_STACK_TRACE, sw.toString())
            .putInt(CrashReportWorker.KEY_CRASH_NUMBER, crashNumber)
            .build()

        val request = OneTimeWorkRequestBuilder<CrashReportWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(this).enqueue(request)
        Log.e(TAG, "CrashReportWorker enqueued. Thread=${thread.name}, exception=${throwable.javaClass.name}")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            appScope.launch {
                runCatching { modelRepository.onTrimMemory() }
            }
        }
    }

    /**
     * Returns the most recent crash log file (written by CrashReportWorker),
     * or null if none exists. Called by [MainActivity] to display the crash
     * on next launch.
     */
    fun getLatestCrashLog(): java.io.File? {
        val crashDir = java.io.File(getExternalFilesDir(null), "crash_logs")
        if (!crashDir.exists()) return null
        return crashDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * Delete a crash log after the user has acknowledged/shared it.
     */
    fun clearCrashLog(file: java.io.File) {
        runCatching { file.delete() }
    }

    private companion object {
        const val TAG = "Vorv/Application"
    }
}
