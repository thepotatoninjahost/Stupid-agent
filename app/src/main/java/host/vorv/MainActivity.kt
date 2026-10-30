package host.vorv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import host.vorv.data.KeyStore
import host.vorv.data.VorvDatabase
import host.vorv.ui.VorvApp
import host.vorv.ui.theme.VorvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
}
