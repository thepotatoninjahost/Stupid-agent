package host.vorv.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import host.vorv.AgentState
import host.vorv.ChatMessage
import host.vorv.kernel.TraceStep
import host.vorv.ui.theme.*

@Composable
fun ChatScreen(state: AgentState, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    Text(
                        "Vorv — local-first agent.\nPick a task or ask anything. I'll plan, call real tools, observe, and repeat until done.",
                        color = OnSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(state.messages, key = { it.id }) { msg -> MessageBubble(msg) }
            if (state.busy && state.liveTrace.isNotEmpty()) {
                item { TraceCard(state.liveTrace.takeLast(4)) }
            }
        }
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Message Vorv…", color = OnSurfaceVariant) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Border,
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { if (input.isNotBlank()) { onSend(input); input = "" } },
                enabled = input.isNotBlank() && !state.busy,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(50),
            ) { Icon(Icons.Default.ArrowUpward, contentDescription = "Send") }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    // Detect [uncited] prefix on assistant answers — kernel tags facts with no tool/citation backing.
    val uncited = !isUser && msg.content.startsWith("[uncited]")
    val body = if (uncited) msg.content.removePrefix("[uncited]").trim() else msg.content
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) Primary else if (uncited) Warning.copy(alpha = 0.10f) else SurfaceVariant,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(body, color = if (isUser) OnPrimary else OnBackground, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (uncited) {
                        Surface(shape = RoundedCornerShape(50), color = Warning.copy(alpha = 0.25f)) {
                            Text(
                                "uncited — no tool evidence",
                                color = Warning,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    msg.citations.forEach { c ->
                        Surface(shape = RoundedCornerShape(50), color = Citation.copy(alpha = 0.2f)) {
                            Text(
                                "[${c.type}] ${c.label}",
                                color = Citation,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceCard(steps: List<TraceStep>) {
    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Reasoning", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            steps.forEach { s ->
                val label = when (s) {
                    is TraceStep.Thinking -> "💭 ${s.label}"
                    is TraceStep.ToolStart -> "🔧 iter ${s.iteration}: ${s.tool}"
                    is TraceStep.ToolEnd -> if (s.result.ok) "✓ ${s.tool} (${s.result.durationMs}ms)" else "✗ ${s.tool}: ${s.result.error}"
                    is TraceStep.Answer -> "✓ Answer ready"
                    is TraceStep.Error -> "✗ ${s.message}"
                    is TraceStep.Done -> "🏁 ${s.summary}"
                    is TraceStep.Telemetry -> "⚡ ${"%.2f".format(s.tokensPerSecond)} tok/s · ${s.generateMs}ms · ${s.engine}"
                }
                Text(label, color = OnBackground, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}
