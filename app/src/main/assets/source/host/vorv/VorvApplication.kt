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

    /**
     * Did the session before this launch actually crash? Decided once, at boot,
     * from evidence — see [host.vorv.healer.DeathEvidence]. The crash dialog
     * reads this instead of asking "did the last boot finish?".
     */
    @Volatile
    var lastExitCrashed: Boolean = false
        private set

    /** What the evidence said, in one line (null when the last boot finished). */
    @Volatile
    var lastExitVerdict: String? = null
        private set

    /**
     * What Vorv already did about a proven crash this boot, in plain words
     * (null when nothing had to be done). See [host.vorv.healer.CrashRepair].
     */
    @Volatile
    var lastRepairSummary: String? = null
        private set

    /**
     * True when the previous boot died inside GenieX/native init: this boot
     * runs without it once, instead of crashing the same way again.
     */
    @Volatile
    var suppressNativeThisBoot: Boolean = false
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
        // An unfinished boot is only a crash when something independent says
        // so (Android's exit history, a Java report, or the watchdog). The old
        // rule treated every unfinished boot as a crash, so closing the app or
        // Android reclaiming memory produced "Vorv crashed last time" on the
        // next launch — a report about a session that never crashed.
        val previousBootFinished = BootTracer.previousBootCompleted(this)
        val death = if (previousBootFinished) null
        else runCatching { host.vorv.healer.DeathEvidence.of(this, BootTracer.previousBootStartEpoch(this)) }
            .getOrNull()
        lastExitCrashed = death?.crashed == true
        lastExitVerdict = death?.detail
        if (!previousBootFinished && lastExitCrashed) {
            val count = BootTracer.incrementCrashCount(this)
            BootTracer.step(this, "previous_boot_crashed", "count=$count evidence=${death!!.detail}")

            // Night session 2026-11-08 (screen recording 20:32 — "this time
            // no crash report or notification"): a native crash or process
            // kill fires NO Java handler, so CrashReportWorker writes
            // nothing and the crash is invisible to the user, the dialog,
            // the probation gate, and the agent. Before anything reads
            // crash evidence this boot, SYNTHESIZE a report from what does
            // exist — the previous boot's breadcrumbs and any watchdog
            // detection — so the crash is visible to every consumer with
            // one honest artifact that says what it is.
            BootTracer.step(this, "synthesize_native_report_begin")
            runCatching { synthesizeNativeCrashReportIfMissing() }
                .onFailure { BootTracer.step(this, "synthesize_native_report_failed", it.message) }
            BootTracer.step(this, "synthesize_native_report_end")

            // Probation gate: a patch that was followed by a crash in the
            // class it overrides, within 72h of applying, is rolled back.
            val crashedClass = runCatching {
                val latest = getLatestCrashLog()
                latest?.let { f ->
                    host.vorv.healer.CrashParser.extractCrashedClass(f.readText())
                        // No Java frame? Fall back to the breadcrumbs — the
                        // last step before death names the culprit class.
                        ?: host.vorv.healer.CrashParser.inferCrashedClassFromBootTrace(f.readText())
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

            // Evidence is in hand: do the repairs that need no judgement.
            // A crash report nobody acted on was the complaint; the probation
            // quarantine above already removed the patch that caused it, and
            // a death inside native init is not retried blindly on this boot.
            val repair = host.vorv.healer.CrashRepair.plan(
                prevTrace = BootTracer.readPreviousTrace(this),
                rolledBackPatches = rolledBackPatches,
            )
            suppressNativeThisBoot = repair.suppressNativeInit
            lastRepairSummary = runCatching {
                host.vorv.healer.CrashRepair.record(this, death, repair)
            }.getOrDefault(repair.actions.joinToString(" "))
            BootTracer.step(
                this,
                "crash_repair",
                "suppress_native=${repair.suppressNativeInit} actions=${repair.actions.size}",
            )
        } else if (previousBootFinished) {
            BootTracer.step(this, "previous_boot_ok")
        } else {
            // Nothing says it crashed: say exactly that, and take any stale
            // synthesized report out of the dialog's way (the file stays).
            BootTracer.step(this, "previous_exit_not_a_crash", death?.detail ?: "no evidence found")
            runCatching { retireSynthesizedReports() }
        }

        // Start the watchdog service in a separate process. It survives this
        // process's crashes and orchestrates clean restarts.
        //
        // M-6 (code review, 2026-11): on Android 8+ a BACKGROUND boot
        // (WorkManager waking the app for a reminder/crash report, not a
        // user tap) makes plain startService() throw IllegalStateException —
        // the old runCatching swallowed it and the watchdog silently never
        // started for that whole process lifetime. Now the failure falls
        // back to startForegroundService() with the promote flag: the
        // service briefly satisfies the foreground contract, demotes itself
        // and keeps watching (full protection on 8–11 background boots; on
        // 12+ without an FGS exemption the promote is refused and the next
        // interactive boot re-establishes the watchdog — MainActivity's
        // bindService creates the watchdog process whenever the UI opens).
        // The watchdog is never REMOVED to "fix" this — it is upgraded to
        // survive the exact boots it used to miss.
        BootTracer.step(this, "start_watchdog")
        runCatching {
            val intent = android.content.Intent(this, host.vorv.watchdog.WatchdogService::class.java)
            try {
                startService(intent)
            } catch (bg: IllegalStateException) {
                // Background-start restriction exists only on API 26+ —
                // being in this catch implies it, but guard explicitly for
                // lint and older devices that throw it spuriously.
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    BootTracer.step(this, "start_watchdog_bg", "retrying via foreground service")
                    val promoted = android.content.Intent(this, host.vorv.watchdog.WatchdogService::class.java)
                        .putExtra(host.vorv.watchdog.WatchdogService.EXTRA_FGS_PROMOTE, true)
                    startForegroundService(promoted)
                } else {
                    throw bg
                }
            }
        }.onFailure { BootTracer.step(this, "start_watchdog_bg_blocked", it.message) }

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
        // Step 9: a training run stays queued until the phone is charging and
        // idle. WorkManager owns the wait, so a dead session never leaves a
        // half-written generation behind.
        runCatching { host.vorv.learn.TrainingWorker.enqueue(this, chargingOnly = true) }
            .onFailure { android.util.Log.w("Vorv/Learn", "could not queue training: ${it.message}") }
        BootTracer.step(this, "init_model_repo")
        modelRepository = ModelRepository(this)

        // M6 (code review, 2026-11): track whether any activity is started —
        // the heartbeat backs off while the app sits in the background.
        registerActivityLifecycleCallbacks(foregroundTracker.lifecycleCallbacks())

        // Start a background heartbeat writer — keeps the watchdog informed
        // that this process is alive and which step it's on. Fast (5 s)
        // while the UI is visible, backed off (60 s) otherwise.
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
     * Background thread that writes a heartbeat at the FOREGROUND cadence
     * (5 s) or the BACKGROUND cadence (60 s — M6 backoff: no flash writes
     * every 5 s for the whole process lifetime while nothing is on screen).
     * The watchdog reads this to detect when this process has died (stale
     * heartbeat = crash); the written cadence line lets it adapt the stale
     * threshold, so the backoff can never read as a death. The sleep is
     * sliced (1 s) so a foreground/background flip changes the cadence
     * within a second — without ever writing faster than the current
     * cadence allows.
     */
    private var heartbeatThread: Thread? = null
    @Volatile private var heartbeatRunning = false

    /** M6: foreground/background state driving the heartbeat cadence. */
    private val foregroundTracker = ForegroundTracker()

    private fun startHeartbeatWriter() {
        heartbeatRunning = true
        heartbeatThread = Thread {
            while (heartbeatRunning) {
                try {
                    val cadence = host.vorv.watchdog.WatchdogService
                        .heartbeatIntervalMs(foregroundTracker.isForeground)
                    host.vorv.watchdog.WatchdogService.writeHeartbeat(this, "alive", cadence)
                    var remaining = cadence
                    while (remaining > 0 && heartbeatRunning) {
                        val slice = if (remaining < 1000) remaining else 1000
                        Thread.sleep(slice)
                        remaining -= slice
                        if (host.vorv.watchdog.WatchdogService
                                .heartbeatIntervalMs(foregroundTracker.isForeground) != cadence) break
                    }
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
     * Returns the most recent crash evidence file, or null if none exists.
     *
     * Order of preference (night session 2026-11-08):
     *   1. newest report in crash_logs/ — a real Java crash log from
     *      CrashReportWorker, or a synthesized native-crash report;
     *   2. newest watchdog/crash_detected_*.txt — the watchdog's own
     *      detection (heartbeat + boot trace). This fallback catches the
     *      mid-session native crash: the app died after boot completed, the
     *      watchdog wrote its file and relaunched, the new boot "completed"
     *      cleanly — so nothing else would ever surface it.
     *
     * Called by [MainActivity] to display the crash on next launch, by the
     * probation gate, and indirectly by the agent's crash_logs tool.
     */
    fun getLatestCrashLog(): java.io.File? {
        val extDir = getExternalFilesDir(null) ?: return null
        val crashDir = java.io.File(extDir, "crash_logs")
        val newestReport = crashDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") && !it.name.startsWith("handled_") }
            ?.maxByOrNull { it.lastModified() }
        if (newestReport != null) return newestReport

        val wdDir = java.io.File(extDir, "watchdog")
        return wdDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash_detected") && it.name.endsWith(".txt") && !it.name.startsWith("handled_") }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * Night session 2026-11-08: after the AGENT has taken a crash through
     * selfDiagnoseOnBoot and reported, rename the newest evidence file with
     * a `handled_` prefix — the raw-report dialog must not resurface a crash
     * the agent already investigated on a later boot. The file STAYS on disk
     * (evidence is never destroyed); the crash_logs tool still lists and
     * reads it; only getLatestCrashLog (dialog + probation gate) skips it.
     */
    /**
     * A synthesized report describes an unfinished boot as a crash. When this
     * boot found no evidence of a crash, that report must not be offered as
     * one again — it is renamed `handled_`, so it stays on disk as history but
     * leaves the dialog and the probation gate alone.
     */
    private fun retireSynthesizedReports() {
        val dir = java.io.File(getExternalFilesDir(null) ?: return, "crash_logs")
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash_synthesized_") }
            ?.forEach { f ->
                runCatching { f.renameTo(java.io.File(dir, "handled_${f.name}")) }
            }
    }

    fun markCrashHandled(file: java.io.File) {
        if (!file.isFile || file.name.startsWith("handled_")) return
        runCatching {
            val renamed = java.io.File(file.parentFile, "handled_${file.name}")
            file.renameTo(renamed)
        }
    }

    /**
     * A native crash (SIGSEGV in GenieX/NPU) or an external process kill
     * fires no Java handler — CrashReportWorker never runs, and the crash
     * used to leave NO artifact anywhere the app looks. This synthesizes
     * one from the evidence that DOES survive: the previous boot's
     * breadcrumb trace and the newest watchdog detection (if any).
     *
     * The report says plainly what it is — no Java stack exists — so the
     * crash dialog, the agent's crash_logs tool, and any future reader
     * know exactly how much to trust it.
     *
     * Rules: a REAL report from CrashReportWorker stays authoritative (a
     * Java crash wrote its own log moments before death — never duplicate
     * it). A previous SYNTHESIZED report is replaced by this boot's (crash
     * loops would otherwise stack synthesized files). Known edge, kept
     * honestly: a stale real report from an OLD crash also suppresses
     * synthesis — pre-existing behavior for the dialog, not made worse
     * here.
     */
    private fun synthesizeNativeCrashReportIfMissing() {
        val extDir = getExternalFilesDir(null) ?: return
        val crashDir = java.io.File(extDir, "crash_logs").apply { mkdirs() }

        val existing = crashDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") && !it.name.startsWith("handled_") }
            ?.maxByOrNull { it.lastModified() }

        if (existing != null && !existing.name.startsWith("crash_synthesized_")) {
            // A real Java crash report exists — it is authoritative for the
            // crash that produced it. Do not write a second version of the
            // same story.
            return
        }

        // Replace a stale synthesized report from an earlier crash-loop
        // iteration instead of stacking a new one on top.
        existing?.takeIf { it.name.startsWith("crash_synthesized_") }?.let {
            runCatching { it.delete() }
        }

        // REAL evidence first: Android records every exit of this app,
        // native crashes and ANRs included, and hands back the tombstone for
        // a debuggable build. Ask the OS before inventing an explanation from
        // breadcrumbs — the old builds only guessed, and their reports said
        // "unknown" for exactly that reason.
        val real = runCatching { host.vorv.healer.NativeCrashHistory.latestCrash(this) }.getOrNull()
        if (real != null) {
            val realFile = java.io.File(crashDir, "crash_native_${real.timestampMs}.txt")
            if (realFile.isFile) return // already written for this exit
            runCatching {
                existing?.takeIf { it.name.startsWith("crash_synthesized_") }?.delete()
                realFile.writeText(host.vorv.healer.NativeCrashHistory.format(real))
            }.onSuccess {
                BootTracer.step(this, "native_report_from_os", "file=${realFile.name} reason=${real.reasonName}")
                Log.w(TAG, "Real crash report from the OS: ${realFile.absolutePath}")
            }.onFailure {
                BootTracer.step(this, "native_report_from_os_failed", it.message)
            }
            if (realFile.isFile) return
        } else {
            BootTracer.step(this, "native_history_empty", "no crash in Android's exit history")
        }

        val prevTrace = BootTracer.readPreviousTrace(this)
        val watchdogReport = java.io.File(extDir, "watchdog").listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash_detected") && it.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }

        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd_HH-mm-ss-SSS", java.util.Locale.US
        ).format(java.util.Date())
        val outFile = java.io.File(crashDir, "crash_synthesized_${timestamp}_pid${android.os.Process.myPid()}.txt")

        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("Vorv crash report (SYNTHESIZED — no Java stack trace)")
        sb.appendLine("========================================")
        sb.appendLine("Synthesized at: ${java.util.Date()}")
        sb.appendLine("What says this was a crash: " + (lastExitVerdict ?: "unknown"))
        sb.appendLine("Reason: the previous boot did not complete and it was ended by a")
        sb.appendLine("crash, but no Java crash report was written for it (a native crash")
        sb.appendLine("writes no Java log). This report is only produced when that")
        sb.appendLine("evidence exists — an unfinished boot on its own is not a crash.")
        sb.appendLine("There is NO exception class, NO stack trace, and NO line number")
        sb.appendLine("below — the breadcrumbs are all that survived. Treat any")
        sb.appendLine("diagnosis as inference, not fact.")
        sb.appendLine("========================================")
        sb.appendLine("Previous boot trace (breadcrumbs, last steps before death):")
        sb.appendLine("========================================")
        sb.appendLine(
            (prevTrace ?: "(no previous trace available)")
                .let { if (it.length > BootTracer.EMBED_BYTES) "…(older lines dropped)\n" + it.takeLast(BootTracer.EMBED_BYTES) else it }
        )
        sb.appendLine()
        if (watchdogReport != null && watchdogReport.isFile) {
            sb.appendLine("========================================")
            sb.appendLine("Watchdog detection (embedded verbatim, may repeat the trace):")
            sb.appendLine("========================================")
            sb.appendLine(
                runCatching { BootTracer.tailOf(watchdogReport, BootTracer.EMBED_BYTES) }
                    .getOrDefault("(watchdog report unreadable: path=${watchdogReport.name})")
            )
        } else {
            sb.appendLine("No watchdog detection on disk — the watchdog may have died too,")
            sb.appendLine("or the crash occurred before the watchdog registered the process.")
        }
        outFile.writeText(sb.toString())
        BootTracer.step(this, "native_report_synthesized", "file=${outFile.name}")
        Log.w(TAG, "Synthesized native-crash report: ${outFile.absolutePath}")
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
