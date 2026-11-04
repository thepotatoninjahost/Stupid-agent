package host.vorv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import host.vorv.data.SecretsStore
import host.vorv.data.VorvDatabase
import host.vorv.data.VorvPrefs
import host.vorv.data.entity.AgentTask
import host.vorv.data.entity.Episode
import host.vorv.data.entity.Skill
import host.vorv.data.entity.ToolCall
import host.vorv.kernel.CognitiveKernel
import host.vorv.kernel.RoomKernelEventSink
import host.vorv.kernel.TraceStep
import host.vorv.llm.Llm
import host.vorv.llm.ModelChoice
import host.vorv.llm.ModelManager
import host.vorv.llm.ModelRepository
import host.vorv.tools.AppIntentTool
import host.vorv.tools.BrowserAutomateTool
import host.vorv.tools.FileListTool
import host.vorv.tools.FileReadTool
import host.vorv.tools.FileWriteTool
import host.vorv.tools.JsExecTool
import host.vorv.tools.MemoryRecallTool
import host.vorv.tools.MemoryWriteTool
import host.vorv.tools.NotifyTool
import host.vorv.tools.PermissionBridge
import host.vorv.tools.PythonExecTool
import host.vorv.tools.ReminderRegistry
import host.vorv.tools.ScheduleCancelTool
import host.vorv.tools.ScheduleTool
import host.vorv.tools.SelfFixTool
import host.vorv.tools.ShellTool
import host.vorv.tools.TermuxProbe
import host.vorv.tools.WebFetchTool
import host.vorv.tools.WebSearchTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,                // "user" | "assistant"
    val content: String,
    val citations: List<host.vorv.tools.Citation> = emptyList(),
    val ts: Long = System.currentTimeMillis(),
)

/** A pending user-consent dialog raised by a tool (audit H-1/L-2/L-3). */
data class PendingConfirmation(
    val id: String,
    val message: String,
    val response: CompletableDeferred<Boolean>,
)

/**
 * A pending ANDROID runtime-permission dialog (v0.6.14). The UI observes
 * this, calls Activity.requestPermissions(), and reports back through
 * [AgentViewModel.respondPermissionRequest]. Used for Termux's
 * RUN_COMMAND permission — declared protectionLevel="dangerous" by the
 * Termux app, hence runtime-requestable with the standard one-tap dialog.
 */
data class PendingPermissionRequest(
    val id: String,
    val permission: String,
    val response: CompletableDeferred<Boolean>,
)

/** Full-output viewer state (audit M-7). */
data class FullOutputView(
    val title: String,
    val content: String,
)

data class AgentState(
    val screen: String = "chat",     // "chat" | "tasks" | "memory" | "tools" | "files" | "settings"
    val busy: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val liveTrace: List<TraceStep> = emptyList(),
    val memoryCount: Int = 0,
    val skillCount: Int = 0,
    val recentTasks: List<AgentTask> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val llmMode: String = "cloud",       // "local" | "cloud"
    val hasApiKey: Boolean = false,
    val localModelReady: Boolean = false,
    val downloadingModel: Boolean = false,
    val downloadProgress: Int = 0,
    val selectedModelId: String = "qwen7b",
    val downloadedModelIds: Set<String> = emptySet(),
    val workspaceUri: String = "",
    val currentLlmLabel: String = "GLM-4.6 (cloud)",
    /**
     * Setup-screen error message — when a download or model operation fails,
     * the error is surfaced here so the SettingsScreen can display it inline
     * below the affected model card. NEVER added to the chat message list.
     * Cleared on next successful operation or explicit user dismissal.
     */
    val setupError: String? = null,
    /** Active consent dialog (one at a time; concurrent requests queue via the mutex). */
    val pendingConfirmation: PendingConfirmation? = null,
    /** Active Android runtime-permission request (v0.6.14; one at a time). */
    val pendingPermissionRequest: PendingPermissionRequest? = null,
    /** Termux integration status (Setup tab, v0.6.14) — live pm lookups. */
    val termuxInstalled: Boolean = false,
    val runCommandGranted: Boolean = false,
    /** A Termux connection test is running (Setup tab). */
    val termuxTesting: Boolean = false,
    /** Verbatim result of the last Termux connection test (Setup tab). */
    val termuxTestResult: String? = null,
    /** Full-output dialog (audit M-7). */
    val fullOutputView: FullOutputView? = null,
    /** Tasks screen: which task's tool calls are expanded. */
    val expandedTaskId: String? = null,
    /** Tool calls for the expanded task. */
    val toolCallsForTask: List<ToolCall> = emptyList(),
)

