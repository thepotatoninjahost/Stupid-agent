package host.vorv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import host.vorv.AgentViewModel
import host.vorv.ui.theme.*
import host.vorv.ui.screen.ChatScreen
import host.vorv.ui.screen.TasksScreen
import host.vorv.ui.screen.MemoryScreen
import host.vorv.ui.screen.ToolsScreen
import host.vorv.ui.screen.FilesScreen
import host.vorv.ui.screen.SettingsScreen

@Composable
fun VorvApp(vm: AgentViewModel) {
    val state by vm.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            AppHeader(busy = state.busy, llmLabel = state.currentLlmLabel)
            Box(modifier = Modifier.weight(1f)) {
                when (state.screen) {
                    "chat" -> ChatScreen(state, vm::send)
                    "tasks" -> TasksScreen(state)
                    "memory" -> MemoryScreen(state, vm::teach)
                    "tools" -> ToolsScreen(state)
                    "files" -> FilesScreen(state, vm::setWorkspaceUri)
                    "settings" -> SettingsScreen(state, vm::setApiKey, vm::setLlmMode)
                }
            }
            BottomNav(
                current = state.screen,
                onChange = vm::setScreen,
            )
        }
    }
}

@Composable
private fun AppHeader(busy: Boolean, llmLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(
                Brush.radialGradient(listOf(Primary, PrimaryDim))
            )
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Vorv", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (busy) "Working…" else llmLabel,
                color = OnSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(50), color = if (busy) Warning.copy(alpha = 0.15f) else Success.copy(alpha = 0.15f)) {
            Text(
                if (busy) "Live" else "Idle",
                color = if (busy) Warning else Success,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun BottomNav(current: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(
        "chat" to "Chat",
        "tasks" to "Tasks",
        "memory" to "Memory",
        "tools" to "Tools",
        "files" to "Files",
        "settings" to "Setup",
    )
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        color = Background,
    ) {
        Row(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEach { (key, label) ->
                val selected = current == key
                TextButton(onClick = { onChange(key) }) {
                    Text(
                        label,
                        color = if (selected) Primary else OnSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
