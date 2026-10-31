package host.vorv

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.geniex.sdk.GenieXSdk
import host.vorv.data.KeyStore
import host.vorv.data.VorvDatabase
import host.vorv.llm.ModelManager
import host.vorv.ui.VorvApp
import host.vorv.ui.theme.VorvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Clean up legacy model files from v0.2.0–v0.4.0 (CPU-only llama.cpp era).
        // GenieX uses a different on-disk layout under filesDir/geniex/.
        ModelManager.cleanupLegacyModelsDir(applicationContext)

        // Initialize GenieX SDK once at app start. Must complete before any
        // ModelManager.pull() or LlmWrapper.build() call.
        // Verified API (from bytecode disassembly of GenieXSdk.class):
        //   GenieXSdk.getInstance(): GenieXSdk
        //   .init(context: Context, callback: InitCallback): void
        //   InitCallback { onSuccess(), onFailure(reason: String) }
        GenieXSdk.getInstance().init(this, object : GenieXSdk.InitCallback {
            override fun onSuccess() {
                Log.i(TAG, "GenieX SDK initialized")
                // Initialize the model manager now that the SDK is up.
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { ModelManager.init() }
                        .onFailure { Log.e(TAG, "GenieX ModelManager init failed: ${it.message}", it) }
                }
            }
            override fun onFailure(reason: String) {
                Log.e(TAG, "GenieX SDK init failed: $reason")
            }
        })

        val db = Room.databaseBuilder(
            applicationContext,
            VorvDatabase::class.java,
            "vorv.db"
        ).fallbackToDestructiveMigration().build()

        val keyStore = KeyStore(applicationContext)

        setContent {
            VorvTheme {
                val vm: AgentViewModel = viewModel(factory = AgentViewModel.factory(db, keyStore, applicationContext))
                VorvApp(vm = vm)
            }
        }
    }

    companion object {
        private const val TAG = "Vorv/MainActivity"
    }
}
