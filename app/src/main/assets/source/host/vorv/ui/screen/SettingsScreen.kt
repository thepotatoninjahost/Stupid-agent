package host.vorv.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import host.vorv.AgentState
import host.vorv.llm.ModelChoice
import host.vorv.ui.theme.*

@Composable
fun SettingsScreen(
    state: AgentState,
    onSaveKey: (String) -> Unit,
    onSetMode: (String) -> Unit,
    onSetLocalModel: (String) -> Unit,
    onDownloadModel: () -> Unit,
    onDeleteModel: (ModelChoice) -> Unit,
    onClearSetupError: () -> Unit = {},
    onClearAllData: () -> Unit = {},
) {
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Setup", color = OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // ------------------------------------------------ setup error banner
        // Shows up here when a download fails — NOT in the chat.
        state.setupError?.let { errMsg ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Error.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⚠ $errMsg",
                        color = Error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onClearSetupError,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("Dismiss", color = OnSurfaceVariant, fontSize = 11.sp) }
                }
            }
        }

        // ------------------------------------------------ local models
        Text("Local Models (offline engine)", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Sized for the Galaxy S25 — Snapdragon 8 Elite, 12 GB RAM. " +
                "First download needs internet; after that, inference runs fully offline in airplane mode.",
            color = OnSurfaceVariant, fontSize = 11.sp,
        )

        if (state.busy) {
            Surface(shape = RoundedCornerShape(10.dp), color = Warning.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "A task is running — model switching and deletion are disabled until it finishes.",
                    color = Warning, fontSize = 11.sp, modifier = Modifier.padding(10.dp)
                )
            }
        }

        ModelChoice.entries.forEach { choice ->
            val isDownloaded = choice.id in state.downloadedModelIds
            val isSelected = state.selectedModelId == choice.id
            val isDownloading = state.downloadingModel && isSelected

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) Primary.copy(alpha = 0.08f) else SurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSetLocalModel(choice.id) },
                            // Busy-gated (audit H-4): the repository refuses swaps
                            // mid-task; the UI reflects the same rule.
                            enabled = !state.busy && (isDownloaded || !state.downloadingModel),
                            colors = RadioButtonDefaults.colors(selectedColor = Primary),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                choice.displayName,
                                color = OnBackground,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(choice.shortDesc, color = OnSurfaceVariant, fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    when {
                        isDownloaded -> {
                            Text("✓ Downloaded — ready for offline inference", color = Success, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            if (isSelected && state.llmMode == "local") {
                                Text("Active local engine.", color = Primary, fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = { onDeleteModel(choice) },
                                enabled = !state.busy,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            ) {
                                Text("Delete (frees ~${choice.approxBytes / 1_000_000_000} GB)", color = Error, fontSize = 11.sp)
                            }
                        }
                        isDownloading -> {
                            Text("Downloading… ${state.downloadProgress}%", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { state.downloadProgress / 100f },
                                color = Primary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "One-time download from HuggingFace (~${"%,.1f".format(choice.approxBytes / 1_000_000_000.0)} GB). Keep the app open. Resumable if interrupted.",
                                color = OnSurfaceVariant, fontSize = 10.sp,
                            )
                        }
                        else -> {
                            Text(
                                "Not downloaded — ~${"%,.1f".format(choice.approxBytes / 1_000_000_000.0)} GB. " +
                                    "Official, un-gated release from HuggingFace.",
                                color = OnSurfaceVariant, fontSize = 11.sp,
                            )
                            if (!state.downloadingModel && !state.busy) {
                                Button(
                                    onClick = {
                                        onSetLocalModel(choice.id)
                                        onDownloadModel()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(50),
                                ) { Text("Download ${choice.displayName.substringBefore("(").trim()}") }
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------ engine picker
        Text("LLM Engine", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.llmMode == "local",
                onClick = { if (state.localModelReady) onSetMode("local") },
                enabled = state.localModelReady && !state.busy,
                label = { Text("Local (offline)", fontSize = 11.sp) },
            )
            FilterChip(
                selected = state.llmMode == "cloud",
                onClick = { onSetMode("cloud") },
                label = { Text("Cloud (GLM-4.6)", fontSize = 11.sp) },
            )
        }
        val activeChoice = ModelChoice.fromId(state.selectedModelId)
        Text(
            when {
                state.llmMode == "local" && state.localModelReady ->
                    "Active: ${activeChoice.displayName.substringBefore("(").trim()} on the Hexagon NPU via GenieX. " +
                        "No network, no key. Tool calls are parsed from the model's chat template."
                state.llmMode == "local" ->
                    "Local selected, but ${activeChoice.displayName.substringBefore("(").trim()} isn't downloaded yet."
                state.hasApiKey -> "Active: GLM-4.6 with native function calling. Needs internet."
                else -> "Cloud selected, no key set. Add one below or download a local model."
            },
            color = OnSurfaceVariant,
            fontSize = 11.sp,
        )

        // ------------------------------------------------ api key
        Text("Z.ai API Key (cloud only)", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Stored encrypted at rest (Android Keystore-backed). Never uploaded, never committed to git, never logged. Not needed in local mode.",
            color = OnSurfaceVariant, fontSize = 11.sp
        )
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

        // ------------------------------------------------ data
        Text("Data", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Episodes, completed tasks and tool-call logs older than 30 days are pruned automatically at boot. " +
                "Taught memories are never auto-deleted.",
            color = OnSurfaceVariant, fontSize = 11.sp,
        )
        OutlinedButton(
            onClick = onClearAllData,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
        ) { Text("Clear ALL data", color = Error, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }

        Spacer(Modifier.height(8.dp))
        Text("About Vorv", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Vorv is a local-first autonomous agent. Coined name — no existing product.\n\n" +
                "ReAct loop with verifiable tool-call logging. Persistent memory across reboots (Room). " +
                "15 real tools. Local engine: Qwen2.5-Coder via Qualcomm GenieX on the Hexagon NPU " +
                "(offline after one 1.8–4.4 GB download). Cloud engine: GLM-4.6 (optional).\n\n" +
                "Honest limits: real measured tok/s appears in the trace after each generation — " +
                "expected peak ~8-12 tok/s on the 7B model, ~4-6 sustained under thermal load. " +
                "js_exec and python_exec run in hardened in-process sandboxes; shell and app_intent " +
                "round-trip through your workspace and explicit confirmation dialogs. " +
                "See SECURITY.md in the repository for the full threat model.",
            color = OnSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}
