package host.vorv.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import host.vorv.AgentState
import host.vorv.ui.theme.*

@Composable
fun SettingsScreen(state: AgentState, onSaveKey: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Setup", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Text("LLM Engine", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Surface(shape = RoundedCornerShape(10.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Text(
                "GLM-4.6 (cloud, via Z.ai API)\n\nLocal LLM (MLC LLM Android) is the next milestone — not yet wired. Cloud mode requires a Z.ai API key, which you can get free at z.ai/manage-apikey.",
                color = OnSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(12.dp),
            )
        }

        Text("Z.ai API Key", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Required. Stored locally with Android DataStore — never uploaded, never committed to git, never logged.", color = OnSurfaceVariant, fontSize = 11.sp)
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; saved = false },
            placeholder = { Text("Paste Z.ai API key…") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = Border,
            ),
        )
        Button(
            onClick = { if (key.isNotBlank()) { onSaveKey(key); saved = true } },
            enabled = key.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
        ) { Text(if (saved) "Saved ✓" else "Save Key") }

        if (state.hasApiKey) {
            Surface(shape = RoundedCornerShape(10.dp), color = Success.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("✓ API key set", color = Success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Open Chat tab and ask Vorv anything. It will plan, call real tools, observe results, and answer with citations.", color = OnSurfaceVariant, fontSize = 11.sp)
                }
            }
        } else {
            Surface(shape = RoundedCornerShape(10.dp), color = Warning.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("No API key yet", color = Warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Without a key, Chat will refuse with a real error. Cloud LLM (GLM-4.6) is the only working engine right now.", color = OnSurfaceVariant, fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("About Vorv", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Vorv is a local-first autonomous agent. Coined name — no existing product.\n\n" +
                "Architecture: ReAct loop with verifiable tool-call logging. " +
                "Persistent memory across reboots via Room. " +
                "Foreground service for background task execution. " +
                "13 real tools: memory_write/recall, js_exec, file_read/write/list, python_exec (Chaquopy), web_search, web_fetch, app_intent, shell (Termux), notify, schedule (WorkManager).\n\n" +
                "Next milestone: MLC LLM Android for offline mode.",
            color = OnSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}
