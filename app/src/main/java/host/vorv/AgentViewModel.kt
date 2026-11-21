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
import host.vorv.llm.BrainChoice
import host.vorv.llm.BrainDoctor
import host.vorv.llm.BrainFacts
import host.vorv.llm.LlmHttpException
import host.vorv.llm.explainLlmFailure
import host.vorv.llm.CloudLlm
import host.vorv.llm.CloudModel
import host.vorv.llm.FallbackLlm
import host.vorv.llm.Llm
import host.vorv.llm.ModelChoice
import host.vorv.llm.ModelManager
import host.vorv.llm.ModelRepository
import host.vorv.tools.AppIntentTool
import host.vorv.tools.BrainTool
import host.vorv.tools.BrowserAutomateTool
import host.vorv.tools.ComputerTool
import host.vorv.tools.WebTestTool
import host.vorv.tools.BuildAppTool
import host.vorv.tools.LinuxRunTool
import host.vorv.tools.NativeTestTool
import host.vorv.tools.CrashLogsTool
import host.vorv.tools.FileListTool
import host.vorv.tools.FileReadTool
import host.vorv.tools.FileWriteTool
import host.vorv.tools.JsExecTool
import host.vorv.tools.MemoryRecallTool
import host.vorv.tools.MemoryWebTool
import host.vorv.tools.MemoryWriteTool
import host.vorv.tools.NotifyTool
import host.vorv.tools.PermissionBridge
import host.vorv.tools.PythonExecTool
import host.vorv.tools.ReminderRegistry
import host.vorv.tools.ScheduleCancelTool
import host.vorv.tools.ScheduleTool
import host.vorv.tools.SelfFixTool
import host.vorv.tools.SessionGrants
import host.vorv.tools.ShellTool
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
    /**
     * v0.6.15: true only for SHELL consents — the dialog then offers the
     * third button "Allow all this session" (SessionGrants). Set by
     * [AgentViewModel.requestConfirmation] from ShellTool.CONSENT_PREFIX,
     * the single source of truth — no other tool's dialog ever gets it.
     */
    val offerSessionGrant: Boolean = false,
)

