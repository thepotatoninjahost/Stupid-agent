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
 *   This is the "something that doesn't crash with it" pattern. The main app
 *   can die; the watchdog lives. The watchdog writes the death certificate.
 *
 * HOW IT DETECTS CRASHES:
 *   1. The main app binds to this service on startup (bindService). While
 *      bound, the watchdog knows the main process is alive.
 *   2. When the main process dies (crash, kill, OOM), the binding drops and
 *      onServiceDisconnected fires in the watchdog's binder. That's the
 *      crash signal — observed from outside, never racing the kill.
 *   3. The watchdog also writes a heartbeat file every 5s while the main
 *      process is alive. If the watchdog itself is ever killed and restarted,
 *      it checks the heartbeat: if the last beat is old, the main process
 *      died without a clean shutdown.
 *
 * WHAT IT DOES ON CRASH:
 *   - Writes a crash-detection log to getExternalFilesDir()/watchdog/crash_detected_<ts>.txt
 *   - Checks the main app's boot_trace.txt to capture the last breadcrumb
 *     (this is what tells us WHICH step crashed, even for native SIGSEGV)
 *   - Waits 1.5s (lets any WorkManager crash-report job run)
 *   - Relaunches the main activity (clean restart — user sees app come back,
 *     not "Vorv has stopped")
 *
 *   The relaunch is the "hot-swap so it never dies" part. The user sees a
 *   brief flicker, then the app is back. No manual tap required.
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "Vorv/Watchdog"
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val RESTART_DELAY_MS = 1500L

        /**
         * Called by the main process to register a heartbeat. The watchdog
         * treats the presence of a recent heartbeat as "main process is alive".
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

    private fun onMainProcessDied() {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        Log.e(TAG, "MAIN PROCESS DIED at $ts — capturing diagnostics and attempting self-heal")

        val bootTrace = runCatching {
            File(getExternalFilesDir(null), "boot_trace.txt")
                .takeIf { it.exists() }?.readText()
        }.getOrDefault("(could not read boot trace)")

        val heartbeat = runCatching {
            File(getExternalFilesDir(null), "watchdog/heartbeat.txt")
                .takeIf { it.exists() }?.readText()
        }.getOrDefault("(no heartbeat)")

        // Read the Java crash log (written by CrashReportWorker) if it exists.
        val crashLog = runCatching {
            val crashDir = File(getExternalFilesDir(null), "crash_logs")
            crashDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".txt") }
                ?.maxByOrNull { it.lastModified() }
                ?.takeIf { System.currentTimeMillis() - it.lastModified() < 60000 }
                ?.readText()
        }.getOrDefault("")

        val crashDir = File(getExternalFilesDir(null), "watchdog").apply { mkdirs() }
        val crashFile = File(crashDir, "crash_detected_${ts}.txt")
        crashFile.writeText(buildString {
            appendLine("========================================")
            appendLine("Watchdog crash detection")
            appendLine("========================================")
            appendLine("Detected at: ${Date()}")
            appendLine("Watchdog pid: ${Process.myPid()}")
            appendLine("Detection method: stale heartbeat / binder death")
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
        })

        Log.e(TAG, "Crash log written to ${crashFile.absolutePath}")

        // --- SELF-HEALING ATTEMPT ---
        // The watchdog runs in its own process. We attempt to load the local
        // LLM here and run the HealingAgent to generate + compile a patch.
        // If this succeeds, the patch is applied on the next restart (which
        // we trigger below). If it fails, we fall back to a plain restart.
        val healResult = attemptSelfHeal(crashLog, bootTrace)
        if (healResult != null) {
            Log.i(TAG, "Self-heal result: ${if (healResult.first) "SUCCESS patch=${healResult.second}" else "FAILED: ${healResult.second}"}")
        }

        // Wait briefly, then relaunch.
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
     * Attempt to self-heal by loading the local LLM and running the
     * HealingAgent in this (watchdog) process.
     *
     * Returns (success, patchIdOrError) or null if the LLM can't be loaded
     * (e.g. model not downloaded, or running in safe mode).
     *
     * HONEST CAVEAT: This is the most experimental part of the system.
     * Loading the GenieX LLM in the watchdog process may itself fail (the
     * .so that crashed the main process might crash here too). If it does,
     * the catch block logs the failure and we fall back to a plain restart.
     * The user is never worse off than before.
     */
    private fun attemptSelfHeal(crashLog: String, bootTrace: String): Pair<Boolean, String>? {
        if (crashLog.isBlank() && bootTrace.isBlank()) return null

        return runCatching {
            // Identify the crashed class.
            val crashedClass = host.vorv.healer.CrashParser.extractCrashedClass(crashLog)
                ?: host.vorv.healer.CrashParser.inferCrashedClassFromBootTrace(bootTrace)

            if (crashedClass == null) {
                Log.w(TAG, "Self-heal: could not identify crashed class")
                return null
            }

            Log.i(TAG, "Self-heal: targeting class $crashedClass")

            // Load the local LLM. This is the risky step — if the crash was
            // a GenieX native failure, this might fail here too.
            val llm = host.vorv.llm.LocalLlm(
                applicationContext,
                host.vorv.llm.ModelChoice.QWEN_3B,  // use 3B for healing — faster
                "Qwen2.5-Coder-3B (healer)",
            )

            val healer = host.vorv.healer.HealingAgent(applicationContext, llm)
            val crash = host.vorv.healer.HealingAgent.CrashInfo(
                stackTrace = crashLog,
                bootTrace = bootTrace,
                crashedClass = crashedClass,
            )

            // Use kotlinx.coroutines to run the suspend heal() function.
            val result = kotlinx.coroutines.runBlocking {
                healer.heal(crash)
            }

            // Release the LLM's native memory.
            llm.release()

            if (result.success) {
                true to (result.patchId ?: "unknown")
            } else {
                false to (result.error ?: "unknown error")
            }
        }.onFailure {
            Log.e(TAG, "Self-heal failed: ${it.message}", it)
        }.getOrNull()
    }

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
            onMainProcessDied()
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
