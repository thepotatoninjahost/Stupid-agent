package host.vorv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
                    "tasks" -> TasksScreen(state, vm::toggleTaskDetails, vm::viewFullOutput)
                    "memory" -> MemoryScreen(state, vm::teach)
                    "tools" -> ToolsScreen(state)
                    "files" -> FilesScreen(state, vm::setWorkspaceUri)
                    "settings" -> SettingsScreen(state, vm::setApiKey, vm::setLlmMode, vm::setLocalModel, vm::downloadLocalModel, vm::deleteLocalModel, vm::clearSetupError, vm::clearAllData)
                }
            }
            BottomNav(
                current = state.screen,
                onChange = vm::setScreen,
            )
        }
    }

    // ---- Consent dialog (audit H-1/L-2/L-3): tools suspend until answered ----
    state.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.respondConfirmation(pending.id, false) },
            title = { Text("Allow this action?", fontWeight = FontWeight.Bold) },
            // N-I4: the complete message is always shown. It scrolls, however
            // long, so nothing the user is asked to approve is ever cut off.
            text = {
                Text(
                    pending.message,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.respondConfirmation(pending.id, true) }) {
                    Text("Allow", color = Primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.respondConfirmation(pending.id, false) }) {
                    Text("Deny", color = Error)
                }
            },
        )
    }

    // ---- Full-output viewer (audit M-7) ----
    state.fullOutputView?.let { view ->
        AlertDialog(
            onDismissRequest = { vm.dismissFullOutput() },
            title = { Text(view.title, fontWeight = FontWeight.Bold, fontSize = 14.sp) },
            text = {
                Text(
                    view.content,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissFullOutput() }) { Text("Close") }
            },
        )
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
        // Constrain the title+subtitle column so a long label like
        // "Local — Qwen2.5-Coder-7B-Instruct not pulled" can't push the
        // Idle/Live pill off the right edge of the screen.
        Column(modifier = Modifier.weight(1f)) {
            Text("Vorv", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (busy) "Working…" else llmLabel,
                color = OnSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
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
