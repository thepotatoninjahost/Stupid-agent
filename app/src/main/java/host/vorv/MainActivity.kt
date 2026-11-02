package host.vorv

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geniex.sdk.GenieXSdk
import host.vorv.llm.ModelManager
import host.vorv.ui.VorvApp
import host.vorv.ui.theme.VorvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as VorvApplication

        BootTracer.step(this, "mainactivity_oncreate_begin")

        // Check for crash logs from a previous run (written by CrashReportWorker).
        val crashLogFile = app.getLatestCrashLog()
        BootTracer.step(this, "crash_log_check", "found=${crashLogFile != null}")

        // Read the previous boot's trace — if it crashed, we want to show that too.
        val previousTrace = BootTracer.readPreviousTrace(this)
        val previousBootCrashed = !BootTracer.previousBootCompleted(this)
        BootTracer.step(this, "prev_boot_status", "crashed=$previousBootCrashed")

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

                // Load crash log text for the dialog.
                var crashLogText by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(crashLogFile) {
                    crashLogFile?.let { file ->
                        withContext(Dispatchers.IO) {
                            runCatching { file.readText() }.onSuccess { crashLogText = it }
                        }
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
                    VorvApp(vm = vm)

                    // Show crash dialog if we have a crash log from last time.
                    if (crashLogText != null) {
                        CrashLogDialog(
                            crashText = crashLogText!!,
                            onCopy = {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("vorv_crash_log", crashLogText!!))
                                Toast.makeText(this, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onDismiss = {
                                crashLogFile?.let { app.clearCrashLog(it) }
                                crashLogText = null
                            }
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

    companion object {
        private const val TAG = "Vorv/MainActivity"
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
    onCopy: () -> Unit,
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
                Text(
                    text = "Copy this and send it to the developer, then tap Dismiss.",
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
