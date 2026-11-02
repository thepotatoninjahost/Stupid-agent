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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
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

    /**
     * Runtime POST_NOTIFICATIONS request (audit finding H-2). Android 13+
     * treats it as a runtime permission; without it, notify/schedule
     * silently no-op. Asked once per install; the tools also surface an
     * honest error if it's still missing.
     */
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as VorvApplication

        // Check for crash logs from a previous run. If found, display them
        // after the UI is up so the user can copy/share the stack trace.
        val crashLogFile = app.getLatestCrashLog()

        // Clean up legacy model files from v0.2.0–v0.4.0 (CPU-only llama.cpp era).
        // GenieX uses a different on-disk layout under filesDir/geniex/.
        ModelManager.cleanupLegacyModelsDir(applicationContext)

        // Initialize GenieX SDK once at app start. Must complete before any
        // ModelManager.pull() or LlmWrapper.build() call.
        GenieXSdk.getInstance().init(this, object : GenieXSdk.InitCallback {
            override fun onSuccess() {
                Log.i(TAG, "GenieX SDK initialized")
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { ModelManager.init() }
                        .onFailure { Log.e(TAG, "GenieX ModelManager init failed: ${it.message}", it) }
                }
            }
            override fun onFailure(reason: String) {
                Log.e(TAG, "GenieX SDK init failed: $reason")
            }
        })

        // Ask for notification permission once, after the UI is up.
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                // already granted — mark asked so we never nag
                lifecycleScope.launch {
                    app.prefs.markNotifPermissionAsked()
                }
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

        setContent {
            VorvTheme {
                val vm: AgentViewModel = viewModel(factory = AgentViewModel.factory(
                    app.db, app.prefs, app.secrets, app.modelRepository, applicationContext
                ))

                // Crash log dialog — shows on launch if the previous run crashed.
                var crashLogText by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(crashLogFile) {
                    crashLogFile?.let { file ->
                        withContext(Dispatchers.IO) {
                            runCatching { file.readText() }
                                .onSuccess { crashLogText = it }
                        }
                    }
                }

                VorvApp(vm = vm)

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

    companion object {
        private const val TAG = "Vorv/MainActivity"
    }
}

/**
 * Full-screen dialog showing the crash log with Copy and Dismiss buttons.
 * The text is scrollable and monospaced for readability.
 */
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

                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
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
