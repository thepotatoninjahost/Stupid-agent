package host.vorv

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geniex.sdk.GenieXSdk
import host.vorv.healer.CrashInfo
import host.vorv.healer.CrashParser
import host.vorv.healer.HealerApi
import host.vorv.healer.HealingAgent
import host.vorv.healer.PatchProposal
import host.vorv.llm.ModelManager
import host.vorv.ui.VorvApp
import host.vorv.ui.theme.VorvTheme
import host.vorv.watchdog.PatchRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    /**
     * v0.6.14: the tool-driven runtime permission dialog. When a tool hits
     * the Termux RUN_COMMAND wall, AgentViewModel raises
     * pendingPermissionRequest, VorvApp calls [requestPermission] (below),
     * Android shows the one-tap system dialog, and the result flows back to
     * the suspended tool via respondPermissionRequest. Termux declares the
     * permission protectionLevel="dangerous", so the standard runtime
     * dialog works — no Settings walk required.
     */
    private val toolPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "tool permission dialog result: granted=$granted")
            agentViewModel?.respondPermissionRequest(granted)
        }

    /** Set in setContent; the permission launcher callback needs it. */
    private var agentViewModel: AgentViewModel? = null

    /**
     * AUDIT H-1: MainActivity BINDS to the watchdog service and registers a
     * death token. When THIS process dies — crash, kill, native SIGSEGV — the
     * watchdog's linkToDeath recipient fires immediately. The audited code
     * never called bindService at all, so the documented binder-death signal
     * did not exist; stale heartbeats were the only (self-poisoned) signal.
     */
    private val watchdogConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            runCatching {
                // Register our death token via the binder protocol (code 1).
                val token = Binder()
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeStrongBinder(token)
                    service?.transact(1, data, reply, 0)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
                Log.i(TAG, "Registered death token with the watchdog")
            }.onFailure {
                Log.w(TAG, "Watchdog registration failed: ${it.message} — " +
                    "stale-heartbeat fallback remains active")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The watchdog process died; Android will restart it (START_STICKY).
            Log.w(TAG, "Watchdog service disconnected (it will restart)")
        }
    }

    /** A local binder object used as the death-registration token. */
    private class Binder : android.os.Binder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as VorvApplication

        BootTracer.step(this, "mainactivity_oncreate_begin")

        // H-1: bind to the watchdog and register for binder-death detection.
        BootTracer.step(this, "bind_watchdog")
        runCatching {
            val intent = Intent(this, host.vorv.watchdog.WatchdogService::class.java)
            bindService(intent, watchdogConnection, Context.BIND_AUTO_CREATE)
        }.onFailure { BootTracer.step(this, "bind_watchdog_failed", it.message) }

        // Check for crash logs from a previous run (written by CrashReportWorker).
        val crashLogFile = app.getLatestCrashLog()
        BootTracer.step(this, "crash_log_check", "found=${crashLogFile != null}")

        // Read the previous boot's trace — if it crashed, we want to show that too.
        val previousTrace = BootTracer.readPreviousTrace(this)
        val previousBootCrashed = !BootTracer.previousBootCompleted(this)
        BootTracer.step(this, "prev_boot_status", "crashed=$previousBootCrashed")

        // Probation rollbacks from this boot's VorvApplication (patch gate).
        val rolledBack = app.rolledBackPatches

        // Clean up legacy model files.
        BootTracer.step(this, "cleanup_legacy_models_begin")
        ModelManager.cleanupLegacyModelsDir(applicationContext)
        BootTracer.step(this, "cleanup_legacy_models_end")

        // Initialize GenieX SDK — BUT skip it in safe mode.
        if (app.safeMode) {
            BootTracer.step(this, "geniex_init_SKIPPED", "safe mode")
            Log.w(TAG, "SAFE MODE: skipping GenieX init")
        } else {
            BootTracer.step(this, "geniex_init_begin")
            GenieXSdk.getInstance().init(this, object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                    BootTracer.step(this@MainActivity, "geniex_init_success")
                    Log.i(TAG, "GenieX SDK initialized")
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { ModelManager.init() }
                            .onFailure {
                                BootTracer.step(this@MainActivity, "geniex_modelmgr_init_failed", it.message)
                                Log.e(TAG, "GenieX ModelManager init failed: ${it.message}", it)
                            }
                            .onSuccess {
                                BootTracer.step(this@MainActivity, "geniex_modelmgr_init_ok")
                            }
                    }
                }
                override fun onFailure(reason: String) {
                    BootTracer.step(this@MainActivity, "geniex_init_failed", reason)
                    Log.e(TAG, "GenieX SDK init failed: $reason")
                }
            })
            BootTracer.step(this, "geniex_init_after_call")
        }

        // Ask for notification permission once.
        BootTracer.step(this, "notif_perm_check_begin")
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                lifecycleScope.launch { app.prefs.markNotifPermissionAsked() }
            } else {
                lifecycleScope.launch {
                    val asked = app.prefs.notifPermissionAsked.first()
                    if (!asked) {
                        app.prefs.markNotifPermissionAsked()
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        BootTracer.step(this, "notif_perm_check_end")

        BootTracer.step(this, "setcontent_begin")
        setContent {
            VorvTheme {
                val vm: AgentViewModel = viewModel(factory = AgentViewModel.factory(
                    app.db, app.prefs, app.secrets, app.modelRepository, applicationContext
                ))
                agentViewModel = vm

                // Load crash log text for the dialog.
                var crashLogText by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(crashLogFile) {
                    crashLogFile?.let { file ->
                        withContext(Dispatchers.IO) {
                            runCatching { file.readText() }.onSuccess { crashLogText = it }
                        }
                    }
                }

                // Night session 2026-11-08 (screen recording 20:32): Vorv
                // gets its own crash reports FIRST. The old flow pushed the
                // raw report at the user and waited for a tap; the user's
                // contract is the reverse — "you're supposed to see the
                // crash report, not me; I see it when you're done fixing
                // it." On boot after a crash the agent now takes the job
                // directly: it reads crash_logs, diagnoses, and proposes a
                // self_fix patch if one applies. The dialog below stays as
                // the fallback (safe mode / engine not configured / busy).
                var agentHandlingCrash by remember { mutableStateOf(false) }
                LaunchedEffect(crashLogFile, previousBootCrashed) {
                    if (app.safeMode) return@LaunchedEffect
                    if (crashLogFile == null && !previousBootCrashed) return@LaunchedEffect
                    // Engine readiness (API key / model) loads async from
                    // prefs — give the VM a few seconds before falling back
                    // to the dialog.
                    repeat(5) {
                        if (vm.selfDiagnoseOnBoot(
                                hasJavaReport = crashLogFile?.parentFile?.name == "crash_logs" &&
                                    crashLogFile.name.startsWith("crash_") &&
                                    !crashLogFile.name.startsWith("crash_synthesized_"),
                                bootTrace = previousTrace ?: "",
                                previousBootCrashed = previousBootCrashed,
                                rolledBackPatches = rolledBack,
                            )) {
                            agentHandlingCrash = true
                            return@LaunchedEffect
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                }

                // Safe mode screen OR normal app
                if (app.safeMode) {
                    SafeModeScreen(
                        crashLogText = crashLogText,
                        previousTrace = previousTrace,
                        onCopy = {
                            val full = buildString {
                                crashLogText?.let { appendLine("=== CRASH LOG ==="); appendLine(it); appendLine() }
                                if (previousBootCrashed) {
                                    appendLine("=== PREVIOUS BOOT TRACE (crashed) ===")
                                    appendLine(previousTrace ?: "(none)")
                                }
                                appendLine("=== CURRENT BOOT TRACE ===")
                                appendLine(BootTracer.readTrace(this@MainActivity))
                            }
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("vorv_diagnostics", full))
                            Toast.makeText(this@MainActivity, "Diagnostics copied to clipboard", Toast.LENGTH_LONG).show()
                        },
                        onRetryNormalBoot = {
                            BootTracer.clear(this@MainActivity)
                            // Kill the process so the user gets a fresh start
                            finishAffinity()
                            android.os.Process.killProcess(android.os.Process.myPid())
                        },
                    )
                } else {
                    // v0.6.14: the tool-driven permission dialog is launched
                    // from the composable when a tool requests a grant.
                    VorvApp(vm = vm, onRequestPermission = { permission ->
                        toolPermissionLauncher.launch(permission)
                    })

                    // Show crash dialog if the agent did NOT take over the
                    // diagnosis (fallback path), or report a probation
                    // rollback.
                    if (crashLogText != null && !agentHandlingCrash) {
                        CrashLogDialog(
                            crashText = crashLogText!!,
                            rolledBackPatches = rolledBack,
                            onCopy = {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("vorv_crash_log", crashLogText!!))
                                Toast.makeText(this, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onAnalyze = { analyzeCrash(crashLogText ?: "", previousTrace ?: "") },
                            // L6 (code review, 2026-11): the dialog no longer
                            // passes a fake empty bootTrace — it has no trace of
                            // its own, and the real one is right here in scope.
                            onSelfRepair = {
                                attemptSelfRepair(
                                    crashText = crashLogText ?: "",
                                    bootTrace = previousTrace ?: "",
                                    onConsent = { proposal -> requestPatchConsent(proposal) },
                                    onDone = { ok, message, canRestart ->
                                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                                    },
                                )
                            },
                            onDismiss = {
                                crashLogFile?.let { app.clearCrashLog(it) }
                                crashLogText = null
                            },
                        )
                    }
                }
            }
        }
        BootTracer.step(this, "setcontent_end")

        // Mark boot as complete — the UI is up. If the app crashes after this
        // point, the crash counter resets on next boot.
        BootTracer.complete(this)
    }

    override fun onDestroy() {
        runCatching { unbindService(watchdogConnection) }
        super.onDestroy()
    }

    // ================================================================
    // On-device repair (audits H-4, M-8, C-2; strongest-model per v0.6.9)
    // ================================================================

    /**
     * The consent dialog for a patch proposal (audit C-2). The deferred is
     * local — the dialog answers it; nothing needs a field (audit N-L3,
     * VRA-2026-1103: the old patchConsent field was assigned but never read).
     *
     * N-I4: the body SCROLLS and the text is SELECTABLE, so the complete
     * message is readable and independently checkable (copy the SHA-256,
     * verify it yourself) at ANY length — length is never a reason to cut
     * anything.
     */
    private suspend fun requestPatchConsent(proposal: PatchProposal): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        runOnUiThread {
            val pad = (16 * resources.displayMetrics.density).toInt()
            val body = android.widget.TextView(this).apply {
                text = buildPatchConsentMessage(proposal)
                textSize = 13f
                setTextIsSelectable(true)
                setPadding(pad, pad, pad, pad)
            }
            val scroll = android.widget.ScrollView(this).apply { addView(body) }
            android.app.AlertDialog.Builder(this)
                .setTitle("Apply self-repair patch?")
                .setView(scroll)
                .setPositiveButton("Approve") { _, _ -> deferred.complete(true) }
                .setNegativeButton("Deny") { _, _ -> deferred.complete(false) }
                .setOnCancelListener { deferred.complete(false) }
                .show()
        }
        return runCatching { deferred.await() }.getOrDefault(false)
    }

    /**
     * On-device crash ANALYSIS (audit H-4): runs the healer in THIS (main)
     * process, on explicit user request, and writes a diagnostic bundle.
     * It never compiles or applies anything — the output is a report for the
     * user. The watchdog no longer runs the LLM itself (audit M-8).
     */
    private fun analyzeCrash(crashText: String, bootTrace: String) {
        if (crashText.isBlank()) return
        val app = application as VorvApplication
        if (app.safeMode) {
            Toast.makeText(this, "Safe mode — GenieX is not initialized, analysis unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        // N-L8 (VRA-2026-1103): refuse when a chat task is running — the
        // repair builds its own native engine; running it next to the
        // task's engine doubles NPU memory for no benefit.
        if (app.modelRepository.busy) {
            Toast.makeText(this, "A task is running — wait for it to finish before on-device analysis.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Analyzing crash on-device…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Strongest-first repair model (v0.6.9): analysis runs on the
                // best coder model on the device — 7B when downloaded.
                val repairModel = ModelManager.bestRepairModel()
                if (repairModel == null) {
                    toastMain("No coder model is downloaded — analysis needs one (Setup tab).")
                    return@launch
                }
                // M-3 (code review, 2026-11): engine acquisition through the
                // repository — single H-4 ledger, and REUSE of the resident
                // engine when it already is the strongest repair model (no
                // second native instance on the NPU).
                val handle = app.modelRepository.acquireRepairEngine()
                val repairLlm = handle.llm
                if (repairLlm == null) {
                    toastMain("No coder model is downloaded — analysis needs one (Setup tab).")
                    return@launch
                }
                val bundle = try {
                    healAgent(repairLlm).produceDiagnosticBundle(crashText, bootTrace)
                } finally {
                    if (handle.owned) repairLlm.release()
                }
                toastMain("Analysis saved to ${bundle.bundleDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "crash analysis failed: ${e.message}", e)
                toastMain("Analysis failed: ${e.message}")
            }
        }
    }

    /**
     * Construct the healer through [PatchRegistry] FIRST (a verified
     * patch may override the healer itself — the heal-the-healer case),
     * falling back to the original on any failure. Dispatched as
     * [HealerApi] — the shared interface a patched healer can actually
     * be used through.
     */
    private fun healAgent(llm: host.vorv.llm.Llm): HealerApi =
        PatchRegistry.create(
            HealerApi::class.java,
            HealingAgent::class.java.name,
            applicationContext, llm,
        ) ?: HealingAgent(applicationContext, llm)

    /**
     * SELF-REPAIR (audits C-2, H-4, M-8; strongest model per v0.6.9):
     * generate a fix with the on-device LLM, compile it in Termux through
     * the workspace round-trip, verify completeness, sign it, and — via
     * [onConsent] — put the APPROVE/DENY decision in the user's hands
     * before anything is registered. Runs in the MAIN process only; the
     * watchdog stays LLM-free (M-8).
     */
    private fun attemptSelfRepair(
        crashText: String,
        bootTrace: String,
        onConsent: suspend (PatchProposal) -> Boolean,
        onDone: (ok: Boolean, message: String, canRestart: Boolean) -> Unit,
    ): Job {
        val app = application as VorvApplication
        return lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (app.safeMode) {
                    onDone(false, "Safe mode is active — GenieX is not initialized, " +
                        "self-repair unavailable. Use 'Retry normal boot' first.", false)
                    return@launch
                }
                // N-L8 (VRA-2026-1103): refuse while a chat task is running —
                // the repair engine plus the task engine would be two native
                // GenieX instances on the NPU at once.
                if (app.modelRepository.busy) {
                    onDone(false, "A task is running — wait for it to finish before " +
                        "self-repair. Nothing was changed.", false)
                    return@launch
                }
                // Strongest-first repair model (v0.6.9): patches are written
                // by the best coder model on the device — the 7B whenever it
                // is downloaded; the 3B only when the 7B is absent.
                val repairModel = ModelManager.bestRepairModel()
                if (repairModel == null) {
                    onDone(false, "No coder model is downloaded — self-repair needs the " +
                        "strongest on-device coder model (the 7B; Setup tab). " +
                        "Nothing was changed.", false)
                    return@launch
                }
                val workspaceUri = app.prefs.workspaceUri.first()
                if (workspaceUri.isBlank()) {
                    onDone(false, "No workspace granted. The compile round-trips through " +
                        "the workspace folder (Files tab) — grant one on primary local " +
                        "storage and try again. Nothing was changed.", false)
                    return@launch
                }

                // M-3 (code review, 2026-11): engine acquisition through the
                // repository — single H-4 ledger, and REUSE of the resident
                // engine when it already is the strongest repair model (no
                // second native instance on the NPU).
                val handle = app.modelRepository.acquireRepairEngine()
                val repairLlm = handle.llm
                if (repairLlm == null) {
                    onDone(false, "No coder model is downloaded — self-repair needs the " +
                        "strongest on-device coder model (the 7B; Setup tab). " +
                        "Nothing was changed.", false)
                    return@launch
                }
                val result = try {
                    val crash = CrashInfo(
                        stackTrace = crashText,
                        bootTrace = bootTrace,
                        crashedClass = CrashParser.extractCrashedClass(crashText),
                    )
                    healAgent(repairLlm).heal(crash, workspaceUri, onConsent)
                } finally {
                    if (handle.owned) repairLlm.release()
                }

                if (result.success) {
                    onDone(
                        true,
                        "Patch ${result.patchId} for ${result.targetClass} was generated " +
                            "with ${repairModel.displayName.substringBefore("(").trim()}, " +
                            "compiled, verified, signed and APPROVED. It loads at the next " +
                            "start (signature + hash + version + consent verified). " +
                            "Restart to apply it.",
                        true,
                    )
                } else {
                    val denied = !result.userApproved && result.error == "user denied the patch"
                    onDone(
                        false,
                        if (denied) {
                            "You DENIED the patch — nothing was changed."
                        } else {
                            "Self-repair did not produce an applicable patch " +
                                "(attempts: ${result.attempts}): ${result.error ?: "unknown error"}"
                        },
                        false,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "self-repair failed: ${e.message}", e)
                onDone(false, "Self-repair failed: ${e.message}", false)
            }
        }
    }

    private fun toastMain(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "Vorv/MainActivity"

        /**
         * The crash-repair patch consent text (audit C-2; VRA-2026-1103
         * N-I4 follow-up: the COMPLETE 64-char SHA-256 and the COMPLETE
         * description are shown — never a 16-char prefix, never a 400-char
         * cap, no ellipsis). Pinned by ConsentPromptTest.
         */
        internal fun buildPatchConsentMessage(p: PatchProposal): String = buildString {
            appendLine("Vorv wants to apply a self-repair patch:")
            appendLine("• Class: ${p.targetClass}")
            appendLine("• Patch: ${p.patchId}")
            appendLine("• SHA-256: ${p.sha256}")
            appendLine("• Verified: ${if (p.surfaceVerified) "full public API kept" else "NOT verified"}")
            appendLine()
            appendLine(p.description)
            appendLine()
            append("APPROVE = the patched class replaces the original at the next app start. ")
            append("DENY = nothing changes. A crash in the patched class within 72h rolls it back automatically.")
        }
    }
}

/**
 * Safe mode screen — shown when the app has crashed 2+ times on launch.
 * Skips GenieX/normal UI and shows diagnostics + Copy + Retry buttons.
 */
@Composable
private fun SafeModeScreen(
    crashLogText: String?,
    previousTrace: String?,
    onCopy: () -> Unit,
    onRetryNormalBoot: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Vorv Safe Mode",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "The app crashed on launch 2+ times. GenieX (the AI engine) " +
                    "has been skipped so you can access diagnostics. Copy the text " +
                    "below and send it to the developer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                    Text("Copy diagnostics")
                }
                OutlinedButton(
                    onClick = onRetryNormalBoot,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Retry normal boot")
                }
            }

            Text(
                text = "Crash log:",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Text(
                text = crashLogText ?: "(no Java crash log — the crash may be native)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
            )

            if (previousTrace != null) {
                Text(
                    text = "Previous boot trace (breadcrumbs):",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Text(
                    text = previousTrace,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
            }

            Text(
                text = "Current boot trace:",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            val ctx = LocalContext.current
            Text(
                text = BootTracer.readTrace(ctx),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun CrashLogDialog(
    crashText: String,
    rolledBackPatches: List<String>,
    onCopy: () -> Unit,
    onAnalyze: () -> Unit,
    onSelfRepair: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Vorv crashed last time",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (rolledBackPatches.isNotEmpty()) {
                    Text(
                        text = "Rolled back by the probation gate: " +
                            "${rolledBackPatches.joinToString()} — a patch was followed " +
                            "by a crash in the class it overrides.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    text = "Copy the log for a report, analyze it on-device, or let " +
                        "Vorv attempt a self-repair patch (you approve the result).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    items(crashText.split("\n")) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onAnalyze, modifier = Modifier.weight(1f)) {
                        Text("Analyze")
                    }
                    Button(
                        onClick = onSelfRepair,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Self-repair")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss")
                    }
                    Button(
                        onClick = onCopy,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("Copy")
                    }
                }
            }
        }
    }
}
