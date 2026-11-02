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
    val ready = listOf(
        "memory_write" to "Persist a fact for future sessions (Room + FTS4)",
        "memory_recall" to "FTS4 keyword search + LIKE fallback",
        "js_exec" to "Rhino sandbox: ClassShutter, no Java interop, 5s budget",
        "file_read" to "Read a file from granted workspace (SAF, 50KB cap)",
        "file_write" to "Write/overwrite a file in workspace (SAF, 1MB cap)",
        "file_list" to "Recursive list of workspace files (SAF)",
        "python_exec" to "Hardened Python 3.11 sandbox: AST attribute guard, audit hooks, import whitelist, no file/network",
        "web_search" to "Z.ai Web Search API (documented /web_search endpoint)",
        "web_fetch" to "OkHttp fetch, 2MB cap, private-network blocked, jsoup extraction",
        "app_intent" to "Android intents (view/share/dial/open_app) — every action needs your confirmation",
        "shell" to "Bash via Termux — every command asks for confirmation; REAL stdout/stderr/exit-code capture",
        "notify" to "Post a system notification (checks permission, honest error if missing)",
        "schedule" to "WorkManager delayed reminder — unique name, 7-day cap, survives reboot",
        "schedule_cancel" to "Cancel pending reminders by title or all",
        "browser_automate" to "Headless WebView: http/https only, no file://, every navigation host-checked",
    )
    val engines = listOf(
        "local_llm" to if (state.localModelReady) {
            val m = host.vorv.llm.ModelChoice.fromId(state.selectedModelId)
            "${m.displayName.substringBefore("(").trim()} via GenieX/llama.cpp — downloaded, offline ready"
        } else {
            val m = host.vorv.llm.ModelChoice.fromId(state.selectedModelId)
            "${m.displayName.substringBefore("(").trim()} via GenieX/llama.cpp — download in Setup (~${"%,.1f".format(m.approxBytes / 1_000_000_000.0)} GB, one time)"
        },
        "cloud_llm" to "GLM-4.6 with native function calling — needs Z.ai API key",
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Tools", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item {
            Text(
                "${ready.size} tools, all wired into the agent. Sandboxes are real, outputs are real, " +
                    "and sensitive actions require your confirmation.",
                color = OnSurfaceVariant, fontSize = 12.sp
            )
        }

        item { Text("Ready", color = Success, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        items(ready) { (name, desc) ->
            Surface(shape = RoundedCornerShape(10.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row {
                        Text(name, color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("READY", color = Success, fontSize = 9.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(desc, color = OnSurfaceVariant, fontSize = 11.sp)
                }
            }
        }

        item { Text("Reasoning engines", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
        items(engines) { (name, desc) ->
            val isLocal = name == "local_llm"
            val ok = if (isLocal) state.localModelReady else state.hasApiKey
            Surface(shape = RoundedCornerShape(10.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row {
                        Text(name, color = OnBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(if (ok) "READY" else "SETUP", color = if (ok) Success else Warning, fontSize = 9.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(desc, color = OnSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}
