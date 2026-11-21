package host.vorv.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import host.vorv.AgentState
import host.vorv.llm.BrainDoctor
import host.vorv.llm.ModelChoice
import host.vorv.ui.theme.*

@Composable
fun SettingsScreen(
    state: AgentState,
    onSaveKey: (String) -> Unit,
    onSetMode: (String) -> Unit,
    onSetCloudProvider: (String, String) -> Unit = { _, _ -> },
    onSetAutoFallback: (Boolean) -> Unit,
    onSetLocalModel: (String) -> Unit,
    onDownloadModel: () -> Unit,
    onDeleteModel: (ModelChoice) -> Unit,
    onClearSetupError: () -> Unit = {},
    onClearAllData: () -> Unit = {},
    onTestLinuxSystem: () -> Unit = {},
    onInstallLinuxToolchain: () -> Unit = {},
    onRefreshLinuxStatus: () -> Unit = {},
    onRevokeShellSessionGrant: () -> Unit = {},
    onOpenFilesTab: () -> Unit = {},
    onSelectProvider: (String) -> Unit = {},
    onSaveProviderKey: (String) -> Unit = {},
    onRefreshModels: () -> Unit = {},
    onTestProvider: () -> Unit = {},
) {
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var providerUrl by remember { mutableStateOf(state.cloudBaseUrl) }
    var providerModel by remember { mutableStateOf(state.cloudCustomModel) }
    var providerSaved by remember { mutableStateOf(false) }

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
                label = { Text("Cloud", fontSize = 11.sp) },
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
                state.hasApiKey -> "Active: ${state.cloudCustomModel.ifBlank { "your provider's model" }} with native function calling." +
                    if (state.autoFallback && state.localModelReady) " If the cloud fails mid-task, the offline engine finishes the job." else ""
                else -> "Cloud selected, no key set. Add one below or download a local model."
            },
            color = OnSurfaceVariant,
            fontSize = 11.sp,
        )

        // ------------------------------------------------ provider + key
        Text("Cloud coder", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Pick who runs the cloud brain. Z.ai is one option, not the only one. " +
                "Each provider keeps its own key, encrypted at rest (Android Keystore). " +
                "Nothing is ever uploaded, committed, or logged — and saving always runs one real call.",
            color = OnSurfaceVariant, fontSize = 11.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            host.vorv.llm.provider.ProviderRegistry.presets.forEach { p ->
                FilterChip(
                    selected = state.providerId == p.id,
                    onClick = { onSelectProvider(p.id) },
                    label = { Text(p.label, fontSize = 11.sp) },
                )
            }
        }
        Text(
            if (state.keyReadError != null) {
                "⚠ ${state.keyReadError}"
            } else {
                "Stored key for ${host.vorv.llm.provider.ProviderRegistry.byId(state.providerId)?.label ?: "this provider"}: " +
                    if (state.hasApiKey) "present" else "none yet"
            },
            color = if (state.keyReadError != null) Error else OnSurfaceVariant,
            fontSize = 11.sp,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; saved = false },
            placeholder = { Text("Paste this provider's API key…") },
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
            onClick = { if (key.isNotBlank()) { onSaveProviderKey(key); saved = true } },
            enabled = key.isNotBlank() && !state.keyTesting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
        ) {
            Text(
                when {
                    state.keyTesting -> "Testing against ${state.cloudCustomModel.ifBlank { host.vorv.llm.provider.BrainTarget.DEFAULT_MODEL }}…"
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onRefreshModels,
                enabled = !state.catalogLoading,
                shape = RoundedCornerShape(50),
            ) {
                Text(if (state.catalogLoading) "Asking the provider…" else "Refresh model list", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onTestProvider,
                enabled = !state.keyTesting,
                shape = RoundedCornerShape(50),
            ) {
                Text("Test this coder", fontSize = 11.sp)
            }
        }
        state.catalogError?.let { msg ->
            Text("✗ $msg", color = Error, fontSize = 11.sp)
        }
        if (state.catalogIds.isNotEmpty()) {
            Text(
                "${state.catalogIds.size} models this key can use — tap one:",
                color = OnSurfaceVariant,
                fontSize = 11.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.catalogIds.take(40).forEach { id ->
                    AssistChip(
                        onClick = { onSetCloudProvider(state.cloudBaseUrl, id) },
                        label = { Text(id, fontSize = 10.sp) },
                    )
                }
            }
        }

        if (state.hasApiKey && state.keyTestResult == null && !state.keyTesting) {
            Surface(shape = RoundedCornerShape(10.dp), color = Success.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                Text("✓ API key set — cloud mode available", color = Success, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
        }

        // ------------------------------------------ a coder you choose
        // 0.9.13 — the cloud brain used to be Z.ai only. Any provider whose API
        // takes the same request (address .../chat/completions, Bearer key)
        // works now; blank fields keep the Z.ai chain exactly as before.
        Text("Use a different coder (optional)", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Have a key from another provider? Put its address and the model name below and it becomes " +
                "the cloud brain. Leave the address blank to use the default (Qwen3 Coder on OpenRouter). " +
                "Saving runs one real test call, so the answer here is proof, not a promise.",
            color = OnSurfaceVariant, fontSize = 11.sp,
        )
        OutlinedTextField(
            value = providerUrl,
            onValueChange = { providerUrl = it; providerSaved = false },
            placeholder = { Text("Address, e.g. https://api.example.com/v1") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = Border,
            ),
        )
        OutlinedTextField(
            value = providerModel,
            onValueChange = { providerModel = it; providerSaved = false },
            placeholder = { Text("Model name, e.g. kimi-k2-0905-preview") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = Border,
            ),
        )
        Button(
            onClick = { onSetCloudProvider(providerUrl.trim(), providerModel.trim()); providerSaved = true },
            enabled = !state.keyTesting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
        ) {
            Text(
                when {
                    state.keyTesting -> "Testing that coder…"
                    providerSaved -> "Saved ✓ — see test result above"
                    providerUrl.isBlank() && providerModel.isBlank() -> "Clear — use Z.ai"
                    else -> "Save coder & Test"
                }
            )
        }

        // ------------------------------------------- brain health
        // 0.9.18 — one honest surface. Every row states a fact the app can
        // see (key present, last reply, what is on disk) and what to tap if
        // it is wrong. "No brain" without a cause is gone.
        Text("Brain health", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "What actually works right now. This is the same answer the chat gate uses — " +
                "the header and these rows cannot disagree.",
            color = OnSurfaceVariant, fontSize = 11.sp,
        )
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    state.currentLlmLabel,
                    color = OnBackground,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.brainChecks.isEmpty()) {
                    Text("Measuring…", color = OnSurfaceVariant, fontSize = 11.sp)
                }
                state.brainChecks.forEach { c ->
                    Text(
                        c.line(),
                        color = when (c.state) {
                            host.vorv.llm.BrainDoctor.State.OK -> Success
                            host.vorv.llm.BrainDoctor.State.WARN -> Warning
                            host.vorv.llm.BrainDoctor.State.FAIL -> Error
                        },
                        fontSize = 11.sp,
                    )
                }
                if (state.brainReport.isNotBlank()) {
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.brainReport)) }) {
                        Text("Copy brain health", fontSize = 11.sp)
                    }
                }
            }
        }

        // --------------------------------- Linux system (Vorv's own computer)
        // What used to be the Termux checklist. There is nothing to install
        // and no permission to grant any more: the guest ships inside the APK.
        Text("Linux System (the shell's computer)", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "The shell and linux_run tools execute inside a Debian 12 Linux system that " +
                "lives in this app's own storage — no root, no Termux, no other app. " +
                "Packages install with 'apt-get install' (run 'apt-get update' first).",
            color = OnSurfaceVariant, fontSize = 11.sp,
        )

        if (state.shellBlockedThisSession) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Error.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Shell is disabled for this session: a command never came back. " +
                        "Force-stop and reopen Vorv (or reboot the phone) — the guest is " +
                        "recreated on the next command and shell re-enables itself.",
                    color = Error, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        val guestRan = state.shellVerifiedThisSession ||
            (state.linuxTestResult?.startsWith("ALL GOOD") == true)
        val gates = listOf(
            Triple(
                "The Linux payload is in this build",
                if (state.linuxAvailable || state.linuxPayloadInApk) "✓" else "✗",
                when {
                    state.linuxAvailable -> "proot is installed and executable on this phone"
                    state.linuxPayloadInApk ->
                        "proot IS in this APK, but Android did not unpack it for this install —" +
                            " uninstall Vorv and install this same APK again"
                    else -> "this APK was built without the payload — run tools/forge/fetch-linux.sh and rebuild"
                },
            ),
            Triple(
                "Filesystem unpacked",
                if (state.linuxProvisioned) "✓" else "?",
                if (state.linuxProvisioned) "the Debian 12 tree is extracted and stamped"
                else "unpacks once on the first command (about 9 MB, a few seconds)",
            ),
            Triple(
                "A real command answered",
                if (guestRan) "✓" else "?",
                if (guestRan) "the guest executed a command and returned its output"
                else "tap Test below — nothing is assumed",
            ),
            Triple(
                "Workspace visible at " + host.vorv.tools.ShellTool.GUEST_WORKSPACE,
                if (state.workspaceUri.isNotBlank()) "✓" else "?",
                if (state.workspaceUri.isNotBlank()) "the folder you granted is bound into the guest"
                else "optional: grant a folder in the Files tab to edit the same files from both sides",
            ),
        )

        Surface(shape = RoundedCornerShape(12.dp), color = SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (state.linuxAvailable && guestRan)
                        "READY — the shell runs inside Vorv's own Linux system."
                    else if (!state.linuxAvailable && state.linuxPayloadInApk)
                        "PACKAGED, NOT UNPACKED — Android did not unpack this APK's binaries; reinstall it."
                    else if (!state.linuxAvailable)
                        "NOT PACKAGED — this build has no Linux system."
                    else
                        "PACKAGED, NOT YET RUN — test it once to prove it works on this phone.",
                    color = when {
                        state.linuxAvailable && guestRan -> Success
                        !state.linuxAvailable && state.linuxPayloadInApk -> Warning
                        !state.linuxAvailable -> Error
                        else -> Warning
                    },
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(2.dp))

                gates.forEachIndexed { i, (title, symbol, detail) ->
                    val color = when (symbol) {
                        "✓" -> Success
                        "✗" -> Error
                        else -> OnSurfaceVariant
                    }
                    Column {
                        Text("${i + 1}. $title  $symbol", color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(detail, color = OnSurfaceVariant, fontSize = 10.sp)
                    }
                }

                if ((!state.linuxAvailable || state.linuxPayloadInApk) && !state.linuxDiagnostics.isNullOrBlank()) {
                    Text(
                        state.linuxDiagnostics!!,
                        color = OnSurfaceVariant, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                if (state.workspaceUri.isBlank()) {
                    Button(
                        onClick = onOpenFilesTab,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50),
                    ) { Text("Open the Files tab to grant a workspace folder", fontSize = 11.sp) }
                }

                OutlinedButton(
                    onClick = onTestLinuxSystem,
                    enabled = !state.linuxTesting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        if (state.linuxTesting) "Testing… (first run unpacks the filesystem)"
                        else "Test the Linux system — boots Debian and reports its version",
                        fontSize = 11.sp,
                    )
                }

                if (state.linuxTestResult != null) {
                    val good = state.linuxTestResult!!.startsWith("ALL GOOD")
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (good) Success.copy(alpha = 0.12f) else Error.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            state.linuxTestResult!!,
                            color = if (good) Success else Error,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                OutlinedButton(
                    onClick = onInstallLinuxToolchain,
                    enabled = !state.toolchainInstalling,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        when {
                            state.toolchainInstalling -> "Installing the build tools… don't close Vorv"
                            state.toolchainInstalled -> "Build tools installed — reinstall (203 MB, sha256-checked)"
                            else -> "Install the Android build tools (203 MB, one time)"
                        },
                        fontSize = 11.sp,
                    )
                }

                if (state.toolchainProgress != null) {
                    Text(
                        state.toolchainProgress!!,
                        color = OnSurfaceVariant,
                        fontSize = 10.sp,
                    )
                }

                if (state.toolchainResult != null) {
                    val good = state.toolchainResult!!.startsWith("ALL GOOD")
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (good) Success.copy(alpha = 0.12f) else Error.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            state.toolchainResult!!,
                            color = if (good) Success else Error,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                TextButton(
                    onClick = onRefreshLinuxStatus,
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
                "${state.toolCount} real tools registered (including self_fix, which lets you ask Vorv in " +
                "chat to fix or change its own code); the Tools tab lists them, live.\n\n" +
                "What runs it: the built-in reasoning brain on device, and any cloud model you have " +
                "selectable in Setup (free tier first, paid models optional, automatic fallback). " +
                "No model is required for js_exec, python_exec, file work or the Linux system.\n\n" +
                "Honest limits: real measured tok/s appears in the trace after each generation. " +
                "js_exec and python_exec run in hardened in-process sandboxes; every powerful action " +
                "asks first. The Linux system is Debian 12 under proot inside Vorv — no root, no Termux — and " +
                "it has not yet been seen starting on this phone: Setup → Linux System → TEST is what " +
                "proves it, and it names the stage that fails.\n\n" +
                "See SECURITY.md in the repository for the full threat model.",
            color = OnSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}
