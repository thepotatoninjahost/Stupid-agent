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
        "memory_write" to "Persist a fact for future sessions (embeds a vector when local model is up)",
        "memory_recall" to "Vector cosine search + FTS4 keyword fallback",
        "js_exec" to "Run JavaScript in a Rhino sandbox (3s)",
        "file_read" to "Read a file from granted workspace (SAF)",
        "file_write" to "Write/overwrite a file in workspace (SAF)",
        "file_list" to "Recursive list of workspace files (SAF)",
        "python_exec" to "Run real Python 3.11 via Chaquopy (10s)",
        "web_search" to "Z.ai web_search function",
        "web_fetch" to "OkHttp fetch + HTML-to-text extraction",
        "app_intent" to "Send Android intents (view/share/dial/open_app)",
        "shell" to "Bash via Termux RUN_COMMAND intent (Termux + permission required)",
        "notify" to "Post a system notification",
        "schedule" to "WorkManager delayed task — fires even if app is killed",
        "browser_automate" to "In-app WebView: load/click/type/extract/run JS",
    )
    val engines = listOf(
        "local_llm" to if (state.localModelReady) "Qwen2.5-0.5B via llama.cpp — downloaded, offline ready" else "Qwen2.5-0.5B via llama.cpp — download in Setup (~400MB, one time)",
        "cloud_llm" to "GLM-4.6 with native function calling — needs Z.ai API key",
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Tools", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item { Text("${ready.size} tools, all real and wired into the agent. No stubs.", color = OnSurfaceVariant, fontSize = 12.sp) }

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
