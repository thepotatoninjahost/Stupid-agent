package host.vorv.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import host.vorv.AgentState
import host.vorv.ui.theme.*

@Composable
fun ToolsScreen(state: AgentState) {
    val available = listOf(
        "memory_write" to "Persist a fact for future sessions",
        "memory_recall" to "FTS-search long-term memory",
        "js_exec" to "Run JavaScript in a Rhino sandbox (3s)",
        "file_read" to "Read a file from granted workspace",
        "file_write" to "Write/overwrite a file in workspace",
        "file_list" to "Recursive list of workspace files",
        // Phase 2+ — not yet wired:
        "python_exec" to "[pending] Chaquopy Python interpreter",
        "shell" to "[pending] Bash via Termux:RUN intent",
        "web_search" to "[pending] Z.ai web_search / DDG fallback",
        "web_fetch" to "[pending] Fetch URL + extract readable text",
        "browser_automate" to "[pending] In-app WebView + JS injection",
        "app_intent" to "[pending] Send Android intents",
        "notify" to "[pending] Post system notification",
        "schedule" to "[pending] WorkManager delayed task",
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Tools", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item { Text("Real tools the agent can call. Stubbed tools are marked [pending] — coming in next pushes.", color = OnSurfaceVariant, fontSize = 12.sp) }
        items(available) { (name, desc) ->
            val isPending = desc.startsWith("[pending]")
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isPending) SurfaceVariant.copy(alpha = 0.5f) else SurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row {
                        Text(name, color = if (isPending) OnSurfaceVariant else Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(if (isPending) "PENDING" else "READY", color = if (isPending) Warning else Success, fontSize = 9.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(desc.removePrefix("[pending] "), color = OnSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}
