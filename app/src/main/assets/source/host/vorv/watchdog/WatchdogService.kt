package host.vorv.watchdog

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WatchdogService — runs in a SEPARATE PROCESS (android:process=":watchdog").
 *
 * WHY A SEPARATE PROCESS:
 *   When the main app process crashes — even a native SIGSEGV from a GenieX .so
 *   failure — this process is unaffected. It has its own JVM, its own memory
 *   space, its own lifecycle. It can observe the main process dying, document
 *   the crash from OUTSIDE, and orchestrate a clean restart.
 *
 * HOW IT DETECTS CRASHES (audit H-1 — both signals now actually work):
 *   1. BINDER DEATH (primary): the main process BINDS to this service from
 *      MainActivity. When the main process dies, Android drops the binding
 *      and onUnbind() fires here — a crash signal observed from outside,
 *      never racing the kill. onUnbind verifies via isMainProcessRunning()
 *      before declaring death (a normal unbind while the app still runs is
 *      not a crash).
 *   2. STALE HEARTBEAT (fallback): the MAIN process — and only the main
 *      process; VorvApplication's process guard guarantees the watchdog no
 *      longer writes heartbeats itself — refreshes heartbeat.txt every 5s.
 *      If the beat goes stale AND the main process is gone, that's a crash.
 *
 * WHAT IT DOES ON CRASH:
 *   - Writes a crash-detection log to getExternalFilesDir()/watchdog/crash_detected_<ts>.txt
 *   - Checks the main app's boot_trace.txt to capture the last breadcrumb
 *     (this is what tells us WHICH step crashed, even for native SIGSEGV)
 *   - Waits 1.5s (lets any WorkManager crash-report job run)
 *   - Relaunches the main activity (clean restart — user sees app come back,
 *     not "Vorv has stopped")
 *
 * WHAT IT NO LONGER DOES (audit M-8 / H-4): self-healing. v0.6.5 ran
 * runBlocking { healer.heal(...) } ON the watchdog's heartbeat thread —
 * loading a 3B LLM and blocking for up to minutes. The compile pipeline it
 * fed could never work on a stock device anyway (H-4), and the hot-patch
 * path it fed is now fail-closed (C-2). Crash ANALYSIS, if the user asks
 * for it, runs on-demand in the restarted MAIN process via
 * HealingAgent.produceDiagnosticBundle — never here.
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "Vorv/Watchdog"
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val RESTART_DELAY_MS = 1500L

        /**
         * Called by the MAIN process to register a heartbeat. The watchdog
         * treats the presence of a recent heartbeat as "main process is alive".
         * (VorvApplication's process guard ensures this is never called from
         * the watchdog process itself — audit H-1.)
         */
        fun writeHeartbeat(context: Context, step: String) {
            runCatching {
                val file = File(context.getExternalFilesDir(null), "watchdog/heartbeat.txt")
                file.parentFile?.mkdirs()
                val ts = System.currentTimeMillis()
                file.writeText("$ts\n$step\npid=${Process.myPid()}\n")
            }
        }
    }

    private val running = AtomicBoolean(false)
    private lateinit var heartbeatThread: Thread
    private val handlingDeath = AtomicBoolean(false)

    private val binder = object : Binder() {}

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Watchdog process started (pid=${Process.myPid()})")

        running.set(true)
        heartbeatThread = Thread {
            while (running.get()) {
                try {
                    checkMainProcessHealth()
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "heartbeat thread error: ${e.message}", e)
                }
            }
        }.apply { isDaemon = true; name = "vorv-watchdog-heartbeat" }
        heartbeatThread.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Watchdog onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        heartbeatThread.interrupt()
        super.onDestroy()
        Log.w(TAG, "Watchdog process being destroyed")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * BINDER-DEATH CRASH SIGNAL (audit H-1). The main process binds from
     * MainActivity with BIND_AUTO_CREATE; when it dies — crash, kill, OOM —
     * the system tears the binding down and onUnbind fires here (it is
     * called when the LAST client goes away, dead or alive). A plain unbind
     * while the app still runs passes through harmlessly thanks to the
     * isMainProcessRunning() re-check.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Last client unbound — checking main process health (binder-death signal)")
        if (!isMainProcessRunning()) {
            maybeHandleDeath("binder death (client unbind + main process gone)")
        }
        // true => onRebind is invoked if a client re-binds without a stop,
        // which is fine: every unbind re-runs the health check.
        return true
    }

    private fun maybeHandleDeath(detectionMethod: String) {
        if (!handlingDeath.compareAndSet(false, true)) return
        try {
            onMainProcessDied(detectionMethod)
        } finally {
            // Re-arm after the restart delay so a SECOND crash is also caught.
            android.os.Handler(mainLooper).postDelayed({ handlingDeath.set(false) },
                RESTART_DELAY_MS + 5000L)
        }
    }

    private fun onMainProcessDied(detectionMethod: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        Log.e(TAG, "MAIN PROCESS DIED at $ts ($detectionMethod) — capturing diagnostics")

        val bootTrace: String = runCatching {
            File(getExternalFilesDir(null), "boot_trace.txt")
                .takeIf { it.exists() }?.readText() ?: ""
        }.getOrDefault("")

        val heartbeat: String = runCatching {
            File(getExternalFilesDir(null), "watchdog/heartbeat.txt")
                .takeIf { it.exists() }?.readText() ?: ""
        }.getOrDefault("")

        // Read the Java crash log (written by CrashReportWorker) if it exists.
        val crashLog: String = runCatching {
            val cdir = File(getExternalFilesDir(null), "crash_logs")
            cdir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".txt") }
                ?.maxByOrNull { it.lastModified() }
                ?.takeIf { System.currentTimeMillis() - it.lastModified() < 60000 }
                ?.readText() ?: ""
        }.getOrDefault("")

        val crashDir = File(getExternalFilesDir(null), "watchdog").apply { mkdirs() }
        val crashFile = File(crashDir, "crash_detected_${ts}.txt")
        crashFile.writeText(buildString {
            appendLine("========================================")
            appendLine("Watchdog crash detection")
            appendLine("========================================")
            appendLine("Detected at: ${Date()}")
            appendLine("Watchdog pid: ${Process.myPid()}")
            appendLine("Detection method: $detectionMethod")
            appendLine()
            appendLine("========================================")
            appendLine("Last heartbeat from main process:")
            appendLine("========================================")
            appendLine(heartbeat)
            appendLine()
            appendLine("========================================")
            appendLine("Main process boot trace (breadcrumbs):")
            appendLine("========================================")
            appendLine(bootTrace)
            if (crashLog.isNotBlank()) {
                appendLine()
                appendLine("========================================")
                appendLine("Java crash log (from CrashReportWorker):")
                appendLine("========================================")
                appendLine(crashLog)
            }
            appendLine()
            appendLine("========================================")
            appendLine("On-device analysis (optional):")
            appendLine("========================================")
            appendLine("Open Vorv after the restart and tap 'Analyze crash' in the crash")
            appendLine("dialog to run the on-device LLM analysis and produce a diagnostic")
            appendLine("bundle. Patches are never generated or applied automatically.")
        })

        Log.e(TAG, "Crash log written to ${crashFile.absolutePath}")

        // Wait briefly, then relaunch. (Self-heal removed — audit M-8/H-4:
        // the watchdog never loads the LLM; analysis belongs to the restarted
        // main process, on explicit user request.)
        try { Thread.sleep(RESTART_DELAY_MS) } catch (_: InterruptedException) {}

        Log.i(TAG, "Relaunching main activity (clean restart)")
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                startActivity(launchIntent)
                Log.i(TAG, "Main activity relaunch initiated")
            } else {
                Log.e(TAG, "Could not get launch intent for $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to relaunch main activity: ${e.message}", e)
        }
    }

    /**
     * Fallback signal: stale heartbeat + main process actually gone.
     * The heartbeat file is only ever written by the main process since the
     * H-1 fix, so a stale beat is meaningful again.
     */
    private fun checkMainProcessHealth() {
        val heartbeatFile = File(getExternalFilesDir(null), "watchdog/heartbeat.txt")
        if (!heartbeatFile.exists()) return

        val heartbeatText = runCatching { heartbeatFile.readText() }.getOrNull() ?: return
        val lines = heartbeatText.lines()
        val heartbeatTs = lines.firstOrNull()?.toLongOrNull() ?: return

        val ageMs = System.currentTimeMillis() - heartbeatTs
        if (ageMs < 3 * HEARTBEAT_INTERVAL_MS) return

        if (!isMainProcessRunning()) {
            val markerFile = File(getExternalFilesDir(null), "watchdog/.crash_handled_$heartbeatTs")
            if (markerFile.exists()) return
            markerFile.writeText("handled")
            maybeHandleDeath("stale heartbeat (${ageMs / 1000}s old)")
        }
    }

    private fun isMainProcessRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses ?: return false
            processes.any { it.processName == packageName }
        } catch (e: Exception) {
            false
        }
    }
}
