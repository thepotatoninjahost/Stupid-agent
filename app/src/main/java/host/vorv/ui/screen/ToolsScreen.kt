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
    // This inventory must name every tool AgentViewModel registers. It has
    // drifted before — 16 listed while 17 were registered, then 18 listed
    // while 24 were registered (computer_run, web_test, build_app, linux_run,
    // brain and native_test were missing). Register a tool and add it here in
    // the same change.
    val ready = listOf(
        "memory_write" to "Persist a fact for future sessions (Room + FTS4)",
        "memory_recall" to "FTS4 keyword search + LIKE fallback",
        "memory_web" to "Inspect the associative knowledge web: stats, keyword query, one node by id — the graph that grows from every chat and task",
        "js_exec" to "Rhino sandbox: ClassShutter, no Java interop, 5s budget",
        "computer_run" to "Run a program on the app's own computer: js (sandboxed JavaScript, no file/network) or python (the sandboxed interpreter); real output under a hard deadline",
        "file_read" to "Read a file from granted workspace (SAF, 50KB cap)",
        "file_write" to "Write/overwrite a file in workspace (SAF, 1MB cap)",
        "file_list" to "Recursive list of workspace files (SAF)",
        "crash_logs" to "Read the agent's own crash evidence: Java crash reports, watchdog detections, boot traces",
        "python_exec" to "Hardened Python 3.11 sandbox: audit hooks, import whitelist, no file/network",
        "web_search" to "Z.ai Web Search API (documented /web_search endpoint)",
        "web_fetch" to "OkHttp fetch, 2MB cap, private-network blocked, jsoup extraction",
        "app_intent" to "Android intents (view/share/dial/open_app) — every action needs your confirmation",
        "shell" to "A real shell in Vorv's own Linux system with REAL stdout/stderr/exit-code capture (no root, nothing to install; your confirmation first)",
        "linux_run" to "Debian 12 shell in the same Linux system — apt-get install and run any language the distribution carries; your confirmation first",
        "notify" to "Post a system notification (checks permission, honest error if missing)",
        "schedule" to "WorkManager delayed reminder — unique name, 7-day cap, survives reboot",
        "schedule_cancel" to "Cancel pending reminders by title or all",
        "browser_automate" to "Headless WebView: http/https only, no file://, private hosts blocked",
        "web_test" to "Serve a built web app on loopback, load it in a real WebView, run your checks and save a screenshot — a pass/fail verdict, not a guess",
        "build_app" to "Compile a project on the phone into a signed APK (Main.java entry point); the APK native_test then installs",
        "native_test" to "Install the APK you built, open it, and tap through your steps with a pass/fail for each — needs Vorv app testing enabled in Settings > Accessibility",
        "brain" to "Train and query Vorv's own small on-device model (train/generate/status), reporting the loss before and after; the weights persist across restarts",
        "self_fix" to "Fix or modify Vorv's own code: Vorv writes a plan naming each class it will change, you approve that plan ONCE, then the on-device coder writes each class, Vorv's own compiler builds it, the public API is checked, it is signed, and a crash rolls it back. Nothing outside the plan is touched; a step that fails stops the plan",
    )
    val engines = listOf(
        "local_llm" to if (state.localModelReady) {
            val m = host.vorv.llm.ModelChoice.fromId(state.selectedModelId)
            "${m.displayName.substringBefore("(").trim()} via GenieX/llama.cpp — downloaded, offline ready"
        } else {
            val m = host.vorv.llm.ModelChoice.fromId(state.selectedModelId)
            "${m.displayName.substringBefore("(").trim()} via GenieX/llama.cpp — download in Setup (~${"%,.1f".format(m.approxBytes / 1_000_000_000.0)} GB, one time)"
        },
        "cloud_llm" to if (state.hasApiKey) {
            val model = state.cloudCustomModel.ifBlank { host.vorv.llm.provider.BrainTarget.DEFAULT_MODEL }
            "$model on your provider with native function calling"
        } else {
            "your provider with native function calling — add a key in Setup"
        },
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
