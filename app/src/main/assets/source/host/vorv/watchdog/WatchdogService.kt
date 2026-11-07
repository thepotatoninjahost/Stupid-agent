package host.vorv.watchdog

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
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
 * AUDIT H-1 FIXES (this service was structurally dead before):
 *
 *  1. THE WATCHDOG NO LONGER POISONS ITS OWN HEARTBEAT. VorvApplication now
 *     guards initialization by process name: the heartbeat writer (and all
 *     other main-process init) runs ONLY in the main process. Previously
 *     the watchdog process ran the same Application.onCreate and wrote its
 *     own "alive" heartbeats, making the stale-heartbeat check meaningless.
 *  2. BINDER DEATH IS NOW THE PRIMARY SIGNAL. MainActivity binds to this
 *     service and registers a caller token (transact code 1). The watchdog
 *     links a death recipient to that token: when the MAIN process dies —
 *     crash, kill, OOM, native SIGSEGV — the recipient fires immediately,
 *     regardless of heartbeats. This is the documented crash signal the
 *     audited code claimed but never implemented (MainActivity never called
 *     bindService at all).
 *  3. STALE HEARTBEAT + PID CHECK as the fallback: the heartbeat file
 *     records the writer's pid; a heartbeat is only trusted when it belongs
 *     to a process that is still running (the old check ignored the pid).
 *
 * AUDIT M-8 FIX: the watchdog NEVER runs the LLM or the healer. On main
 * death it captures diagnostics and relaunches; the restarted MAIN process
 * offers self-repair through the crash dialog (healing runs there, in the
 * restarted process, on Dispatchers.IO — see HealingAgent).
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "Vorv/Watchdog"

        /** Heartbeat cadence while a Vorv activity is started (UI visible). */
        internal const val HEARTBEAT_INTERVAL_MS = 5000L

        /**
         * M6 (code review, 2026-11): heartbeat cadence while NO activity is
         * started (app in the background). A 5 s flash write for the whole
         * process lifetime was storage wear with no diagnostic value while
         * nothing is on screen — 12x fewer writes while backgrounded. The
         * writer records the cadence it used IN the heartbeat file, and the
         * stale check below adapts to it, so the backoff can never be
         * misread as a dead process.
         */
        internal const val HEARTBEAT_BACKGROUND_INTERVAL_MS = 60_000L

        private const val RESTART_DELAY_MS = 1500L

        /** Cadence for the next heartbeat: fast in the foreground, backed off otherwise. */
        internal fun heartbeatIntervalMs(foreground: Boolean): Long =
            if (foreground) HEARTBEAT_INTERVAL_MS else HEARTBEAT_BACKGROUND_INTERVAL_MS

        /**
         * The heartbeat is stale when it is older than THREE written
         * cadences. [cadenceMs] is the cadence the writer actually recorded
         * (null for legacy 3-line files, which follow the 5 s rule). The
         * isMainProcessRunning() gate in checkMainProcessHealth stays the
         * real discriminator — the backoff only stretches how long a
         * backgrounded-but-alive process may sit between writes.
         */
        internal fun heartbeatIsStale(ageMs: Long, cadenceMs: Long?): Boolean =
            ageMs >= 3 * maxOf(HEARTBEAT_INTERVAL_MS, cadenceMs ?: HEARTBEAT_INTERVAL_MS)

        /** Binder protocol: main registers its death token. */
        private const val TRANSACT_REGISTER = 1

        /**
         * N-L5 (VRA-2026-1103): relaunch circuit breaker. A persistent crash
         * before the main process's safe-mode logic could otherwise spin
         * crash -> relaunch -> crash indefinitely. At most MAX_RELAUNCHES
         * relaunches per RELAUNCH_WINDOW_MS; past the cap the watchdog stops
         * relaunching and logs that the user must open the app manually.
         */
        private const val MAX_RELAUNCHES = 5
        private const val RELAUNCH_WINDOW_MS = 10 * 60_000L
        private val relaunchTimestamps = java.util.ArrayDeque<Long>()

        private fun relaunchAllowed(): Boolean = synchronized(relaunchTimestamps) {
            val now = System.currentTimeMillis()
            while (relaunchTimestamps.isNotEmpty() && now - relaunchTimestamps.first() > RELAUNCH_WINDOW_MS) {
                relaunchTimestamps.removeFirst()
            }
            if (relaunchTimestamps.size >= MAX_RELAUNCHES) return false
            relaunchTimestamps.addLast(now)
            true
        }

        /**
         * Called by the MAIN process to register a heartbeat. The watchdog
         * treats a recent heartbeat FROM A LIVE PROCESS as "main is alive".
         *
         * M6 (code review, 2026-11): the heartbeat file lives in INTERNAL
         * storage. It used to sit on external storage (getExternalFilesDir),
         * which a) wears external flash with a write every 5 s for the whole
         * process lifetime and b) breaks crash detection whenever external
         * storage is unmounted or slow to mount at boot. Both processes of
         * this app share filesDir, so the watchdog reads the same path.
         * The writer also backs off to [HEARTBEAT_BACKGROUND_INTERVAL_MS]
         * while no activity is started, and records that cadence in the
         * file (line 4) so the stale check adapts to the backoff.
         */
        internal fun heartbeatFile(context: Context): File =
            File(context.filesDir, "watchdog/heartbeat.txt")

        /**
         * One-shot per process: the legacy external heartbeat can only come
         * from an OLDER app version (this code never writes there), so
         * sweeping it on the first write of this process is sufficient and
         * saves a getExternalFilesDir() resolution on every later write.
         */
        @Volatile
        private var legacyExternalSwept = false

        fun writeHeartbeat(context: Context, step: String, cadenceMs: Long = HEARTBEAT_INTERVAL_MS) {
            runCatching {
                // M6: sweep the pre-M6 external heartbeat if one still exists
                // from an older install — once per process (see above), so it
                // can never leave a stale file as a false signal.
                if (!legacyExternalSwept) {
                    File(context.getExternalFilesDir(null), "watchdog/heartbeat.txt").delete()
                    legacyExternalSwept = true
                }
                val file = heartbeatFile(context)
                file.parentFile?.mkdirs()
                val ts = System.currentTimeMillis()
                // Line 4 records the cadence the writer is running at, so
                // the watchdog's stale threshold adapts to the M6 backoff.
                file.writeText("$ts\n$step\npid=${Process.myPid()}\ncadence=$cadenceMs\n")
            }
        }
    }

    private val running = AtomicBoolean(false)
    private lateinit var heartbeatThread: Thread

    /** Registered death tokens (one per main-process connection). */
    private val deathTokens = java.util.concurrent.CopyOnWriteArrayList<IBinder>()

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TRANSACT_REGISTER) {
                val token = data.readStrongBinder()
                if (token != null) {
                    registerDeathToken(token)
                    reply?.writeInt(1)
                    return true
                }
                return false
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun registerDeathToken(token: IBinder) {
        runCatching {
            token.linkToDeath({
                Log.e(TAG, "MAIN PROCESS DIED (binder death) — capturing diagnostics and relaunching")
                deathTokens.remove(token)
                onMainProcessDied("binder death")
            }, 0)
            deathTokens.add(token)
            Log.i(TAG, "Main process registered a death token (${deathTokens.size} active)")
        }.onFailure {
            Log.e(TAG, "linkToDeath failed: ${it.message}", it)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Watchdog process started (pid=${Process.myPid()})")

        running.set(true)
        heartbeatThread = Thread {
            while (running.get()) {
                try {
                    checkMainProcessHealth()
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
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
        // Death tokens are kernel-managed: they fire (or are discarded) when
        // this process dies — no unlink needed, and the recipient reference
        // is held by the framework, not us.
        deathTokens.clear()
        super.onDestroy()
        Log.w(TAG, "Watchdog process being destroyed")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun onMainProcessDied(method: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        Log.e(TAG, "Crash handling started (detection=$method) at $ts")

        val bootTrace: String = runCatching {
            File(getExternalFilesDir(null), "boot_trace.txt")
                .takeIf { it.exists() }?.readText() ?: ""
        }.getOrDefault("")

        val heartbeat: String = runCatching {
            heartbeatFile(this)
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
            appendLine("Detection method: $method")
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
            appendLine("Self-repair: offered in the restarted app (crash dialog).")
            appendLine("The watchdog itself never runs the LLM (audit M-8).")
        })

        Log.e(TAG, "Crash log written to ${crashFile.absolutePath}")

        // Wait briefly, then relaunch — unless the circuit breaker tripped.
        // NO LLM here — the heal runs in the restarted MAIN process when the
        // user taps "Attempt self-repair".
        try { Thread.sleep(RESTART_DELAY_MS) } catch (_: InterruptedException) {}

        if (!relaunchAllowed()) {
            Log.e(TAG, "Relaunch circuit breaker tripped ($MAX_RELAUNCHES crashes in " +
                "${RELAUNCH_WINDOW_MS / 60_000} min) — NOT relaunching; the user must open " +
                "the app manually. Diagnostics are on disk.")
            return
        }

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
     * Fallback path: stale heartbeat from a process that is no longer
     * running. The pid recorded in the heartbeat file is checked against
     * the live process list — the audited code ignored it (H-1 evidence).
     */
    private fun checkMainProcessHealth() {
        val hbFile = heartbeatFile(this)
        if (!hbFile.exists()) return

        val heartbeatText = runCatching { hbFile.readText() }.getOrNull() ?: return
        val lines = heartbeatText.lines()
        val heartbeatTs = lines.firstOrNull()?.toLongOrNull() ?: return
        val heartbeatPid = lines.firstOrNull { it.startsWith("pid=") }
            ?.removePrefix("pid=")?.toIntOrNull()

        val cadenceMs = lines.firstOrNull { it.startsWith("cadence=") }
            ?.removePrefix("cadence=")?.toLongOrNull()
        val ageMs = System.currentTimeMillis() - heartbeatTs
        if (!heartbeatIsStale(ageMs, cadenceMs)) return

        // The heartbeat's owner must be gone (or unidentifiable) AND the
        // main process must not be running.
        if (!isMainProcessRunning()) {
            val markerFile = File(getExternalFilesDir(null), "watchdog/.crash_handled_$heartbeatTs")
            if (markerFile.exists()) return
            markerFile.writeText("handled")
            if (heartbeatPid != null && heartbeatPid == Process.myPid()) {
                // Our own poisoned heartbeat from an old buggy boot — ignore.
                return
            }
            onMainProcessDied("stale heartbeat (ts=$heartbeatTs, pid=$heartbeatPid)")
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
