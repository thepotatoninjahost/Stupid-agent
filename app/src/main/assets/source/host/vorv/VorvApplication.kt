package host.vorv

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import host.vorv.data.SecretsStore
import host.vorv.data.VorvDatabase
import host.vorv.data.VorvPrefs
import host.vorv.llm.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application-scoped wiring (audit findings H-4, L-7, H-1, C-2).
 *  - owns the DB, prefs, secrets and the ModelRepository as singletons
 *    - migrates the legacy plaintext API key into encrypted storage, once
 *  - releases the local LLM's native memory under system memory pressure
 *
 * PROCESS GUARD (audit H-1): Android instantiates this class in EVERY
 * process, including ":watchdog". The watchdog process must run ONLY the
 * WatchdogService — the v0.6.5 behavior of letting it execute the full
 * onCreate (DB, ModelRepository, a heartbeat writer that poisoned the
 * watchdog's own crash detection, a redundant startService of itself)
 * is gone. Heartbeats are written exclusively by the main process now.
 *
 * v0.6.3 crash diagnostics:
 *  - [BootTracer] starts FIRST (audit L-7) so every subsequent breadcrumb
 *    lands in the new boot's section — previously the pre-start steps were
 *    appended to the PREVIOUS boot's section, misclassifying healthy boots
 *    as crashes.
 *  - Global UncaughtExceptionHandler enqueues [CrashReportWorker] via
 *    WorkManager — the worker writes the full Java stack trace to a file in
 *    a FRESH process, so the write isn't racing the process kill
 *  - Crash-loop detection: if the previous boot didn't reach BOOT_COMPLETE,
 *    the crash counter is incremented; MainActivity enters safe mode after
 *    2 crashes (skips GenieX init so the app can at least boot and show
 *    diagnostics)
 *
 * NO HOT-PATCH LOADING AT BOOT (audit C-2): unsigned .dex loading was
 * removed from onCreate entirely. See [host.vorv.watchdog.HotPatcher].
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
     * marker) AND the crash count >= 2. When true, GenieX init is skipped so
     * the app can boot without the native lib that's crashing.
     */
    @Volatile
    var safeMode = false
        private set

    override fun onCreate() {
        // ---- PROCESS GUARD (audit H-1) ---------------------------------
        // The watchdog service runs in a separate process and must NOT get
        // the full app init: no DB, no ModelRepository, no BootTracer, and
        // above all NO heartbeat writer — the watchdog process writing its
        // own "alive" heartbeats made main-process crash detection
        // structurally dead in v0.6.5.
        val processName = getProcessNameSafe()
        if (processName != null && processName != packageName) {
            super.onCreate()
            android.util.Log.i(TAG,
                "Secondary process '$processName' — minimal init (service only)")
            return
        }

        // Install the crash handler FIRST, before anything else can throw.
        installCrashHandler()
        super.onCreate()

        // Start the breadcrumb trace BEFORE any step is written (audit L-7):
        // the separator + BOOT_STARTED marker must open the new boot's
        // section, so pre-init steps can't pollute the previous boot's
        // trace and misclassify a healthy boot as a crash.
        BootTracer.start(this)

        BootTracer.step(this, "check_previous_boot")
        // Detect crash loop: if the previous boot didn't complete, increment
        // the crash counter. If we're at 2+ crashes, enter safe mode.
        if (!BootTracer.previousBootCompleted(this)) {
            val count = BootTracer.incrementCrashCount(this)
            BootTracer.step(this, "previous_boot_crashed", "count=$count")
            if (count >= 2) {
                safeMode = true
                BootTracer.step(this, "SAFE_MODE_ENABLED", "skipping non-essential init")
            }
        } else {
            BootTracer.step(this, "previous_boot_ok")
        }

        // Start the watchdog service in a separate process. It survives this
        // process's crashes and orchestrates clean restarts. MainActivity
        // additionally BINDS to it so binder-death — not just a stale
        // heartbeat — is a crash signal (audit H-1).
        BootTracer.step(this, "start_watchdog")
        runCatching {
            val intent = android.content.Intent(this, host.vorv.watchdog.WatchdogService::class.java)
            startService(intent)
        }.onFailure { BootTracer.step(this, "start_watchdog_failed", it.message) }

        // Write initial heartbeat — the watchdog uses this to detect when
        // this process dies (stale heartbeat = crash). Written ONLY by the
        // main process now (audit H-1).
        host.vorv.watchdog.WatchdogService.writeHeartbeat(this, "app_oncreate")

        // NOTE (audit C-2): HotPatcher.loadPendingPatches() used to run here,
        // loading every .dex in filesDir/hotpatch whose id appeared in
        // applied.json — with NO verification when the manifest lacked an
        // entry. Unsigned dex loading is removed from boot entirely; see
        // HotPatcher for the fail-closed design if patching is ever enabled.
        BootTracer.step(this, "hotpatch_boot_load_removed", "audit C-2")

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
                .onFailure { android.util.Log.e(TAG, "legacy key migration failed: ${it.message}", it) }
        }
    }

    /** Application.getProcessName() (API 28+; minSdk 31 makes it always available). */
    private fun getProcessNameSafe(): String? =
        runCatching { android.app.Application.getProcessName() }.getOrNull()

    /**
     * Background thread that writes a heartbeat every 5s. MAIN PROCESS ONLY
     * (audit H-1) — the watchdog reads this file to detect when the main
     * process has died (stale heartbeat = crash). If the watchdog process
     * wrote heartbeats too, the file would never go stale and crash
     * detection would be structurally dead.
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
                android.util.Log.e(TAG, "crash handler failed: ${e.message}", e)
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
        android.util.Log.e(TAG, "CrashReportWorker enqueued. Thread=${thread.name}, exception=${throwable.javaClass.name}")
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