/**
 * A pending ANDROID runtime-permission dialog (v0.6.14). The UI observes
 * this, calls Activity.requestPermissions(), and reports back through
 * [AgentViewModel.respondPermissionRequest]. For any runtime grant a tool
 * needs — the standard one-tap dialog, never a Settings walk.
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
    /** Step 9 — what the Brain screen shows: champion, generations, runs. */
    val brain: host.vorv.learn.BrainReport.View? = null,
    /** A training run is going right now (read back from the run log). */
    val brainBusy: Boolean = false,
    /** The last thing the Brain screen did, in the owner's words. */
    val brainNote: String = "",
    /** Step 7 — what the learning ledger actually holds, read from the table. */
    val ledgerRows: Int = 0,
    val ledgerExamples: Int = 0,
    val ledgerValRows: Int = 0,
    /** What the last export did, in the owner's words. Never a silent success. */
    val exportNote: String = "",
    val skillCount: Int = 0,
    /** Number of tools actually registered — the Setup text quotes this, not a
     *  remembered number (F-10: it said 17 while the registry held 24). */
    val toolCount: Int = 0,
    val recentTasks: List<AgentTask> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val llmMode: String = "cloud",       // "local" | "cloud"
    val hasApiKey: Boolean = false,
    /** v0.7.0 — selected Z.ai cloud model (free glm-4.7-flash by default). */
    val cloudModelId: String = CloudModel.GLM_4_7_FLASH.id,
    /** 0.9.13 — a different provider's address; blank keeps Z.ai. */
    val cloudBaseUrl: String = "",
    /** 0.9.13 — that provider's model name; blank keeps the Z.ai chain. */
    val cloudCustomModel: String = "",
    /** v0.7.0 — cloud→offline fallback armed (needs a downloaded local model). */
    val autoFallback: Boolean = true,
    /** v0.7.0 — key+model probe in flight (Setup screen). */
    val keyTesting: Boolean = false,
    /** v0.7.0 — verbatim result of the last key/model probe (Setup screen). */
    val keyTestResult: String? = null,
    val localModelReady: Boolean = false,
    val downloadingModel: Boolean = false,
    val downloadProgress: Int = 0,
    val selectedModelId: String = "omnicoder9b",
    val downloadedModelIds: Set<String> = emptySet(),
    val workspaceUri: String = "",
    /** 0.9.18 — which provider the cloud brain uses ("" = the Z.ai preset). */
    val providerId: String = "z.ai",
    /** 0.9.18 — model ids the provider itself listed; empty until refreshed. */
    val catalogIds: List<String> = emptyList(),
    val catalogLoading: Boolean = false,
    /** 0.9.18 — why the model list could not be read. Shown, never swallowed. */
    val catalogError: String? = null,
    /** 0.9.18 — the encrypted key store itself failed; distinct from "no key". */
    val keyReadError: String? = null,
    /** 0.9.18 — the same facts the send gate uses, shown in Setup → Brain health. */
    val brainChecks: List<BrainDoctor.Check> = emptyList(),
    /** 0.9.18 — the Copy-button text for Brain health. */
    val brainReport: String = "",
    val currentLlmLabel: String = "GLM-4.7-Flash (cloud, free)",
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
    /** The app carries the Linux payload (proot + Debian rootfs). */
    val linuxAvailable: Boolean = false,
    /** The guest filesystem has been unpacked and stamped. */
    val linuxProvisioned: Boolean = false,
    /** A Linux-system test is running (Setup tab). */
    val linuxTesting: Boolean = false,
    /** Verbatim result of the last Linux-system test (Setup tab). */
    val linuxTestResult: String? = null,
    /** What the payload check actually looked for, worded for the Setup card. */
    val linuxDiagnostics: String? = null,
    /** The Debian build tools are downloading/unpacking into the guest. */
    val toolchainInstalling: Boolean = false,
    /** One live line: "12/169 packages — 8.4 MB of 203.0 MB — fetching libc6". */
    val toolchainProgress: String? = null,
    /** javac and aapt really exist in the guest right now. */
    val toolchainInstalled: Boolean = false,
    /** Verbatim outcome of the last install attempt, shown as-is. */
    val toolchainResult: String? = null,
    /** The APK carries proot even if Android has not unpacked it (two different facts). */
    val linuxPayloadInApk: Boolean = false,
    /** A real command completed inside the guest this session (knowledge). */
    val shellVerifiedThisSession: Boolean = false,
    /** v0.6.17: the dead-shell circuit breaker is active (live in Setup). */
    val shellBlockedThisSession: Boolean = false,
    /** v0.6.15: the user granted all shell commands for this session. */
    val shellSessionGrant: Boolean = false,
    /** v0.6.15: commands run under the session grant (audit counter). */
    val shellAutoRunCount: Int = 0,
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

    /**
     * 0.9.18 — provider health and the list of models a provider actually
     * serves. Both are off-device-testable classes; the ViewModel only wires
     * them to Setup.
     */
    private val router = host.vorv.llm.provider.ProviderRouter()
    private val catalog = host.vorv.llm.provider.ModelCatalog(
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .dns(host.vorv.tools.NetworkGuard.PublicDns)
            .followRedirects(false)
            .build(),
    )

    /** Vorv's own computer: one instance, shared by the shell and linux_run. */
    private val linuxSystem = host.vorv.linux.LinuxSystem(appContext)

    private val tools = listOf(
        MemoryWriteTool(db),
        MemoryRecallTool(db),
        MemoryWebTool(db.knowledgeGraphDao()),
        JsExecTool(),
        ComputerTool(appContext),
        WebTestTool(appContext),
        BuildAppTool(appContext),
        LinuxRunTool(linuxSystem),
        BrainTool(appContext),
        NativeTestTool(appContext),
        FileReadTool(appContext),
        FileWriteTool(appContext),
        FileListTool(appContext),
        CrashLogsTool(appContext),
        PythonExecTool(appContext),
        WebSearchTool(secrets),
        WebFetchTool(),
        AppIntentTool(appContext),
        ShellTool(linuxSystem, appContext),
        NotifyTool(appContext),
        ScheduleTool(appContext, reminderRegistry),
        ScheduleCancelTool(appContext, reminderRegistry),
        BrowserAutomateTool(appContext),
        SelfFixTool(appContext, prefs),
    )

    private val outputDir = File(appContext.cacheDir, "tool_outputs")

    // v0.8.0 — the knowledge web's learning half: every chat message and
    // every task outcome feeds the associative graph (owner spec,
    // 2026-11-09). All ingestion is wrapped in runCatching by the callers —
    // a failure to learn must NEVER fail a task.
    private val memoryLearner = host.vorv.knowledge.MemoryLearner(db.knowledgeGraphDao())

    // v0.6.8 dispatch: the kernel is created through PatchRegistry so a
    // verified, approved patch of CognitiveKernel actually RUNS (same-FQCN
    // classes under different classloaders are different runtime identities —
    // dispatching through the shared KernelApi interface is the only way a
    // patched kernel can be used). Falls back to the original on any failure.
    //
    // C-1 (code review, 2026-11): the field used to be typed CognitiveKernel
    // and re-cast the PatchRegistry result with `as? CognitiveKernel`. A
    // hot-patched class loads under its OWN ClassLoader, so it is never an
    // instanceof the original class — the cast always failed for a real
    // patch, the freshly built patched kernel was silently discarded, and
    // self-patch dispatch stayed dead code for the kernel no matter how
    // correct the generated patch was. `trace` and `emitTelemetry` now live
    // on KernelApi, so the field holds the interface type and there is no
    // cross-classloader cast left to fail.
    private val kernel: host.vorv.kernel.KernelApi =
        host.vorv.watchdog.PatchRegistry.create(
            host.vorv.kernel.KernelApi::class.java,
            CognitiveKernel::class.java.name,
            RoomKernelEventSink(db), tools, outputDir,
        ) ?: CognitiveKernel(RoomKernelEventSink(db), tools, outputDir)

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /** The one consent dialog: asked by a tool, answered by the screen, timed out as "no". */
    private val consent = host.vorv.agent.ConsentGate(_state)

    /**
     * v0.6.16 (field finding #5 — the missing stop button): the running
     * task's Job, kept so the user can CANCEL it. Before this, a task
     * could only run to its iteration cap or its multi-minute timeout —
     * the field recording shows a dead-shell retry loop the user could
     * not stop (the chat send button is disabled while busy, and typed
     * "stop" was silently dropped). Null when idle.
     */
    @Volatile
    private var currentTaskJob: kotlinx.coroutines.Job? = null

    /** M1: serializer for chat-message citations (persisted as JSON). */
    private val chatGson = com.google.gson.Gson()

    /**
     * M1 (code review, 2026-11): fire-and-forget persistence of one chat
     * message. Failure to persist NEVER breaks the chat — the message still
     * appears; the honest trade-off is logged, not thrown.
     */
    /** Last turn of each side, used only to recognise a correction. */
    @Volatile private var lastUserText: String? = null
    @Volatile private var lastAssistantText: String? = null

    private val learningStore by lazy { host.vorv.learn.LearningStore(db.learningDao()) }

    private fun persistChat(msg: ChatMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                db.chatMessageDao().insert(
                    host.vorv.data.entity.ChatMessageEntity(
                        id = msg.id, ts = msg.ts, role = msg.role,
                        content = msg.content,
                        citationsJson = chatGson.toJson(msg.citations),
                    )
                )
            }.onFailure {
                android.util.Log.w("Vorv/Chat", "chat persist failed: ${it.message}")
            }
            // Step 6: every turn becomes a ledger row in the same minute. A
            // failure here is logged, never swallowed silently.
            try {
                recordLearning(msg)
                refreshLedgerCounts()
            } catch (t: Throwable) {
                android.util.Log.w("Vorv/Learn", "ledger write failed: ${t.message}", t)
            }
        }
    }

    /** Reads the counts back from the table, so the screen never guesses. */
    private suspend fun refreshLedgerCounts() {
        val stats = learningStore.stats()
        _state.update {
            it.copy(
                ledgerRows = stats.rows,
                ledgerExamples = stats.examples,
                ledgerValRows = stats.valRows,
            )
        }
    }

    /**
     * Step 7 — write the corpus to a file the owner can send to the trainer,
     * then show what happened. An empty ledger produces no file and a sentence
     * that says why, never a green button over nothing.
     */
    fun saveTrainingFile() {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = try {
                learningStore.rows()
            } catch (t: Throwable) {
                _state.update { it.copy(exportNote = "Could not read the learning table: ${t.message}") }
                return@launch
            }
            val jsonl = host.vorv.learn.LearningLedger.exportJsonl(rows)
            if (jsonl.isBlank()) {
                _state.update {
                    it.copy(exportNote = "Nothing to export yet: no answerable rows in the ledger. Talk to Vorv first.")
                }
                return@launch
            }
            val result = host.vorv.learn.CorpusExport.write(appContext, jsonl, rows.size)
            _state.update { it.copy(exportNote = result.message) }
            val file = result.file
            if (result.ok && file != null) {
                val intent = host.vorv.learn.CorpusExport.shareIntent(appContext, file)
                if (intent != null) {
                    try {
                        appContext.startActivity(intent)
                    } catch (t: Throwable) {
                        android.util.Log.w("Vorv/Learn", "share sheet failed: ${t.message}", t)
                    }
                }
            }
        }
    }

    private suspend fun recordLearning(msg: ChatMessage) {
        val kind: host.vorv.learn.LearningLedger.Kind
        val input: String
        val output: String
        if (msg.role == "user") {
            val previous = lastAssistantText
            if (host.vorv.learn.LearningLedger.isCorrection(msg.content, previous)) {
                kind = host.vorv.learn.LearningLedger.Kind.CORRECTION
                input = previous.orEmpty()
                output = msg.content
            } else {
                kind = host.vorv.learn.LearningLedger.Kind.CHAT_USER
                input = msg.content
                output = ""
            }
            lastUserText = msg.content
        } else {
            kind = host.vorv.learn.LearningLedger.Kind.CHAT_ASSISTANT
            input = lastUserText.orEmpty()
            output = msg.content
            lastAssistantText = msg.content
        }
        learningStore.record(
            host.vorv.learn.LearningLedger.Row(
                ts = msg.ts,
                kind = kind,
                sessionId = "chat",
                inputText = input,
                outputText = output,
                engineLabel = "",
            )
        )
    }

    // M3 (code review, 2026-11): the exact bridge lambdas THIS ViewModel
    // installed. Kept so onCleared() can clear the singleton references
    // ONLY when they still point into this instance — never nulling a
    // newer ViewModel's handlers in a recreation race.
    private val installedPermissionHandler: suspend (String) -> Boolean = ::permissionBridge
    private val installedAuditSink: suspend (String, String, String) -> Unit =
        { kind, summary, detailsJson ->
            runCatching { db.episodeDao().insert(Episode(kind = kind, summary = summary, detailsJson = detailsJson)) }
        }

    init {
        // Step 7: the Brain numbers are read from the table at boot, not
        // carried over from a previous process's opinion.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                refreshLedgerCounts()
            } catch (t: Throwable) {
                android.util.Log.w("Vorv/Learn", "ledger read failed at boot: ${t.message}", t)
            }
        }
        // v0.6.14: install the runtime-permission bridge BEFORE any tool can
        // run. Tools call PermissionBridge.request() when they hit the
        // RUN_COMMAND wall; the handler shows Android's one-tap dialog and
        // reports back. See PermissionBridge.kt for why this is a
        // process-level reference rather than a ToolContext field
        // (binary compatibility with registered patches).
        @Suppress("LeakingThis")
        PermissionBridge.handler = installedPermissionHandler
        refreshLinuxStatus()

        // 0.9.18 — Brain health survives a restart, and every fact a brain
        // publishes re-renders the header through the ONE resolution.
        // H-4: the record lives in noBackupFilesDir, so it survives a restart
        // but never enters a cloud backup or a device-to-device transfer.
        viewModelScope.launch(Dispatchers.IO) {
            val store = host.vorv.llm.BrainFactsStore(appContext)
            runCatching { store.migrateLegacy(prefs) }
                .onFailure { android.util.Log.w("VorvBrainFacts", "legacy brain-facts migration failed: ${it.message}") }
            host.vorv.llm.BrainState.attachStore(save = store::save, load = store::load)
        }
        viewModelScope.launch {
            host.vorv.llm.BrainState.facts.collect {
                _state.update { s -> refresh(s) }
            }
        }

        // v0.6.15: the session-grant audit channel — every command executed
        // under "Allow all shell commands this session" lands in the episode
        // log with its COMPLETE text. The grant replaces the per-command
        // dialog, never the record of what ran. Boot state starts clean: a
        // grant is a live user choice, not something a previous process
        // decided for this one.
        @Suppress("LeakingThis")
        SessionGrants.auditSink = installedAuditSink

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
        _state.update { it.copy(toolCount = tools.size) }
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
            // 0.9.18 — "has a key" means the SELECTED provider has one. The
            // old collector watched the single Z.ai slot, so choosing another
            // provider reported "no key" while its key sat in the same file.
            secrets.keys.collect {
                val id = _state.value.providerId.ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
                _state.update { st ->
                    refresh(st.copy(hasApiKey = secrets.keyFor(id).isNotBlank()))
                }
            }
        }
        viewModelScope.launch {
            secrets.readError.collect { err ->
                _state.update { st -> refresh(st.copy(keyReadError = err)) }
            }
        }
        viewModelScope.launch {
            prefs.cloudProviderId.collect { id ->
                val real = id.ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
                _state.update { st ->
                    refresh(st.copy(providerId = real, hasApiKey = secrets.keyFor(real).isNotBlank()))
                }
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
                _state.update { prev ->
                    val next = prev.copy(llmMode = mode, localModelReady = ready)
                    refresh(next, choice)
                }
            }
        }
        viewModelScope.launch {
            // v0.7.0: cloud model + fallback preference → state + honest label.
            prefs.cloudModelId.collect { id ->
                _state.update { prev ->
                    val next = prev.copy(cloudModelId = id)
                    refresh(next)
                }
            }
        }
        viewModelScope.launch {
            prefs.cloudBaseUrl.collect { url ->
                _state.update { prev ->
                    val next = prev.copy(cloudBaseUrl = url)
                    refresh(next)
                }
            }
        }
        viewModelScope.launch {
            prefs.cloudCustomModel.collect { name ->
                _state.update { prev ->
                    val next = prev.copy(cloudCustomModel = name)
                    refresh(next)
                }
            }
        }
        viewModelScope.launch {
            prefs.autoFallback.collect { enabled ->
                _state.update { prev ->
                    val next = prev.copy(autoFallback = enabled)
                    refresh(next)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // M1 (code review, 2026-11): chat history is PERSISTENT. Reload
            // the conversation on startup — a restart used to lose every
            // message while tool-call logs survived. Guarded with
            // messages.isEmpty() so a fast in-flight send never gets
            // overwritten by the (older) disk snapshot.
            val saved = runCatching { db.chatMessageDao().loadRecent() }.getOrDefault(emptyList())
            if (saved.isNotEmpty()) {
                val restored = saved.map { e ->
                    ChatMessage(
                        id = e.id,
                        role = e.role,
                        content = e.content,
                        citations = runCatching {
                            chatGson.fromJson<List<host.vorv.tools.Citation>>(
                                e.citationsJson,
                                object : com.google.gson.reflect.TypeToken<List<host.vorv.tools.Citation>>() {}.type,
                            )
                        }.getOrNull() ?: emptyList(),
                        ts = e.ts,
                    )
                }
                _state.update { s -> if (s.messages.isEmpty()) s.copy(messages = restored) else s }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // One-shot cleanup of orphaned files from prior versions. Safe to
            // call every boot — only deletes files matching the LEGACY list.
            ModelManager.cleanupLegacyModelsDir(appContext)
            // Owner decision (2026-11): the Qwen 7B/3B weights are deleted from
            // devices that still hold them, before anything can pull again.
            ModelManager.cleanupRemovedModels(appContext)
            // A prefs value from an older build ("qwen7b"/"qwen3b") is not a
            // model this build has; normalise it once instead of carrying aliases.
            val stored = prefs.localModelId.first()
            if (ModelChoice.entries.none { it.id == stored }) {
                prefs.setLocalModelId(ModelChoice.OMNICODER9B.id)
            }
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
                _state.update { prev ->
                    val next = prev.copy(selectedModelId = id, localModelReady = ready)
                    refresh(next, choice)
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

    // ---- Step 9: the Brain screen -------------------------------------------------

    /** Re-reads the brain from disk and the run log from the database. */
    fun refreshBrain() {
        viewModelScope.launch(Dispatchers.IO) {
            val view = try {
                val runs = db.trainingRunDao().recent(30).map {
                    host.vorv.learn.BrainReport.Run(
                        id = it.id, startedAt = it.startedAt, state = it.state,
                        generation = it.generation, rung = it.rung, corpusRows = it.corpusRows,
                        trainLoss = it.trainLoss, valLoss = it.valLoss, seconds = it.seconds,
                        message = it.message,
                    )
                }
                host.vorv.learn.BrainReport.build(host.vorv.learn.BrainPaths.dir(appContext), runs)
            } catch (t: Throwable) {
                _state.update { it.copy(brainNote = "Could not read the brain: ${t.javaClass.simpleName}: ${t.message}") }
                return@launch
            }
            _state.update { it.copy(brain = view, brainBusy = view.runs.any { it.state == "running" }) }
        }
    }

    /** The owner's own run, allowed on battery; the nightly one waits for the charger. */
    fun trainNow(steps: Int = host.vorv.learn.TrainingWorker.DEFAULT_STEPS) {
        viewModelScope.launch {
            val rows = try {
                learningStore.count()
            } catch (t: Throwable) {
                android.util.Log.w("Vorv/Learn", "ledger count failed: ${t.message}", t)
                -1
            }
            if (rows == 0) {
                _state.update {
                    it.copy(brainNote = "Nothing to learn from yet: the ledger is empty. Talk to Vorv first.")
                }
                return@launch
            }
            if (rows < 0) {
                _state.update { it.copy(brainNote = "Could not read the learning table, so no training was started.") }
                return@launch
            }
            _state.update {
                it.copy(
                    brainBusy = true,
                    brainNote = "Training queued on $rows ledger rows. It runs when the phone is charging and cool.",
                )
            }
            host.vorv.learn.TrainingWorker.enqueue(appContext, steps = steps, chargingOnly = false)
        }
    }

    /** One tap back to the previous accepted generation. */
    fun rollbackBrain() {
        viewModelScope.launch(Dispatchers.IO) {
            val root = try {
                host.vorv.learn.BrainPaths.dir(appContext)
            } catch (t: Throwable) {
                _state.update { it.copy(brainNote = "Could not open the brain folder: ${t.message}") }
                return@launch
            }
            val result = host.vorv.learn.GenerationStore(root).rollback()
            var note = when (result) {
                is host.vorv.learn.GenerationStore.Result.Ok -> result.message
                is host.vorv.learn.GenerationStore.Result.Refused -> result.message
            }
            if (result is host.vorv.learn.GenerationStore.Result.Ok) {
                result.generation?.let { gen ->
                    // The generation store already moved its pointer; this
                    // mirrors it for the weights reader and a failure is shown.
                    try {
                        host.vorv.learn.WeightsStore.setActive(root, gen.id)
                    } catch (t: Throwable) {
                        note += " The weights pointer could not be updated: ${t.message}"
                    }
                }
            }
            _state.update { it.copy(brainNote = note) }
            refreshBrain()
        }
    }

    /**
     * Step 10: the owner picks the zip a GPU run produced. The phone copies it,
     * unwraps it, hashes the model, grades it with its own suite and records the
     * result — accepted or refused, in words. Nothing here trusts the file.
     */
    fun importBrainZip(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(brainBusy = true, brainNote = "Copying the GPU bundle…") }
            val root = try {
                host.vorv.learn.BrainPaths.dir(appContext)
            } catch (t: Throwable) {
                _state.update { it.copy(brainBusy = false, brainNote = "Could not open the brain folder: ${t.message}") }
                return@launch
            }
            val copy = java.io.File(appContext.cacheDir, "gpu-bundle-${System.currentTimeMillis()}.zip")
            try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("the file picker returned nothing for this file")
                input.use { from -> copy.outputStream().use { to -> from.copyTo(to) } }
            } catch (t: Throwable) {
                copy.delete()
                _state.update {
                    it.copy(
                        brainBusy = false,
                        brainNote = "The bundle could not be copied into the app: " +
                            "${t.javaClass.simpleName}: ${t.message}. Nothing was imported.",
                    )
                }
                return@launch
            }

            val champion = try {
                host.vorv.learn.GenerationStore(root).champion()?.let { gen ->
                    host.vorv.learn.Trainer(root).evalRecord(gen.id)
                        ?: host.vorv.learn.PromotionGate.Report(gen.id, emptyMap(), emptyMap(), gen.valLoss)
                }
            } catch (t: Throwable) {
                android.util.Log.w("Vorv/Learn", "could not read the champion's evaluation: ${t.message}", t)
                null
            }

            val result = try {
                host.vorv.learn.GpuIntake.import(copy, root, champion)
            } catch (t: Throwable) {
                android.util.Log.e("Vorv/Learn", "GPU import failed: ${t.message}", t)
                host.vorv.learn.GpuIntake.Result.Failed(
                    "The bundle was not imported: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
            copy.delete()
            val note = when (result) {
                is host.vorv.learn.GpuIntake.Result.Done ->
                    "GPU generation ${result.outcome.generation}: ${result.outcome.message}"

                is host.vorv.learn.GpuIntake.Result.Failed -> result.message
            }
            _state.update { it.copy(brainBusy = false, brainNote = note) }
            refreshBrain()
        }
    }

    fun setScreen(screen: String) {
        _state.update { it.copy(screen = screen) }
        if (screen == "brain") refreshBrain()
    }

    /**
     * v0.7.0: saving a key now VERIFIES it with a real round-trip against
     * the selected cloud model ([CloudLlm.probe]) before trusting it. Setup
     * shows the model's actual reply — or Z.ai's actual error — never a
     * guess. On success the engine switches to cloud automatically: pasting
     * a key is an unambiguous instruction to use cloud.
     */
    fun setApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            secrets.setZaiApiKey(key)
            // B-1 (code review, 2026-11): publish key presence IMMEDIATELY.
            // The hot SecretsStore flow also carries this update now, but
            // send()'s engineAvailable gate reads AgentState.hasApiKey —
            // setting it here means a freshly saved key is usable on the
            // very next message, with no dependency on collector scheduling.
            // This one line is the difference between "paste a key, get told
            // to paste a key" (the four-year first-run loop) and a working
            // default cloud mode.
            _state.update { it.copy(hasApiKey = key.isNotBlank()) }
            _state.update { it.copy(keyTesting = true, keyTestResult = null) }
            val modelId = prefs.cloudModelId.first()
            val probe = CloudLlm.probe(key, modelId)
            val cm = CloudModel.fromId(modelId)
            if (probe.isSuccess) {
                _state.update {
                    it.copy(
                        keyTesting = false,
                        keyTestResult = "✓ ${cm.displayName} replied “${(probe.getOrNull() ?: "").take(40)}” — key works. Cloud mode switched ON.",
                    )
                }
                prefs.setLlmMode("cloud")
            } else {
                val reason = probe.exceptionOrNull()?.message ?: "unknown error"
                _state.update { it.copy(keyTesting = false, keyTestResult = "✗ $reason") }
            }
        }
    }

    /**
     * v0.7.0: pick the cloud model. When a key exists the choice is verified
     * immediately with a real probe — an unavailable model id is caught at
     * selection time in Setup, not mid-task.
     */
    fun setCloudModel(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setCloudModelId(id)
            val pid = prefs.cloudProviderId.first()
                .ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
            val key = secrets.keyFor(pid)
            if (key.isBlank()) {
                // Saved, but nothing was proven — say exactly that.
                _state.update {
                    it.copy(keyTestResult = "✓ Model saved. No key for ${providerLabel(pid)}, so nothing was tested.")
                }
                return@launch
            }
            _state.update { it.copy(keyTesting = true, keyTestResult = null) }
            val base = prefs.cloudBaseUrl.first().ifBlank { ZAI_BASE_URL }
            val probe = CloudLlm.probeAt(key, base, id)
            val cm = CloudModel.fromId(id)
            _state.update {
                it.copy(
                    keyTesting = false,
                    keyTestResult = if (probe.isSuccess) {
                        "✓ ${cm.displayName} replied “${(probe.getOrNull() ?: "").take(40)}” — works with your key."
                    } else {
                        "✗ ${probe.exceptionOrNull()?.let { explainLlmFailure(it) } ?: "unknown error"}"
                    },
                )
            }
        }
    }

    /**
     * 0.9.13 — point the cloud brain at any provider that speaks the same
     * request format. Both fields are saved together and a key, if present,
     * is proven with one real call before the header claims it works.
     */
    fun setCloudProvider(baseUrl: String, model: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setCloudBaseUrl(baseUrl)
            prefs.setCloudCustomModel(model)
            val pid = prefs.cloudProviderId.first()
                .ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
            val key = secrets.keyFor(pid)
            if (model.isBlank()) {
                _state.update {
                    it.copy(keyTestResult = "✓ Address saved. No model name yet — type one or tap Refresh list.")
                }
                return@launch
            }
            if (key.isBlank()) {
                _state.update {
                    it.copy(keyTestResult = "✓ Address and model saved. No key for ${providerLabel(pid)}, so nothing was tested.")
                }
                return@launch
            }
            _state.update { it.copy(keyTesting = true, keyTestResult = null) }
            val probe = CloudLlm.probeAt(key, baseUrl.ifBlank { "https://api.z.ai/api/paas/v4" }, model)
            _state.update {
                it.copy(
                    keyTesting = false,
                    keyTestResult = probe.fold(
                        onSuccess = { text -> "✓ $model replied “${text.take(40)}” — works with your key." },
                        onFailure = { e -> "✗ $model: ${explainLlmFailure(e)}" },
                    ),
                )
            }
        }
    }

    /**
     * 0.9.18 — pick which provider the cloud brain talks to. A preset fills
     * its address AND its first suggested coder id: the previous provider's
     * model id does not exist here, so keeping it would only produce a 404.
     */
    fun selectProvider(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setCloudProviderId(id)
            // A 401 from the last provider says nothing about this one.
            host.vorv.llm.BrainState.forgetFailures()
            val p = host.vorv.llm.provider.ProviderRegistry.byId(id)
            // The previous provider's model id does not exist here, so it is
            // replaced (never kept) — a Z.ai id sent to OpenRouter is a 404.
            val nextModel = if (p != null && p.baseUrl.isNotBlank()) p.suggestions.firstOrNull().orEmpty() else ""
            if (p != null && p.baseUrl.isNotBlank()) {
                prefs.setCloudBaseUrl(p.baseUrl)
                prefs.setCloudCustomModel(nextModel)
            }
            _state.update { s ->
                refresh(
                    s.copy(
                        providerId = id,
                        cloudBaseUrl = p?.baseUrl?.ifBlank { s.cloudBaseUrl } ?: s.cloudBaseUrl,
                        cloudCustomModel = if (p != null && p.baseUrl.isNotBlank()) nextModel else s.cloudCustomModel,
                    ),
                )
            }
            // A provider with a saved key gets a real test — the header never
            // claims a coder works because a name is on file.
            if (secrets.keyFor(id).isNotBlank()) testProvider()
        }
    }

    /** Saves (encrypted) the key FOR THE SELECTED PROVIDER, then proves it. */
    fun saveProviderKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = _state.value.providerId.ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
            secrets.setProviderKey(id, key)
            _state.update { s ->
                refresh(s.copy(hasApiKey = key.isNotBlank(), keyTestResult = null))
            }
            _state.update { it.copy(keyTesting = true) }
            val base = _state.value.cloudBaseUrl.ifBlank { ZAI_BASE_URL }
            val model = _state.value.cloudCustomModel.ifBlank { CloudModel.fromId(_state.value.cloudModelId).id }
            val probe = CloudLlm.probeAt(key, base, model)
            val provider = host.vorv.llm.provider.ProviderRegistry.byId(id)
                ?: host.vorv.llm.provider.ProviderRegistry.byIdOrCustom(id, base)
            router.record(
                id,
                code = probe.exceptionOrNull()?.let { (it as? LlmHttpException)?.code },
                errorText = probe.exceptionOrNull()?.message ?: "",
            )
            _state.update {
                it.copy(
                    keyTesting = false,
                    keyTestResult = probe.fold(
                        onSuccess = { text ->
                            "✓ ${provider.label} answered “${text.take(40)}” with $model — this coder works."
                        },
                        onFailure = { e -> "✗ ${provider.label} · $model: ${explainLlmFailure(e)}" },
                    ),
                )
            }
            _state.update { s -> refresh(s) }
        }
    }

    /** Asks the provider what it serves and shows it; a failure is shown, not swallowed. */
    fun refreshProviderModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val s = _state.value
            val id = s.providerId.ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
            val provider = host.vorv.llm.provider.ProviderRegistry.byId(id)
                ?: host.vorv.llm.provider.ProviderRegistry.byIdOrCustom(id, s.cloudBaseUrl)
            _state.update { it.copy(catalogLoading = true, catalogError = null) }
            val listing = catalog.fetch(provider, secrets.keyFor(id))
            _state.update {
                it.copy(
                    catalogLoading = false,
                    catalogIds = listing.entries.map { e -> e.id },
                    catalogError = listing.error?.let { err ->
                        "Could not read the model list: $err. You can still type a model name."
                    },
                )
            }
        }
    }

    /** Runs the probe for whatever is selected now (Setup → Test this coder). */
    fun testProvider() {
        viewModelScope.launch(Dispatchers.IO) {
            val s = _state.value
            val id = s.providerId.ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
            val key = secrets.keyFor(id)
            if (key.isBlank()) {
                _state.update {
                    it.copy(keyTestResult = "✗ No key saved for ${providerLabel(id)} — paste one and tap Save & Test.")
                }
                return@launch
            }
            _state.update { it.copy(keyTesting = true, keyTestResult = null) }
            val base = s.cloudBaseUrl.ifBlank { ZAI_BASE_URL }
            val model = s.cloudCustomModel.ifBlank { CloudModel.fromId(s.cloudModelId).id }
            val probe = CloudLlm.probeAt(key, base, model)
            router.record(id, (probe.exceptionOrNull() as? LlmHttpException)?.code)
            _state.update {
                it.copy(
                    keyTesting = false,
                    keyTestResult = probe.fold(
                        onSuccess = { text -> "✓ $model replied “${text.take(40)}” — works with your key." },
                        onFailure = { e -> "✗ $model: ${explainLlmFailure(e)}" },
                    ),
                )
            }
            _state.update { st -> refresh(st) }
        }
    }

    private fun providerLabel(id: String): String =
        host.vorv.llm.provider.ProviderRegistry.byId(id)?.label ?: id

    /**
     * Gate 0 — the chosen provider first, then every other provider this owner
     * holds a key for. One refused key or one outage is then a detour, not a
     * dead end, and nothing is invented: a provider with no stored key is left
     * out of the chain.
     */
    private suspend fun providerFallbacks(
        baseUrl: String,
        customModel: String,
        cloudModel: CloudModel,
    ): List<host.vorv.llm.provider.ProviderChainLlm.Route> {
        val routes = host.vorv.tools.Attempt.of("list the providers this owner holds keys for") {
            val pid = prefs.cloudProviderId.first()
                .ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
            val chosen = host.vorv.llm.provider.ProviderRegistry.byIdOrCustom(pid, baseUrl)
            val target = host.vorv.llm.provider.BrainTarget.resolve(baseUrl, customModel, cloudModel.id)
            host.vorv.llm.provider.ProviderChainLlm.routes(
                chosen = chosen,
                chosenBaseUrl = target.baseUrl,
                chosenModelId = target.modelId,
                chosenUsesModelChain = target.useChain,
                keyFor = { p -> secrets.keyFor(p.id) },
            )
        }
        return routes ?: emptyList()
    }

    /**
     * A provider swap is never silent: the episode log keeps the reason, and
     * the header stops claiming the chosen provider answered.
     */
    private fun recordProviderFailover(answeredBy: String, reason: String) {
        _state.update {
            it.copy(currentLlmLabel = "$answeredBy (cloud) — your chosen provider did not answer")
        }
        viewModelScope.launch(Dispatchers.IO) {
            host.vorv.tools.Attempt.of("record in the episode log which provider answered") {
                db.episodeDao().insert(
                    Episode(
                        kind = "fallback",
                        summary = "Your provider did not answer — $answeredBy answered instead",
                        detailsJson = "{\"answered_by\":\"${answeredBy.replace("\"", "'")}\"," +
                            "\"reason\":\"${reason.replace("\"", "'").take(300)}\"}",
                    )
                )
            }
        }
    }

    /**
     * Turns an exception into the sentence an owner can act on. The old UI
     * printed the exception text, so "HTTP 401" and "retired model" looked
     * alike.
     */


    /** v0.7.0 — arm/disarm the cloud→offline fallback (cloud mode only). */
    fun setAutoFallback(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { prefs.setAutoFallback(enabled) }
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
            _state.update { prev ->
                val next = prev.copy(selectedModelId = id, localModelReady = ready)
                refresh(next, choice)
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
                _state.update { prev ->
                    val next = prev.copy(
                        localModelReady = true,
                        downloadingModel = false,
                        downloadedModelIds = newDownloaded,
                        setupError = null,
                    )
                    refresh(next, choice)
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
                _state.update { prev ->
                    val next = prev.copy(
                        localModelReady = false,
                        llmMode = "cloud",
                        downloadedModelIds = newDownloaded,
                    )
                    refresh(next, choice)
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
    private suspend fun requestConfirmation(message: String): Boolean = consent.request(message)

    fun respondConfirmation(id: String, allowed: Boolean) = consent.respond(id, allowed)

    /**
     * v0.6.15: the consent dialog's third answer — "Allow all this session".
     * Approves the CURRENT command like Allow, and additionally records the
     * user's session grant: subsequent shell commands skip the dialog but
     * are each audited (complete command → episode log) and counted. The
     * grant dies with the process and is revocable in Setup anytime. Only
     * ever reachable on a shell consent — the dialog only shows the button
     * when [PendingConfirmation.offerSessionGrant] is true.
     */
    fun respondConfirmationWithSessionGrant(id: String) {
        val pending = _state.value.pendingConfirmation ?: return
        if (pending.id != id || !pending.offerSessionGrant) return
        SessionGrants.grantShellForSession()
        viewModelScope.launch {
            runCatching {
                db.episodeDao().insert(Episode(
                    kind = "session_grant",
                    summary = "User granted all shell commands for this session",
                    detailsJson = "{}",
                ))
            }
        }
        respondConfirmation(id, true)
        refreshSessionGrants()
    }

    /** Setup → session grants → REVOKE: the next shell command asks again. */
    fun revokeShellSessionGrant() {
        SessionGrants.revokeShellGrant()
        viewModelScope.launch {
            runCatching {
                db.episodeDao().insert(Episode(
                    kind = "session_grant_revoked",
                    summary = "User revoked the shell session grant",
                    detailsJson = "{}",
                ))
            }
        }
        refreshSessionGrants()
    }

    /** Mirror the SessionGrants singleton into UI state (Setup card). */
    fun refreshSessionGrants() {
        _state.update {
            it.copy(
                shellVerifiedThisSession = SessionGrants.shellVerified,
                shellBlockedThisSession = SessionGrants.shellBlocked,
                shellSessionGrant = SessionGrants.shellAllCommands,
                shellAutoRunCount = SessionGrants.autoRunCount,
            )
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

    // ------------------------------------- Linux system setup (Setup tab)

    /**
     * Live status of Vorv's own computer. [provisioned] is a stamp file, so this
     * is a cheap file check and can run on every screen entry.
     */
    fun refreshLinuxStatus() {
        val guest = linuxSystem
        _state.update {
            it.copy(
                linuxAvailable = guest.isAvailable(),
                linuxPayloadInApk = guest.inspectPayload().payloadInApk,
                linuxProvisioned = guest.isProvisioned(),
                linuxDiagnostics = guest.diagnose(),
                toolchainInstalled = host.vorv.linux.LinuxToolchain.looksInstalled(guest.rootfs),
            )
        }
    }

    /** The loader proot was handed, shown with a failure so the cause is visible. */
    private fun loaderTail(guest: host.vorv.linux.LinuxSystem): String =
        "\n\nproot loader used: " + (guest.resolveLoader()?.absolutePath ?: "none executable") +
            "\nproot binary: " + (guest.prootExecutable()?.absolutePath ?: "missing")

    /** Setup → Linux system → TEST. Runs PROBE_COMMAND in the guest — nothing else. */
    fun testLinuxSystem() {
        if (_state.value.linuxTesting) return
        _state.update { it.copy(linuxTesting = true, linuxTestResult = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val guest = linuxSystem
            var inspected: host.vorv.linux.LinuxPayload.State? = null
            if (!guest.isAvailable()) inspected = runCatching { guest.payloadReport() }.getOrNull()
            val verdict = if (!guest.isAvailable()) {
                when {
                    inspected == null || !inspected.payloadInApk ->
                        "NO LINUX SYSTEM IN THIS BUILD — this APK has no payload at all (tools/forge/fetch-linux.sh)."
                    else ->
                        "THE PAYLOAD IS IN THIS APK BUT ANDROID DID NOT UNPACK IT FOR THIS INSTALL.\n" +
                            "That unpack happens at install time, so the fix is a clean install, not a setting:\n" +
                            "  1. uninstall Vorv,  2. install the same APK again.\n\n" +
                            "Checked on this phone:\n" +
                            "  in the APK: " + (inspected.apkEntry ?: "no") + "\n" +
                            "  for ABI: " + (inspected.abi ?: "none") + "\n" +
                            "  found by the runtime: " + (inspected.resolvedPath ?: "no") + "\n" +
                            "  unpacked copy: " + (if (inspected.extracted) "present" else "missing") + "\n" +
                            "  assessment: " + (inspected.diagnosis ?: "the binaries look right, the guest refused to start")
                }
            } else {
                val shell = runCatching { guest.run(PROBE_COMMAND, 180_000, emptyList(), "/root") }
                    .getOrElse { host.vorv.linux.LinuxSystem.Shell(-1, "", it.message, true) }
                when {
                    shell.timedOut -> "FAILED — the guest did not answer within 180s. " +
                        (shell.error ?: "No output.") + loaderTail(guest)
                    shell.exitCode != 0 -> "FAILED — the guest answered with exit ${shell.exitCode}:\n" +
                        shell.output.takeLast(2_000) + loaderTail(guest)
                    else -> "ALL GOOD — a real command ran inside Vorv's Linux system:\n" +
                        shell.output.trim().takeLast(2_000)
                }
            }
            if (verdict.startsWith("ALL GOOD")) SessionGrants.markShellVerified()
            _state.update {
                it.copy(
                    linuxTesting = false,
                    linuxTestResult = verdict,
                    linuxAvailable = guest.isAvailable(),
                    linuxPayloadInApk = guest.inspectPayload().payloadInApk,
                    linuxProvisioned = guest.isProvisioned(),
                    linuxDiagnostics = guest.diagnose(),
                    shellVerifiedThisSession = SessionGrants.shellVerified,
                    shellBlockedThisSession = SessionGrants.shellBlocked,
                )
            }
        }
    }

    /** Setup → manual fallback: open Vorv's own Android settings page. */
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

    // ------------------------------------------------------- send / tasks

    /**
     * v0.6.16: STOP — the user's exit from any running task. Cancels the
     * kernel loop at its next suspension point; the honest stopped-by-user
     * message comes from send()'s CancellationException handler. Safe to
     * call anytime (no-op when idle). The consent dialog, if one is up,
     // is cleared by requestConfirmation's own finally.
     */
    fun stopCurrentTask() {
        currentTaskJob?.cancel(kotlinx.coroutines.CancellationException("stopped by user"))
    }

    /**
     * Send a chat message — kicks off the ReAct kernel.
     * Gating is honest: local mode needs the downloaded model; cloud mode needs an API key.
     * Overall task timeout is mode-aware (audit M-3).
     *
     * v0.6.16: a busy task no longer locks the user out. "stop"/"cancel"/
     * "abort" cancels the running task; anything else gets an honest
     * local reply instead of the old silent drop (the send button is
     * enabled while busy for exactly this).
     */
    fun send(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return

        // ---- v0.6.16: honest busy-send (was: silent drop — the user was
        //      locked in with no way to even say "stop") ----
        if (_state.value.busy) {
            val lower = trimmed.lowercase().trimEnd('.', '!', '?')
            val busyEcho = ChatMessage(role = "user", content = trimmed)
            _state.update { it.copy(messages = it.messages + busyEcho) }
            persistChat(busyEcho)   // M1: the echo is part of the conversation
            if (lower == "stop" || lower == "cancel" || lower == "abort" || lower == "stop it") {
                stopCurrentTask()
            } else {
                val hint = ChatMessage(role = "assistant",
                    content = "I'm still working on the previous task. To cancel it, tap ■ STOP " +
                        "in the header (or send \"stop\"), then resend your message.")
                _state.update { it.copy(messages = it.messages + hint) }
                persistChat(hint)   // M1
            }
            return
        }

        val s = _state.value
        // 0.9.18 — the gate reads the SAME resolution as the header, so the two
        // can no longer disagree (screenshot 3: header named a custom model,
        // every message answered "I have no brain to think with yet").
        val resolved = BrainChoice.resolve(brainFacts(s))
        val engineAvailable = resolved.usable
        if (!engineAvailable) {
            val why = resolved.reason ?: "no brain is configured yet"
            val hint = if (s.llmMode == "local") {
                "I cannot answer: $why\n\nOpen Setup → download the on-phone coder " +
                    "(5.31 GB for OmniCoder-9B, one time), or add a cloud key there. Then I work."
            } else {
                "I cannot answer: $why\n\nTwo ways to give me a brain:\n\n" +
                    "1. Setup → your provider key → Save & Test (a real reply is required before it counts).\n" +
                    "2. Setup → download the on-phone coder (~5.31 GB, one time) — offline, no key.\n\n" +
                    "Setup → Brain health lists every piece and what is wrong with it."
            }
            val echo = ChatMessage(role = "user", content = trimmed)
            val hintMsg = ChatMessage(role = "assistant", content = hint)
            _state.update {
                it.copy(messages = it.messages + echo + hintMsg)
            }
            persistChat(echo)     // M1: the whole exchange persists — including
            persistChat(hintMsg)  // the honest "not configured yet" answers
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
        persistChat(userMsg)   // M1

        // v0.8.0 — learn from EVERY message: operator identity, preferences,
        // taught facts. Learning failure must never break the task.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { memoryLearner.ingestUserMessage(trimmed) }
        }

        currentTaskJob = viewModelScope.launch(Dispatchers.IO) {
            // The task's own Job, so the tail below can tell "I am still the
            // running task" from "the user already started a new one". send()
            // clears busy as soon as the answer is in, while the DB writes run
            // on, so a fast second message can own currentTaskJob before this
            // block reaches its finally.
            val self = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val task = AgentTask(prompt = trimmed, status = "in_progress", startedAt = System.currentTimeMillis())
            db.taskDao().insert(task)

            host.vorv.service.AgentService.start(appContext, "Working: ${trimmed.take(40)}")

            try {
                val chosenMode = prefs.llmMode.first()
                // 0.9.18 — the key belongs to the SELECTED provider.
                val providerId = prefs.cloudProviderId.first()
                    .ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
                val key = secrets.keyFor(providerId)
                val choice = ModelChoice.fromId(prefs.localModelId.first())
                // No cloud key but an on-phone coder in storage: the coder answers,
                // and the header says so. Never a silent engine switch — the label
                // and the episode log both name the brain that actually ran.
                //
                // 0.9.19 — the gate and the header now read the SAME answer. When
                // nothing can reply, the task stops with the reason in the chat
                // instead of spending a minute discovering it.
                val facts = brainFacts(_state.value).copy(
                    llmMode = chosenMode,
                    providerId = providerId,
                    keyPresent = key.isNotBlank(),
                    keyReadError = null,
                    baseUrl = prefs.cloudBaseUrl.first().ifBlank { ZAI_BASE_URL },
                    modelId = prefs.cloudCustomModel.first().ifBlank { prefs.cloudModelId.first() },
                    localModelId = choice.id,
                    localDownloaded = ModelManager.isDownloaded(choice),
                )
                val resolved = BrainChoice.resolve(facts)
                if (!resolved.usable) {
                    throw IllegalStateException(resolved.reason ?: resolved.notice ?: "no brain can answer yet")
                }
                val mode = resolved.mode
                if (mode != chosenMode) {
                    runCatching {
                        db.episodeDao().insert(
                            Episode(
                                kind = "lesson",
                                summary = "No cloud key — this task ran on the on-phone coder (${choice.id})",
                            )
                        )
                    }
                }
                val cloudModel = CloudModel.fromId(prefs.cloudModelId.first())
                val autoFallback = prefs.autoFallback.first()
                val cloudBaseUrl = prefs.cloudBaseUrl.first()
                val customModel = prefs.cloudCustomModel.first()
                val wsUri = _state.value.workspaceUri.ifBlank { null }

                modelRepository.markBusy(true)
                val llm: Llm = modelRepository.activeEngine(
                    mode, key, choice, cloudModel, autoFallback, cloudBaseUrl, customModel,
                    providerFallbacks = providerFallbacks(cloudBaseUrl, customModel, cloudModel),
                    onFailover = ::recordProviderFailover,
                )

                // v0.7.0: every cloud→offline takeover is recorded with its
                // REAL reason in the episode log — a fallback is visible,
                // never a silent engine swap.
                (llm as? FallbackLlm)?.onEngineSwitch = { from, to, reason ->
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching {
                            db.episodeDao().insert(
                                Episode(
                                    kind = "fallback",
                                    summary = "Cloud engine failed mid-task — continuing on the offline engine",
                                    detailsJson = "{\"from\":\"${from.replace("\"", "'")}\"," +
                                        "\"to\":\"${to.replace("\"", "'")}\"," +
                                        "\"reason\":\"${reason.replace("\"", "'").take(300)}\"}",
                                )
                            )
                        }
                    }
                }

                val timeoutMs = if (mode == "local") LOCAL_TASK_TIMEOUT_MS else CLOUD_TASK_TIMEOUT_MS
                // Night session 2026-11-08 — conversation continuity: the
                // model used to receive ONLY this message (kernel.run starts
                // a fresh [system, user(prompt)] every task), so "try again"
                // arrived as two words in a void. Recent turns now ride
                // along, budget-aware per engine — see buildPromptWithContext.
                val promptWithContext = buildPromptWithContext(trimmed, localEngine = mode == "local")
                val result = try {
                    withTimeout(timeoutMs) {
                        kernel.run(task.id, promptWithContext, llm, wsUri, confirm = ::requestConfirmation)
                    }
                } catch (e: TimeoutCancellationException) {
                    // Screen recording 20:32: the bare timeout notice left the
                    // model with nothing to retry — "try again" got "I don't
                    // have context for what you'd like me to try again". The
                    // notice now carries the original task and the real
                    // progress, so a retry request has full context.
                    val steps = _state.value.liveTrace
                        .filterIsInstance<TraceStep.ToolEnd>()
                        .takeLast(5)
                        .joinToString("; ") { step ->
                            "${step.tool}(${if (step.result.ok) "ok" else "failed"})"
                        }
                        .ifBlank { "no tool calls completed" }
                    host.vorv.kernel.KernelApi.TaskResult(
                        false,
                        "Task timed out after ${timeoutMs / 60000} minutes and was stopped. " +
                            "Original task: \"${trimmed.take(200)}\". Progress before timeout: $steps. " +
                            "Full history: Tasks tab. If asked to retry, rerun the original task.",
                        0, emptyList(), 0, error = "timeout",
                    )
                }

                val assistantMsg = ChatMessage(
                    role = "assistant",
                    content = result.answer,
                    citations = result.citations,
                )
                // The answer always joins the conversation; busy/liveTrace are
                // this task's to clear only if it is still the running one.
                _state.update {
                    it.copy(
                        messages = it.messages + assistantMsg,
                        busy = if (currentTaskJob === self) false else it.busy,
                        liveTrace = if (currentTaskJob === self) emptyList() else it.liveTrace,
                    )
                }
                persistChat(assistantMsg)   // M1
                db.taskDao().complete(task.id, result.answer.take(500), if (result.ok) "completed" else "failed")

                // v0.8.0 — the task's outcome becomes a lesson node wired
                // into the knowledge web, with the REAL tool roster.
                runCatching {
                    val toolNames = db.toolCallDao().forTask(task.id).map { it.toolName }
                    memoryLearner.ingestOutcome(trimmed, result.ok, toolNames, result.iterations)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v0.6.16: the user pressed STOP (header button or "stop" in
                // chat). Honest, immediate, final. DB write needs
                // NonCancellable — suspend calls in a cancelled coroutine
                // throw on entry.
                val stoppedMsg = ChatMessage(
                    role = "assistant",
                    content = "Stopped — you cancelled this task. Original task: \"${trimmed.take(200)}\". " +
                        "Work already done is preserved in the Tasks tab; partial tool history is intact.",
                )
                _state.update {
                    it.copy(
                        messages = it.messages + stoppedMsg,
                        busy = if (currentTaskJob === self) false else it.busy,
                        liveTrace = if (currentTaskJob === self) emptyList() else it.liveTrace,
                    )
                }
                persistChat(stoppedMsg)   // M1
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { db.taskDao().fail(task.id, "stopped by user") }
                }
                throw e
            } catch (e: Exception) {
                val errMsg = ChatMessage(role = "assistant", content = "Error: ${e.message}")
                _state.update {
                    it.copy(
                        messages = it.messages + errMsg,
                        busy = if (currentTaskJob === self) false else it.busy,
                        liveTrace = if (currentTaskJob === self) emptyList() else it.liveTrace,
                    )
                }
                persistChat(errMsg)   // M1
                // F-11: fail() is a Room write inside the catch of a coroutine
                // with no outer guard. If the database is unavailable the
                // original error would be replaced by an unexplained second one
                // and the task row would stay in_progress forever.
                runCatching { db.taskDao().fail(task.id, e.message ?: "unknown") }
            } finally {
                // Only the OWNER of currentTaskJob may clear it. A second task
                // started while this one wrote its result would otherwise lose
                // its STOP handle, and markBusy(false) would let the local
                // model be swapped out from under it.
                if (currentTaskJob === self) {
                    currentTaskJob = null
                    modelRepository.markBusy(false)
                    host.vorv.service.AgentService.stop(appContext)
                }
                // v0.6.17: the task may have tripped the dead-shell breaker
                // or completed the first verified round-trip — the Setup
                // checklist must show the truth the moment the task ends,
                // not stay stale until the user taps something.
                refreshSessionGrants()
            }
        }
    }

    // ------------------------------------------------------- conversation continuity

    /**
     * Night session 2026-11-08 — the amnesia fix (see [ConversationPrompt]
     * for the full story and the frozen-signature constraint). The pure
     * builder lives in the kernel package so it is unit-testable without
     * Android; this wrapper just feeds it the live chat state.
     *
     * v0.8.0 — SUSPEND: the knowledge web is now woven in here too. The
     * associative activation (KnowledgeGraph.activate) runs over the learned
     * graph, its section (KnowledgePrompt.section) is appended after the
     * conversation window, and the woken nodes get touched (use strengthens
     * memory). runCatching guards the whole wake-up: a brain that fails to
     * wake must never kill the task — the prompt ships without the web.
     */
    internal suspend fun buildPromptWithContext(newMessage: String, localEngine: Boolean): String {
        val base = host.vorv.kernel.ConversationPrompt.build(
            newMessage = newMessage,
            // The current user message was ALREADY appended to state by
            // send() before this runs — drop it; it is delivered as the
            // [New message] section, never duplicated inside the history.
            history = _state.value.messages
                .dropLast(1)
                .map { host.vorv.kernel.ConversationPrompt.Turn(it.role, it.content) },
            localEngine = localEngine,
        )
        val web = runCatching { knowledgeSection(newMessage, localEngine) }.getOrDefault("")
        val withWeb = if (web.isBlank()) base else "$base\n\n$web"
        return "$withWeb\n\n${projectSection(newMessage)}"
    }

    /**
     * Plan Step 7: the agent is shown the project it has open — outline, the code
     * nearest this request, and whatever could not be indexed. Before this, the
     * whole `host.vorv.project` package was never called from the app, so the
     * agent answered about code it had never seen.
     */
    private suspend fun projectSection(message: String): String {
        val uri = _state.value.workspaceUri
        if (uri.isBlank()) {
            return host.vorv.project.ProjectContext.build(null, message)
        }
        val section = host.vorv.tools.Attempt.of("index the open project for this prompt") {
            host.vorv.project.ProjectIndexCache.section(appContext, uri, message)
        }
        return section ?: ("## Project\nThe project folder could not be indexed, and the reason " +
            "was written to the log. Do not guess what is in it; say the index failed.")
    }

    /**
     * Wake the knowledge web for [message]: activate, strengthen the woken
     * nodes, format the section. Blank when nothing associates.
     */
    private suspend fun knowledgeSection(message: String, localEngine: Boolean): String {
        val dao = db.knowledgeGraphDao()
        val nodeRows = dao.recentNodes(500)
        if (nodeRows.isEmpty()) return ""
        val edgeRows = dao.recentEdges(1000)
        val activation = host.vorv.knowledge.KnowledgeGraph.activate(
            message,
            nodeRows.map {
                host.vorv.knowledge.KnowledgeGraph.Node(it.id, it.kind, it.topic, it.content, it.salience, it.accessCount)
            },
            edgeRows.map {
                host.vorv.knowledge.KnowledgeGraph.Edge(it.fromId, it.toId, it.relation, it.weight)
            },
            maxNodes = if (localEngine) 8 else 16,
            maxEdges = if (localEngine) 6 else 12,
        )
        if (activation.nodes.isEmpty()) return ""

        // Use strengthens: each woken node's salience is decay-adjusted
        // then bumped; accessCount++ and lastAccessedAt=now via touch().
        val now = System.currentTimeMillis()
        for (n in activation.nodes) {
            val lastAccess = nodeRows.firstOrNull { it.id == n.id }?.lastAccessedAt
            val decayed = host.vorv.knowledge.KnowledgeGraph.decay(n.salience, lastAccess, now)
            runCatching { dao.touch(n.id, (decayed + 0.05f).coerceAtMost(1f), now) }
        }

        val operator = dao.operator()?.name
        return host.vorv.kernel.KnowledgePrompt.section(activation, operator, localEngine)
    }

    // ------------------------------------------------------- boot self-diagnosis

    /** Once-per-process guard for [selfDiagnoseOnBoot] (survives activity recreation). */
    @Volatile
    private var bootDiagnosisStarted = false

    /**
     * Night session 2026-11-08 (screen recording 20:32): the user's contract
     * is "when you crash, YOU read the crash report and fix it — I see the
     * report only after you're done." Reality was inverted: the crash dialog
     * pushed the raw report at the USER, and "did you see the crash report?"
     * could only be answered "I don't have any" — no tool reached the crash
     * artifacts at all. This is the missing half: on boot after a crash the
     * agent gets the job directly, reads its own reports (crash_logs tool),
     * and reports back; the dialog stays as the fallback.
     *
     * Guard checks run synchronously; a false return tells the caller
     * (MainActivity) to show the crash dialog instead. The task prompt is
     * system-authored and never masquerades as a user message — the
     * persisted kickoff note says plainly what Vorv is doing.
     */
    fun selfDiagnoseOnBoot(
        hasJavaReport: Boolean,
        bootTrace: String,
        previousBootCrashed: Boolean,
        rolledBackPatches: List<String> = emptyList(),
        repairSummary: String? = null,
        evidenceDetail: String? = null,
    ): Boolean {
        if (bootDiagnosisStarted) return true
        val s = _state.value
        if (s.busy) return false
        if (!BrainChoice.usable(s.llmMode, s.hasApiKey, s.localModelReady)) return false
        bootDiagnosisStarted = true

        val situation = when {
            hasJavaReport ->
                "A Java crash report was written by CrashReportWorker — it is in crash_logs/."
            previousBootCrashed ->
                "The previous boot was ended by a crash. Evidence: " +
                    (evidenceDetail ?: "recorded in the crash report") +
                    ". A synthesized report may be in crash_logs/, and watchdog detections " +
                    "may be in watchdog/."
            else ->
                "The watchdog detected a mid-session process death (watchdog/ has the detection)."
        }

        val prompt = buildString {
            appendLine("[Automatic boot task — Vorv crashed last session]")
            appendLine("You (Vorv) crashed. Find out why, and fix yourself if a code fix is possible.")
            appendLine()
            appendLine("What is known: $situation")
            if (!repairSummary.isNullOrBlank()) {
                appendLine("Already done automatically this boot (do not repeat it): $repairSummary")
                appendLine("The record is in crash_repairs/ — read it before proposing anything.")
            }
            if (bootTrace.isNotBlank()) {
                appendLine("Last breadcrumbs before death (tail of the previous boot trace — the full trace is readable via crash_logs):")
                appendLine(bootTrace.trim().takeLast(600))
            }
            if (rolledBackPatches.isNotEmpty()) {
                appendLine("Probation gate: ${rolledBackPatches.size} hot-patch(es) were rolled back this boot " +
                    "(a patch was followed by a crash in a class it overrides): " +
                    rolledBackPatches.joinToString(", ") + ". Mention this in your report.")
            }
            appendLine("All crash artifacts are ON THIS DEVICE — read them yourself with the crash_logs tool (action=list, then action=read).")
            appendLine()
            appendLine("Steps:")
            appendLine("1. crash_logs action=list — see every report that exists.")
            appendLine("2. Read the newest relevant report(s) in full.")
            appendLine("3. State the root cause as precisely as the evidence allows. If the evidence only supports \"native crash, no Java stack\", say exactly that — never invent a stack trace, class name, or line number.")
            appendLine("4. If a code fix is possible, use self_fix. The patch still needs the user's approval — describe it clearly when the consent dialog appears.")
            appendLine("5. If no fix applies, say plainly what you found and what evidence would be needed.")
            appendLine()
            appendLine("Report your findings in plain language. Do not ask the user to paste or fetch anything — you can read all of it yourself.")
        }

        val kickoff = ChatMessage(
            role = "assistant",
            content = "I crashed last session. I'm reading my own crash reports now — " +
                "I'll tell you what I find, and if I can fix it, you'll get the patch to approve.",
        )
        _state.update {
            it.copy(
                messages = it.messages + kickoff,
                busy = true,
                liveTrace = emptyList(),
            )
        }
        persistChat(kickoff)

        currentTaskJob = viewModelScope.launch(Dispatchers.IO) {
            // Same ownership rule as send(): the tail must not clear a task
            // the user started in the meantime.
            val self = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val task = AgentTask(
                prompt = "[boot self-diagnosis] $situation",
                status = "in_progress",
                startedAt = System.currentTimeMillis(),
            )
            db.taskDao().insert(task)
            host.vorv.service.AgentService.start(appContext, "Working: boot self-diagnosis")
            try {
                val chosenMode = prefs.llmMode.first()
                val bootPid = prefs.cloudProviderId.first()
                    .ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
                val key = secrets.keyFor(bootPid)
                val choice = ModelChoice.fromId(prefs.localModelId.first())
                // No cloud key but an on-phone coder in storage: the coder answers,
                // and the header says so. Never a silent engine switch — the label
                // and the episode log both name the brain that actually ran.
                val mode = BrainChoice.mode(chosenMode, key.isNotBlank(), ModelManager.isDownloaded(choice))
                if (mode != chosenMode) {
                    runCatching {
                        db.episodeDao().insert(
                            Episode(
                                kind = "lesson",
                                summary = "No cloud key — this task ran on the on-phone coder (${choice.id})",
                            )
                        )
                    }
                }
                val cloudModel = CloudModel.fromId(prefs.cloudModelId.first())
                val autoFallback = prefs.autoFallback.first()
                val cloudBaseUrl = prefs.cloudBaseUrl.first()
                val customModel = prefs.cloudCustomModel.first()
                val wsUri = _state.value.workspaceUri.ifBlank { null }

                modelRepository.markBusy(true)
                val llm: Llm = modelRepository.activeEngine(
                    mode, key, choice, cloudModel, autoFallback, cloudBaseUrl, customModel,
                    providerFallbacks = providerFallbacks(cloudBaseUrl, customModel, cloudModel),
                    onFailover = ::recordProviderFailover,
                )

                val timeoutMs = if (mode == "local") LOCAL_TASK_TIMEOUT_MS else CLOUD_TASK_TIMEOUT_MS
                val result = try {
                    withTimeout(timeoutMs) {
                        kernel.run(task.id, prompt, llm, wsUri, confirm = ::requestConfirmation)
                    }
                } catch (e: TimeoutCancellationException) {
                    host.vorv.kernel.KernelApi.TaskResult(
                        false,
                        "Boot self-diagnosis timed out after ${timeoutMs / 60000} minutes and was stopped. " +
                            "The crash reports are still on disk — send \"diagnose the crash\" and I'll go again.",
                        0, emptyList(), 0, error = "timeout",
                    )
                }

                val assistantMsg = ChatMessage(
                    role = "assistant",
                    content = result.answer,
                    citations = result.citations,
                )
                _state.update {
                    it.copy(
                        messages = it.messages + assistantMsg,
                        busy = if (currentTaskJob === self) false else it.busy,
                        liveTrace = if (currentTaskJob === self) emptyList() else it.liveTrace,
                    )
                }
                persistChat(assistantMsg)
                db.taskDao().complete(task.id, result.answer.take(500), if (result.ok) "completed" else "failed")
                // v0.8.0 — boot-diagnosis outcomes learn too (they are the
                // richest lessons: what crashed and what fixed it).
                runCatching {
                    val toolNames = db.toolCallDao().forTask(task.id).map { it.toolName }
                    memoryLearner.ingestOutcome(
                        task.prompt, result.ok, toolNames, result.iterations,
                    )
                }
                // The agent has reported on this crash — mark the evidence
                // handled so the raw-report dialog never resurfaces it on a
                // later boot (files stay on disk for crash_logs).
                (appContext as? VorvApplication)?.let { app ->
                    app.getLatestCrashLog()?.let(app::markCrashHandled)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // STOP during boot diagnosis: the old code caught this as an
                // ordinary Exception, so the task was recorded as a failure no
                // one could explain and the cancelled coroutine then threw from
                // inside its own handler.
                val stoppedMsg = ChatMessage(
                    role = "assistant",
                    content = "Boot self-diagnosis stopped — you cancelled it. The crash reports are " +
                        "still on disk — send \"diagnose the crash\" to run it again.",
                )
                _state.update {
                    it.copy(
                        messages = it.messages + stoppedMsg,
                        busy = if (currentTaskJob === self) false else it.busy,
                        liveTrace = if (currentTaskJob === self) emptyList() else it.liveTrace,
                    )
                }
                persistChat(stoppedMsg)
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { db.taskDao().fail(task.id, "stopped by user") }
                }
                throw e
            } catch (e: Exception) {
                val errMsg = ChatMessage(
                    role = "assistant",
                    content = "Boot self-diagnosis failed: ${e.message}. The crash reports are still " +
                        "on disk — send \"diagnose the crash\" to retry.",
                )
                _state.update {
                    it.copy(
                        messages = it.messages + errMsg,
                        busy = if (currentTaskJob === self) false else it.busy,
                        liveTrace = if (currentTaskJob === self) emptyList() else it.liveTrace,
                    )
                }
                persistChat(errMsg)
                runCatching { db.taskDao().fail(task.id, e.message ?: "unknown") }
            } finally {
                if (currentTaskJob === self) {
                    currentTaskJob = null
                    modelRepository.markBusy(false)
                    host.vorv.service.AgentService.stop(appContext)
                }
                refreshSessionGrants()
            }
        }
        return true
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
        // M3 (code review, 2026-11): the bridge singletons hold lambdas that
        // capture this ViewModel (and its whole object graph). Without this,
        // the LAST dead ViewModel stayed pinned until a future one happened
        // to overwrite the reference. Clear only what this instance installed
        // — a newer ViewModel's handlers are never touched.
        if (PermissionBridge.handler === installedPermissionHandler) {
            PermissionBridge.handler = null
        }
        if (SessionGrants.auditSink === installedAuditSink) {
            SessionGrants.auditSink = null
        }
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

    /**
     * 0.9.18 — the ONLY place "which brain" is decided. The header chip, the
     * send gate and Setup → Brain health all read this one answer, so a model
     * in prefs can never be labelled while the gate refuses it (screenshot 3).
     */
    private fun brainFacts(s: AgentState): BrainFacts {
        val choice = ModelChoice.fromId(s.selectedModelId)
        val base = host.vorv.llm.BrainState.facts.value
        val providerId = s.providerId.ifBlank { host.vorv.llm.provider.ProviderRegistry.ZAI_ID }
        val provider = host.vorv.llm.provider.ProviderRegistry.byId(providerId)
        return base.copy(
            llmMode = s.llmMode,
            providerId = providerId,
            providerLabel = provider?.label ?: "your provider",
            keyReadError = s.keyReadError,
            baseUrl = s.cloudBaseUrl.ifBlank { ZAI_BASE_URL },
            modelId = s.cloudCustomModel.ifBlank { CloudModel.fromId(s.cloudModelId).id },
            keyPresent = s.hasApiKey,
            localModelId = choice.id,
            localModelName = choice.displayName.substringBefore("(").trim(),
            localDownloaded = s.localModelReady,
            localExpectedBytes = choice.approxBytes,
            localBytes = if (s.localModelReady) choice.approxBytes else 0L,
        )
    }

    /** Applies the one resolution to the state: label, sentence, health rows. */
    private fun refresh(s: AgentState, choice: ModelChoice = ModelChoice.fromId(s.selectedModelId)): AgentState {
        val facts = brainFacts(s.copy(selectedModelId = choice.id))
        val r = BrainChoice.resolve(facts)
        return s.copy(
            currentLlmLabel = r.label,
            brainChecks = BrainDoctor.diagnose(facts),
            brainReport = BrainDoctor.report(facts),
        )
    }

    /**
     * Setup → INSTALL BUILD TOOLS. Downloads Debian's openjdk-17, aapt, zipalign
     * and apksigner (203 MB, sha256-checked) and unpacks them into the guest.
     * It reports every package as it lands, and says plainly if it stops.
     */
    fun installLinuxToolchain() {
        if (_state.value.toolchainInstalling) return
        _state.update { it.copy(toolchainInstalling = true, toolchainResult = null, toolchainProgress = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val guest = linuxSystem
            // The whole install runs under a guard: a malformed plan or a
            // throw from the guest used to escape this coroutine and leave
            // toolchainInstalling = true with no result for the rest of the
            // process — "it says installing and never stops".
            try {
                val json = runCatching {
                    appContext.assets.open(host.vorv.linux.LinuxToolchain.ASSET).bufferedReader().use { it.readText() }
                }.getOrElse { missing ->
                    _state.update { s ->
                        s.copy(
                            toolchainInstalling = false,
                            toolchainResult = "FAILED — this build has no list of what to download "
                                + "(forge/linux/toolchain.json): ${missing.message}",
                        )
                    }
                    return@launch
                }
                // The unpacking runs inside the guest: dpkg-deb resolves Debian's link
                // chains against the guest's own root, which the app's filesystem view
                // cannot do (0.9.5 failed with ELOOP trying it from here).
                val toolchain = host.vorv.linux.LinuxToolchain(
                    rootfs = guest.rootfs,
                    runInGuest = { command ->
                        guest.run(command, timeoutMs = 10 * 60_000L, binds = emptyList(), workDir = "/")
                    },
                    rebuildNote = { guest.lastRebuildNote },
                )
                val plan = toolchain.plan(json)
                val outcome = toolchain.install(plan) { progress ->
                    _state.update { it.copy(toolchainProgress = progress.line()) }
                }
                val verdict = outcome.fold(
                    onSuccess = {
                        "ALL GOOD — javac and aapt are installed in the guest (${plan.size} packages)."
                    },
                    onFailure = { "FAILED — ${it.message}" },
                )
                _state.update {
                    it.copy(
                        toolchainInstalling = false,
                        toolchainProgress = null,
                        toolchainInstalled = host.vorv.linux.LinuxToolchain.looksInstalled(guest.rootfs),
                        toolchainResult = verdict,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        toolchainInstalling = false,
                        toolchainProgress = null,
                        toolchainResult = "FAILED — the install stopped with an error before it could " +
                            "report a package: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    companion object {
        private const val CLOUD_TASK_TIMEOUT_MS = 5 * 60_000L
        private const val LOCAL_TASK_TIMEOUT_MS = 15 * 60_000L
        private const val RETENTION_DAYS = 30
        /** The Z.ai preset's address — one default among the providers, not "the" cloud. */
        private const val ZAI_BASE_URL = "https://api.z.ai/api/paas/v4"

        /**
         * Setup → TEST runs exactly this inside the guest: it answers whether
         * the Debian root filesystem boots, which release it is, and whether the
         * build tools have been installed yet.
         */
        internal const val PROBE_COMMAND =
            "uname -sr; cat /etc/debian_version 2>/dev/null; " +
                "if [ -x /usr/bin/javac ] && [ -x /usr/bin/aapt ]; then " +
                "echo \"build tools: installed\"; else echo \"build tools: not installed yet\"; fi"

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
