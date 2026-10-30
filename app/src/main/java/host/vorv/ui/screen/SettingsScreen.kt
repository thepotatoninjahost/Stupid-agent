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
fun SettingsScreen(state: AgentState, onSaveKey: (String) -> Unit, onSetMode: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Text("LLM Engine", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("local" to "Local (offline)", "cloud" to "Cloud (GLM-4.6)").forEach { (mode, label) ->
                FilterChip(
                    selected = state.llmMode == mode,
                    onClick = { onSetMode(mode) },
                    label = { Text(label, fontSize = 11.sp) },
                )
            }
        }
        Text(
            when (state.llmMode) {
                "local" -> "Default. Runs offline. (Note: MLC local LLM is being integrated — currently a stub. Watch this space.)"
                else -> if (state.hasApiKey) "Cloud mode active. GLM-4.6 will be used with native function calling." else "Cloud mode selected but no API key set. Add one below."
            },
            color = OnSurfaceVariant,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(8.dp))
        Text("Z.ai API Key", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Required only for cloud mode. Stored locally with Android DataStore — never uploaded, never committed to git.", color = OnSurfaceVariant, fontSize = 11.sp)
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
                Text("✓ API key set", color = Success, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("About Vorv", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Vorv is a local-first autonomous agent. Coined name — no existing product.\n\n" +
                "Architecture: ReAct loop with verifiable tool-call logging. " +
                "Persistent memory across reboots via Room. " +
                "Foreground service for background task execution. " +
                "Cloud LLM is optional — local mode is the default.",
            color = OnSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}
