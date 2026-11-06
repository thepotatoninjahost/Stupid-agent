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
import host.vorv.llm.CloudModel
import host.vorv.llm.ModelChoice
import host.vorv.tools.TermuxProbe
import host.vorv.ui.theme.*

@Composable
fun SettingsScreen(
    state: AgentState,
    onSaveKey: (String) -> Unit,
    onSetMode: (String) -> Unit,
    onSetCloudModel: (String) -> Unit,
    onSetAutoFallback: (Boolean) -> Unit,
    onSetLocalModel: (String) -> Unit,
    onDownloadModel: () -> Unit,
    onDeleteModel: (ModelChoice) -> Unit,
    onClearSetupError: () -> Unit = {},
    onClearAllData: () -> Unit = {},
    onGrantTermuxPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onTestTermuxConnection: () -> Unit = {},
    onRefreshTermuxStatus: () -> Unit = {},
    onRevokeShellSessionGrant: () -> Unit = {},
    onOpenFilesTab: () -> Unit = {},
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
                label = { Text("Cloud (GLM)", fontSize = 11.sp) },
            )
        }
        val activeChoice = ModelChoice.fromId(state.selectedModelId)
        val activeCloud = CloudModel.fromId(state.cloudModelId)
        Text(
            when {
                state.llmMode == "local" && state.localModelReady ->
                    "Active: ${activeChoice.displayName.substringBefore("(").trim()} on the Hexagon NPU via GenieX. " +
                        "No network, no key. Tool calls are parsed from the model's chat template."
                state.llmMode == "local" ->
                    "Local selected, but ${activeChoice.displayName.substringBefore("(").trim()} isn't downloaded yet."
                state.hasApiKey -> "Active: ${activeCloud.displayName} (${if (activeCloud.free) "free tier" else "paid per token"}) with native function calling." +
                    if (state.autoFallback && state.localModelReady) " If the cloud fails mid-task, the offline engine finishes the job." else ""
                else -> "Cloud selected, no key set. Add one below or download a local model."
            },
            color = OnSurfaceVariant,
            fontSize = 11.sp,
        )

        // ------------------------------------------------ cloud model picker (v0.7.0)
        if (state.llmMode == "cloud" || state.hasApiKey) {
            Text("Cloud Model", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "The free tier costs nothing — every other model is paid per token and strictly optional. " +
                    "Changing the model with a key saved re-tests it against the new model immediately.",
                color = OnSurfaceVariant, fontSize = 11.sp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CloudModel.entries.forEach { cm ->
                    val selected = state.cloudModelId == cm.id
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) Primary.copy(alpha = 0.08f) else SurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onSetCloudModel(cm.id) },
                                colors = RadioButtonDefaults.colors(selectedColor = Primary),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        cm.displayName,
                                        color = OnBackground,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (cm.free) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = Success.copy(alpha = 0.18f),
                                        ) {
                                            Text(
                                                " FREE",
                                                color = Success,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                                Text(cm.shortDesc, color = OnSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Offline fallback switch — only meaningful with a local model on disk.
            Surface(shape = RoundedCornerShape(12.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Offline fallback", color = OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.localModelReady)
                                "If the cloud engine fails mid-task (no signal, outage), the offline engine finishes the step — the switch and its reason are logged."
                            else
                                "Needs a downloaded local model (see Local Models above). Currently unarmed.",
                            color = OnSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = state.autoFallback && state.localModelReady,
                        onCheckedChange = { onSetAutoFallback(it) },
                        enabled = state.localModelReady,
                        colors = SwitchDefaults.colors(checkedTrackColor = Primary),
                    )
                }
            }
        }

        // ------------------------------------------------ api key
        Text("Z.ai API Key (cloud only)", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Create a free key at z.ai → API Keys. Stored encrypted at rest (Android Keystore-backed). " +
                "Never uploaded, never committed to git, never logged. Not needed in local mode. " +
                "Saving runs one real test call against the selected model.",
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
            enabled = key.isNotBlank() && !state.keyTesting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
        ) {
            Text(
                when {
                    state.keyTesting -> "Testing against ${CloudModel.fromId(state.cloudModelId).displayName}…"
                    saved -> "Saved ✓ — see test result below"
                    else -> "Save Key & Test"
                }
            )
        }

        if (state.keyTestResult != null) {
            val ok = state.keyTestResult!!.startsWith("✓")
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (ok) Success.copy(alpha = 0.12f) else Error.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    state.keyTestResult!!,
                    color = if (ok) Success else Error,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        if (state.hasApiKey && state.keyTestResult == null && !state.keyTesting) {
            Surface(shape = RoundedCornerShape(10.dp), color = Success.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                Text("✓ API key set — cloud mode available", color = Success, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
        }

        // ------------------------------- Termux integration (v0.6.14; checklist v0.6.17)
        // Field aftermath ("keeps coming up with new bullshit"): the gates
        // used to surface one per error, mid-task. This card now shows
        // EVERY gate with its live status at once, and names exactly ONE
        // next action — the whole wall at once, one brick to fix at a time.
        Text("Termux Integration (shell tool + self-repair)", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Every gate between Vorv and a working shell — all of them, live, " +
                "in the order they must open. Fix them top to bottom; each names " +
                "its exact price. Nothing is held back for a mid-task surprise.",
            color = OnSurfaceVariant, fontSize = 11.sp,
        )

        // The dead-shell circuit breaker, visible: the model's status line
        // points the user here ("Setup → Termux integration → Test
        // connection") — this banner is what they find when they arrive.
        if (state.shellBlockedThisSession) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Error.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    TermuxProbe.blockedBannerMessage(),
                    color = Error, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        // Pure, pinned by TermuxSetupTest — recomputed from live state.
        val gates = TermuxProbe.setupChecklist(
            state.workspaceUri.takeIf { it.isNotBlank() },
            state.termuxInstalled,
            state.runCommandGranted,
            state.shellVerifiedThisSession,
            state.shellBlockedThisSession,
        )
        val nextStep = TermuxProbe.nextStepMessage(gates)

        Surface(shape = RoundedCornerShape(12.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Exactly ONE action at a time — or the honest all-clear.
                if (nextStep != null) {
                    Text(nextStep, color = Warning, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        "ALL 4 GATES OPEN — the shell tool is fully operational.",
                        color = Success, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(2.dp))

                gates.forEach { gate ->
                    val (symbol, color) = when (gate.status) {
                        TermuxProbe.GateStatus.PASS -> "✓" to Success
                        TermuxProbe.GateStatus.FAIL -> "✗" to Error
                        TermuxProbe.GateStatus.UNKNOWN -> "?" to OnSurfaceVariant
                    }
                    Column {
                        Text(
                            "${gate.n}. ${gate.title}  $symbol",
                            color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(gate.detail, color = OnSurfaceVariant, fontSize = 10.sp)
                    }
                }

                Spacer(Modifier.height(2.dp))

                // Gate 1's fix lives in the Files tab — one tap away.
                if (gates.first().status != TermuxProbe.GateStatus.PASS) {
                    Button(
                        onClick = onOpenFilesTab,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50),
                    ) { Text("Open the Files tab to grant the workspace", fontSize = 11.sp) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onGrantTermuxPermission,
                        enabled = state.termuxInstalled && !state.runCommandGranted,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f),
                    ) { Text("Grant (one tap)", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = onOpenAppSettings,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f),
                    ) { Text("Android Settings…", fontSize = 11.sp) }
                }

                OutlinedButton(
                    onClick = onTestTermuxConnection,
                    enabled = state.termuxInstalled && state.runCommandGranted && !state.termuxTesting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        if (state.termuxTesting) "Testing…"
                        else "Test connection — runs `echo vorv-termux-ok` in Termux",
                        fontSize = 11.sp,
                    )
                }

                if (state.termuxTestResult != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (state.termuxTestResult!!.startsWith("ALL GOOD"))
                            Success.copy(alpha = 0.12f) else Warning.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            state.termuxTestResult!!,
                            color = if (state.termuxTestResult!!.startsWith("ALL GOOD")) Success else OnBackground,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                Text(
                    "Why Vorv cannot do the Termux-side steps for you: both files " +
                        "live inside Termux's private data directory, which Android " +
                        "blocks every other app from touching. One manual setup, then " +
                        "the shell works forever. Also install the compilers " +
                        "self-repair needs: pkg install kotlin",
                    color = OnSurfaceVariant, fontSize = 10.sp,
                )

                TextButton(
                    onClick = onRefreshTermuxStatus,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("RECHECK", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                // ------------------------------------- session grants (v0.6.15)
                // Live view of what the model is TOLD (verification) and what
                // the user has granted (consent batching) — both reset when
                // the app process dies, by design.
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.shellVerifiedThisSession)
                        "✓ Shell VERIFIED this session — a real command round-tripped; the assistant is told the shell works"
                    else
                        "○ Shell not verified yet this session — the assistant is told to try the shell tool rather than assume no access",
                    color = if (state.shellVerifiedThisSession) Success else OnSurfaceVariant,
                    fontSize = 11.sp,
                )
                if (state.shellSessionGrant) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Warning.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Session grant ACTIVE — shell commands run without further prompts " +
                                    "(${state.shellAutoRunCount} so far, each logged in full).",
                                color = Warning, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(
                                onClick = onRevokeShellSessionGrant,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            ) { Text("REVOKE — ask before every command again", color = Error, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                } else {
                    Text(
                        "No session grant — every shell command asks first. Tap 'Allow all this " +
                            "session' on a shell approval dialog to batch them (logged, revocable, " +
                            "expires when Vorv closes).",
                        color = OnSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
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
                "16 real tools (including self_fix, which lets you ask Vorv in chat to fix " +
                    "or change its own code). Local engine: Qwen2.5-Coder via Qualcomm GenieX on the Hexagon NPU " +
                "(offline after one 1.8–4.4 GB download). Cloud engine: Z.ai GLM — FREE tier by default " +
                "(GLM-4.7-Flash), paid models optional, automatic offline fallback.\n\n" +
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