class AgentViewModel(
    private val db: VorvDatabase,
    private val prefs: VorvPrefs,
    private val secrets: SecretsStore,
    private val modelRepository: ModelRepository,
    private val appContext: Context,
) : ViewModel() {

    private val reminderRegistry = ReminderRegistry(prefs)

    private val tools = listOf(
        MemoryWriteTool(db),
        MemoryRecallTool(db),
        JsExecTool(),
        FileReadTool(appContext),
        FileWriteTool(appContext),
        FileListTool(appContext),
        PythonExecTool(appContext),
        WebSearchTool(secrets),
        WebFetchTool(),
        AppIntentTool(appContext),
        ShellTool(appContext),
        NotifyTool(appContext),
        ScheduleTool(appContext, reminderRegistry),
        ScheduleCancelTool(appContext, reminderRegistry),
        BrowserAutomateTool(appContext),
        SelfFixTool(appContext, prefs),
    )

    private val outputDir = File(appContext.cacheDir, "tool_outputs")

    // v0.6.8 dispatch: the kernel is created through PatchRegistry so a
    // verified, approved patch of CognitiveKernel actually RUNS (same-FQCN
    // classes under different classloaders are different runtime identities —
    // dispatching through the shared KernelApi interface is the only way a
    // patched kernel can be used). Falls back to the original on any failure.
    private val kernel: CognitiveKernel =
        host.vorv.watchdog.PatchRegistry.create(
            host.vorv.kernel.KernelApi::class.java,
            CognitiveKernel::class.java.name,
            RoomKernelEventSink(db), tools, outputDir,
        ) as? CognitiveKernel ?: CognitiveKernel(RoomKernelEventSink(db), tools, outputDir)

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /** Serializes consent dialogs so only one is visible at a time. */
    private val confirmMutex = Mutex()

    init {
        // v0.6.14: install the runtime-permission bridge BEFORE any tool can
        // run. Tools call PermissionBridge.request() when they hit the
        // RUN_COMMAND wall; the handler shows Android's one-tap dialog and
        // reports back. See PermissionBridge.kt for why this is a
        // process-level reference rather than a ToolContext field
        // (binary compatibility with registered patches).
        @Suppress("LeakingThis")
        PermissionBridge.handler = ::permissionBridge
        refreshTermuxStatus()

        modelRepository.onTelemetry = { tps, ms ->
            viewModelScope.launch { kernel.emitTelemetry(tps.toFloat(), ms.toLong(), "local") }
        }

        viewModelScope.launch {
            db.knowledgeDao().observeRecent(100).collect { entries ->
                _state.update { it.copy(memoryCount = entries.size) }
            }
        }
        viewModelScope.launch {
            db.skillDao().observeAll().collect { skills ->
                _state.update { it.copy(skills = skills, skillCount = skills.size) }
            }
        }
        viewModelScope.launch {
            db.taskDao().observeRecent(50).collect { tasks ->
                _state.update { it.copy(recentTasks = tasks) }
            }
        }
        viewModelScope.launch {
            db.episodeDao().observeRecent(50).collect { episodes ->
                _state.update { it.copy(episodes = episodes) }
            }
        }
        viewModelScope.launch {
            secrets.zaiApiKey.collect { key ->
                _state.update { it.copy(hasApiKey = key.isNotBlank()) }
            }
        }
        viewModelScope.launch {
            prefs.workspaceUri.collect { uri ->
                _state.update { it.copy(workspaceUri = uri) }
            }
        }
        viewModelScope.launch {
            prefs.llmMode.collect { mode ->
                val id = prefs.localModelId.first()
                val choice = ModelChoice.fromId(id)
                val ready = ModelManager.isDownloaded(choice)
                _state.update { it.copy(llmMode = mode, currentLlmLabel = engineLabel(mode, ready, it.hasApiKey, choice)) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // One-shot cleanup of orphaned files from prior versions. Safe to
            // call every boot — only deletes files matching the LEGACY list.
            ModelManager.cleanupLegacyModelsDir(appContext)
            val downloaded = mutableListOf<ModelChoice>()
            for (m in ModelChoice.entries) {
                if (ModelManager.isDownloaded(m)) downloaded.add(m)
            }
            _state.update { it.copy(downloadedModelIds = downloaded.map { m -> m.id }.toSet()) }
        }
        viewModelScope.launch {
            prefs.localModelId.collect { id ->
                val choice = ModelChoice.fromId(id)
                // Engine lifecycle is owned by ModelRepository (audit H-4) —
                // it serializes swaps behind a mutex and refuses to swap mid-task.
                val ready = ModelManager.isDownloaded(choice)
                _state.update {
                    it.copy(
                        selectedModelId = id,
                        localModelReady = ready,
                        currentLlmLabel = engineLabel(it.llmMode, ready, it.hasApiKey, choice),
                    )
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            db.episodeDao().insert(Episode(kind = "boot", summary = "Vorv booted — ${tools.size} tools ready"))
            bootstrapSkills()
            runMaintenance()
        }
        viewModelScope.launch {
            kernel.trace.collect { step ->
                _state.update { it.copy(liveTrace = it.liveTrace + step) }
            }
        }
    }

    /** Per-tool skill rows, checked individually so upgrades add new tools (audit M-8). */
    private suspend fun bootstrapSkills() {
        tools.forEach { t ->
            if (db.skillDao().getByName(t.name) == null) {
                db.skillDao().upsert(Skill(name = t.name, domain = "tools"))
            }
        }
    }

    /**
     * Data retention (audit privacy section): episodes, completed tasks and
     * tool calls older than RETENTION_DAYS are pruned at boot. User-taught
     * knowledge is NEVER auto-deleted.
     */
    private suspend fun runMaintenance() {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60 * 60 * 1000
        runCatching {
            db.episodeDao().deleteOlderThan(cutoff)
            db.taskDao().deleteCompletedBefore(cutoff)
            db.toolCallDao().deleteOlderThan(cutoff)
        }
    }

    fun setScreen(screen: String) {
        _state.update { it.copy(screen = screen) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) { secrets.setZaiApiKey(key) }
    }

    fun setLlmMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) { prefs.setLlmMode(mode) }
    }

    /**
     * Switch the active local model via ModelRepository — refuses while a
     * task is running and surfaces the honest reason in setupError.
     */
    fun setLocalModel(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val choice = ModelChoice.fromId(id)
            prefs.setLocalModelId(id)
            try {
                modelRepository.swapModel(choice)
                _state.update { it.copy(setupError = null) }
            } catch (e: IllegalStateException) {
                _state.update { it.copy(setupError = e.message) }
            }
            val ready = ModelManager.isDownloaded(choice)
            _state.update {
                it.copy(
                    selectedModelId = id,
                    localModelReady = ready,
                    currentLlmLabel = engineLabel(it.llmMode, ready, it.hasApiKey, choice),
                )
            }
        }
    }

    fun setWorkspaceUri(uri: String) {
        viewModelScope.launch(Dispatchers.IO) { prefs.setWorkspaceUri(uri) }
    }

    /** One-time download of the selected local model, with real progress in state. */
    fun downloadLocalModel() {
        if (_state.value.downloadingModel || _state.value.busy) return
        val choice = ModelChoice.fromId(_state.value.selectedModelId)
        _state.update { it.copy(downloadingModel = true, downloadProgress = 0, setupError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (ModelManager.isDownloaded(choice)) {
                    _state.update { it.copy(downloadingModel = false, downloadProgress = 100) }
                    return@launch
                }
                ModelManager.pull(choice).collect { pct ->
                    _state.update { it.copy(downloadProgress = pct) }
                }
                // Engine creation is lazy now — activeEngine() builds it under
                // the repository lock on first use. No release/create race here.
                val newDownloaded = _state.value.downloadedModelIds + choice.id
                _state.update {
                    it.copy(
                        localModelReady = true,
                        downloadingModel = false,
                        downloadedModelIds = newDownloaded,
                        currentLlmLabel = engineLabel(it.llmMode, true, it.hasApiKey, choice),
                        setupError = null,
                    )
                }
                db.episodeDao().insert(Episode(kind = "lesson", summary = "Local model downloaded: ${choice.id} — NPU offline mode available"))
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        downloadingModel = false,
                        downloadProgress = 0,
                        setupError = "Download failed: ${e.message ?: "unknown error"}",
                    )
                }
                db.episodeDao().insert(Episode(kind = "error", summary = "Model download failed for ${choice.id}: ${e.message}"))
            }
        }
    }

    /** Clear the setup error after the user dismisses it. */
    fun clearSetupError() {
        _state.update { it.copy(setupError = null) }
    }

    fun deleteLocalModel(choice: ModelChoice) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                modelRepository.deleteModel(choice)
            } catch (e: IllegalStateException) {
                _state.update { it.copy(setupError = e.message) }
                return@launch
            }
            val activeId = _state.value.selectedModelId
            if (activeId == choice.id) {
                val newDownloaded = _state.value.downloadedModelIds - choice.id
                _state.update {
                    it.copy(
                        localModelReady = false,
                        llmMode = "cloud",
                        downloadedModelIds = newDownloaded,
                        currentLlmLabel = engineLabel("cloud", false, it.hasApiKey, choice),
                    )
                }
                prefs.setLlmMode("cloud")
            } else {
                _state.update { it.copy(downloadedModelIds = it.downloadedModelIds - choice.id) }
            }
        }
    }

    // ------------------------------------------------------- consent bridge

    /**
     * Suspends until the user answers the confirmation dialog (or 60s elapses
     * = deny). One dialog at a time; a second request waits for the first.
     */
    private suspend fun requestConfirmation(message: String): Boolean = confirmMutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        val pending = PendingConfirmation(
            id = java.util.UUID.randomUUID().toString(),
            message = message,
            response = deferred,
        )
        _state.update { it.copy(pendingConfirmation = pending) }
        val allowed = try {
            withTimeout(60_000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            false
        } finally {
            _state.update { if (it.pendingConfirmation?.id == pending.id) it.copy(pendingConfirmation = null) else it }
        }
        allowed
    }

    fun respondConfirmation(id: String, allowed: Boolean) {
        _state.update { s ->
            s.pendingConfirmation?.let { if (it.id == id) it.response.complete(allowed) }
            if (s.pendingConfirmation?.id == id) s.copy(pendingConfirmation = null) else s
        }
    }

    // --------------------------------------- runtime permissions (v0.6.14)

    /**
     * The PermissionBridge handler: asks Android for [permission] with the
     * standard one-tap dialog and returns whether the grant actually
     * happened. Fast path when already granted; 60s timeout = denied
     * (fail closed — callers keep their honest manual fallbacks).
     */
    private suspend fun permissionBridge(permission: String): Boolean {
        val pm = appContext.packageManager
        val alreadyGranted = pm.checkPermission(
            permission, appContext.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) return true

        val deferred = CompletableDeferred<Boolean>()
        val request = PendingPermissionRequest(
            id = java.util.UUID.randomUUID().toString(),
            permission = permission,
            response = deferred,
        )
        _state.update { it.copy(pendingPermissionRequest = request) }
        return try {
            withTimeout(60_000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            false
        } finally {
            _state.update {
                if (it.pendingPermissionRequest?.id == request.id)
                    it.copy(pendingPermissionRequest = null) else it
            }
        }
    }

    /** The UI reports the system dialog's result back to the waiting tool. */
    fun respondPermissionRequest(granted: Boolean) {
        _state.update { s ->
            s.pendingPermissionRequest?.response?.complete(granted)
            if (s.pendingPermissionRequest != null) s.copy(pendingPermissionRequest = null) else s
        }
    }

    // ------------------------------------------ Termux setup (v0.6.14)

    /** Live pm lookups for the Setup card — call after any external change. */
    fun refreshTermuxStatus() {
        _state.update {
            it.copy(
                termuxInstalled = TermuxProbe.termuxInstalled(appContext),
                runCommandGranted = TermuxProbe.runCommandGranted(appContext),
            )
        }
    }

    /** Setup → Termux integration → GRANT: the one-tap system dialog. */
    fun grantTermuxPermission() {
        viewModelScope.launch {
            permissionBridge(TermuxProbe.RUN_COMMAND_PERMISSION)
            refreshTermuxStatus()
        }
    }

    /** Setup → Termux integration → manual fallback: open Vorv's Settings page. */
    fun openAppSettings() {
        runCatching {
            appContext.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${appContext.packageName}"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Setup → Termux integration → TEST. Runs exactly `echo vorv-termux-ok`
     * in Termux (the button label states the command — that label is the
     * consent surface) and puts the complete honest verdict in state.
     */
    fun testTermuxConnection() {
        if (_state.value.termuxTesting) return
        _state.update { it.copy(termuxTesting = true, termuxTestResult = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = TermuxProbe.runProbe(appContext, _state.value.workspaceUri.takeIf { it.isNotBlank() })
            _state.update {
                it.copy(
                    termuxTesting = false,
                    termuxTestResult = result,
                    // The probe may have been the first real dispatch — refresh
                    // the status readout too.
                    termuxInstalled = TermuxProbe.termuxInstalled(appContext),
                    runCommandGranted = TermuxProbe.runCommandGranted(appContext),
                )
            }
        }
    }

    // ------------------------------------------------------- send / tasks

    /**
     * Send a chat message — kicks off the ReAct kernel.
     * Gating is honest: local mode needs the downloaded model; cloud mode needs an API key.
     * Overall task timeout is mode-aware (audit M-3).
     */
    fun send(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || _state.value.busy) return

        val s = _state.value
        val engineAvailable = when (s.llmMode) {
            "local" -> s.localModelReady
            else -> s.hasApiKey
        }
        if (!engineAvailable) {
            val hint = if (s.llmMode == "local") {
                "Local mode is selected but the model isn't downloaded yet.\n\nOpen Setup → Download local model (~1.8–4.4 GB, one time). Or switch to Cloud and paste a Z.ai API key."
            } else {
                "Cloud mode needs a Z.ai API key.\n\nOpen Setup → paste your key from z.ai/manage-apikey → Save. Or download the local model for offline mode."
            }
            _state.update {
                it.copy(messages = it.messages + ChatMessage(role = "user", content = trimmed) + ChatMessage(role = "assistant", content = hint))
            }
            return
        }

        val userMsg = ChatMessage(role = "user", content = trimmed)
        _state.update {
            it.copy(
                messages = it.messages + userMsg,
                busy = true,
                liveTrace = emptyList(),
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val task = AgentTask(prompt = trimmed, status = "in_progress", startedAt = System.currentTimeMillis())
            db.taskDao().insert(task)

            host.vorv.service.AgentService.start(appContext, "Working: ${trimmed.take(40)}")

            try {
                val mode = prefs.llmMode.first()
                val key = secrets.zaiApiKey.first()
                val choice = ModelChoice.fromId(prefs.localModelId.first())
                val wsUri = _state.value.workspaceUri.ifBlank { null }

                modelRepository.markBusy(true)
                val llm: Llm = modelRepository.activeEngine(mode, key, choice)

                val timeoutMs = if (mode == "local") LOCAL_TASK_TIMEOUT_MS else CLOUD_TASK_TIMEOUT_MS
                val result = try {
                    withTimeout(timeoutMs) {
                        kernel.run(task.id, trimmed, llm, wsUri, confirm = ::requestConfirmation)
                    }
                } catch (e: TimeoutCancellationException) {
                    val msg = "Task timed out after ${timeoutMs / 60000} minutes and was stopped. " +
                        "Partial tool-call history is preserved in the Tasks tab."
                    host.vorv.kernel.KernelApi.TaskResult(false, msg, 0, emptyList(), 0, error = "timeout")
                }

                val assistantMsg = ChatMessage(
                    role = "assistant",
                    content = result.answer,
                    citations = result.citations,
                )
                _state.update {
                    it.copy(
                        messages = it.messages + assistantMsg,
                        busy = false,
                        liveTrace = emptyList(),
                    )
                }
                db.taskDao().complete(task.id, result.answer.take(500), if (result.ok) "completed" else "failed")
            } catch (e: Exception) {
                val errMsg = ChatMessage(role = "assistant", content = "Error: ${e.message}")
                _state.update { it.copy(messages = it.messages + errMsg, busy = false, liveTrace = emptyList()) }
                db.taskDao().fail(task.id, e.message ?: "unknown")
            } finally {
                modelRepository.markBusy(false)
                host.vorv.service.AgentService.stop(appContext)
            }
        }
    }

    // ------------------------------------------------------- task details / full output

    /** Expand a task card and load its verifiable tool-call log (M-7 + acceptance test #8 UI). */
    fun toggleTaskDetails(taskId: String) {
        if (_state.value.expandedTaskId == taskId) {
            _state.update { it.copy(expandedTaskId = null, toolCallsForTask = emptyList()) }
            return
        }
        _state.update { it.copy(expandedTaskId = taskId) }
        viewModelScope.launch(Dispatchers.IO) {
            val calls = db.toolCallDao().forTask(taskId)
            _state.update { if (it.expandedTaskId == taskId) it.copy(toolCallsForTask = calls) else it }
        }
    }

    /** Load a persisted full tool output into the viewer dialog (M-7). */
    fun viewFullOutput(title: String, path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = try {
                File(path).readText().take(200_000)
            } catch (e: Exception) {
                "(could not read output file: ${e.message})"
            }
            _state.update { it.copy(fullOutputView = FullOutputView(title, content)) }
        }
    }

    fun dismissFullOutput() {
        _state.update { it.copy(fullOutputView = null) }
    }

    // ------------------------------------------------------- memory / data

    /**
     * Teach Vorv a fact directly — stores as high-confidence memory.
     * Logged as a real ToolCall row so the verifiable log stays complete
     * (audit finding M-10).
     */
    fun teach(topic: String, content: String) {
        if (topic.isBlank() || content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val tool = tools.firstOrNull { it.name == "memory_write" } ?: return@launch
            val input = com.google.gson.JsonObject().apply {
                addProperty("topic", topic)
                addProperty("content", content)
            }
            val teachTaskId = "teach-${java.util.UUID.randomUUID()}"
            val result = tool.run(
                input,
                host.vorv.tools.ToolContext(teachTaskId, 0, _state.value.workspaceUri)
            )
            db.toolCallDao().insert(ToolCall(
                taskId = teachTaskId,
                iteration = 0,
                toolName = "memory_write",
                inputJson = input.toString(),
                outputJson = result.output.take(2000),
                exitCode = if (result.ok) 0 else 1,
                durationMs = System.currentTimeMillis() - t0,
                errorMessage = result.error,
            ))
            db.episodeDao().insert(Episode(kind = "lesson", summary = "User taught: $topic"))
        }
    }

    /**
     * Clear ALL data (audit M-4 — now actually ALL of it): every table, every
     * scheduled reminder (WorkManager unique work + the registry), every
     * persisted tool output file, and the crash/watchdog diagnostics.
     * The API key is a SETTING, not agent data — it is kept, and the dialog
     * says so. The confirmation goes through the same consent bridge the
     * tools use.
     */
    fun clearAllData() {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            val allowed = requestConfirmation(
                "Delete ALL Vorv data — every memory, task, tool-call log and skill stat, " +
                    "all scheduled reminders, tool output files and crash diagnostics? " +
                    "This cannot be undone. Your API key and settings are KEPT " +
                    "(they are settings, not agent data)."
            )
            if (!allowed) return@launch

            // 1. Cancel every registered reminder's WorkManager unique work.
            runCatching {
                val wm = androidx.work.WorkManager.getInstance(appContext)
                reminderRegistry.takeForCancel(null).forEach { wm.cancelUniqueWork(it.name) }
            }
            // 2. Clear the reminder registry preference itself.
            runCatching { prefs.setScheduledReminders("[]") }
            // 3. Recursively delete persisted tool outputs.
            runCatching { outputDir.deleteRecursively() }
            // 4. Purge crash logs + watchdog diagnostics.
            runCatching {
                File(appContext.getExternalFilesDir(null), "crash_logs").deleteRecursively()
                File(appContext.getExternalFilesDir(null), "watchdog").deleteRecursively()
                File(appContext.getExternalFilesDir(null), "healer").deleteRecursively()
            }
            // 5. Wipe every table.
            runCatching { withContext(Dispatchers.IO) { db.clearAllTables() } }
            db.episodeDao().insert(Episode(kind = "boot", summary = "All data cleared by user"))
        }
    }

    override fun onCleared() {
        // Release native memory + the headless WebView (audit L-7).
        tools.filterIsInstance<BrowserAutomateTool>().forEach { it.destroy() }
        modelRepository.markBusy(false)
        // shutdown() is suspend — run it on the application scope, which
        // outlives the ViewModel.
        (appContext as? VorvApplication)?.appScope?.launch(Dispatchers.IO) {
            runCatching { modelRepository.shutdown() }
        }
        super.onCleared()
    }

    private fun engineLabel(mode: String, ready: Boolean, hasApiKey: Boolean, choice: ModelChoice): String {
        return when (mode) {
            "local" -> if (ready) {
                "${choice.displayName.substringBefore("(").trim()} (NPU, offline)"
            } else {
                "Local — ${choice.displayName.substringBefore("(").trim()} not pulled"
            }
            else -> if (hasApiKey) "GLM-4.6 (cloud)" else "Cloud — needs API key"
        }
    }

    companion object {
        private const val CLOUD_TASK_TIMEOUT_MS = 5 * 60_000L
        private const val LOCAL_TASK_TIMEOUT_MS = 15 * 60_000L
        private const val RETENTION_DAYS = 30

        fun factory(
            db: VorvDatabase,
            prefs: VorvPrefs,
            secrets: SecretsStore,
            modelRepository: ModelRepository,
            ctx: Context,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AgentViewModel(db, prefs, secrets, modelRepository, ctx.applicationContext) as T
        }
    }
}
