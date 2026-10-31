package host.vorv.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
fun SettingsScreen(
    state: AgentState,
    onSaveKey: (String) -> Unit,
    onSetMode: (String) -> Unit,
    onDownloadModel: () -> Unit,
    onDeleteModel: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Setup", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // ------------------------------------------------ local model
        Text("Local Model (offline engine)", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        when {
            state.localModelReady -> {
                Surface(shape = RoundedCornerShape(10.dp), color = Success.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("✓ Qwen2.5-0.5B downloaded — runs fully offline, no API key", color = Success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Inference via llama.cpp on-device. Vector embeddings active for memory recall.", color = OnSurfaceVariant, fontSize = 11.sp)
                    }
                }
                TextButton(onClick = onDeleteModel) {
                    Text("Delete model (frees ~400MB)", color = Error, fontSize = 11.sp)
                }
            }
            state.downloadingModel -> {
                Surface(shape = RoundedCornerShape(10.dp), color = Primary.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Downloading Qwen2.5-0.5B Q4_K_M… ${state.downloadProgress}%", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { state.downloadProgress / 100f },
                            color = Primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("One-time download from HuggingFace (~400MB). Keep the app open.", color = OnSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
            else -> {
                Surface(shape = RoundedCornerShape(10.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Not downloaded", color = OnSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Qwen2.5-0.5B-Instruct Q4_K_M (GGUF) — ~400MB, official un-gated release from HuggingFace. Downloaded once into the app's private storage. After that, airplane-mode inference works with no network call.", color = OnSurfaceVariant, fontSize = 11.sp)
                    }
                }
                Button(
                    onClick = onDownloadModel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                ) { Text("Download local model (~400MB)") }
            }
        }

        // ------------------------------------------------ engine picker
        Text("LLM Engine", color = OnBackground, fontSize = 14.sp, FontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.llmMode == "local",
                onClick = { if (state.localModelReady) onSetMode("local") },
                enabled = state.localModelReady,
                label = { Text("Local (offline)", fontSize = 11.sp) },
            )
            FilterChip(
                selected = state.llmMode == "cloud",
                onClick = { onSetMode("cloud") },
                label = { Text("Cloud (GLM-4.6)", fontSize = 11.sp) },
            )
        }
        Text(
            when {
                state.llmMode == "local" && state.localModelReady -> "Active: Qwen2.5-0.5B via llama.cpp. No network, no key. Small model — tool calls use a strict TOOL_CALL/ANSWER format."
                state.llmMode == "local" -> "Local selected, but the model isn't downloaded. Download above."
                state.hasApiKey -> "Active: GLM-4.6 with native function calling. Needs internet."
                else -> "Cloud selected, no key set. Add one below or download the local model."
            },
            color = OnSurfaceVariant,
            fontSize = 11.sp,
        )

        // ------------------------------------------------ api key
        Text("Z.ai API Key (cloud only)", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Stored locally with Android DataStore — never uploaded, never committed to git, never logged. Not needed in local mode.", color = OnSurfaceVariant, fontSize = 11.sp)
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
                Text("✓ API key set — cloud mode available", color = Success, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("About Vorv", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Vorv is a local-first autonomous agent. Coined name — no existing product.\n\n" +
                "ReAct loop with verifiable tool-call logging. Persistent memory across reboots (Room). " +
                "14 real tools. Local engine: Qwen2.5-0.5B via llama.cpp (offline after one download). " +
                "Cloud engine: GLM-4.6 (optional).\n\n" +
                "Honest limits: the local model is 0.5B — weaker than GLM-4.6 at reasoning and tool choice. " +
                "First model download needs internet once; after that airplane mode works.",
            color = OnSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}
