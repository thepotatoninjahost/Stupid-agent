package com.nova.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.nova.agent.data.NovaDatabase
import com.nova.agent.ui.NovaApp
import com.nova.agent.ui.theme.NOVATheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = Room.databaseBuilder(
            applicationContext,
            NovaDatabase::class.java,
            "nova.db"
        ).fallbackToDestructiveMigration().build()

        setContent {
            NOVATheme {
                val vm: NovaViewModel = viewModel(factory = NovaViewModel.factory(db))
                NovaApp(vm = vm)
            }
        }
    }
}
