package host.vorv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
                VorvApp(vm = vm)
            }
        }
    }

    companion object {
        private const val TAG = "Vorv/MainActivity"
    }
}
