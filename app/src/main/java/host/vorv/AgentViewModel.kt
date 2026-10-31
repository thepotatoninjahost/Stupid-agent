package host.vorv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import host.vorv.data.KeyStore
import host.vorv.data.VorvDatabase
import host.vorv.data.entity.AgentTask
import host.vorv.data.entity.Episode
import host.vorv.data.entity.Skill
import host.vorv.kernel.CognitiveKernel
import host.vorv.kernel.TraceStep
import host.vorv.llm.CloudLlm
import host.vorv.llm.Llm
import host.vorv.llm.LlmRouter
import host.vorv.llm.LocalLlm
import host.vorv.llm.ModelChoice
import host.vorv.llm.ModelManager
import host.vorv.tools.AppIntentTool
import host.vorv.tools.BrowserAutomateTool
import host.vorv.tools.FileListTool
import host.vorv.tools.FileReadTool
import host.vorv.tools.FileWriteTool
import host.vorv.tools.JsExecTool
import host.vorv.tools.MemoryRecallTool
import host.vorv.tools.MemoryWriteTool
import host.vorv.tools.NotifyTool
import host.vorv.tools.PythonExecTool
import host.vorv.tools.ScheduleTool
import host.vorv.tools.ShellTool
import host.vorv.tools.WebFetchTool
import host.vorv.tools.WebSearchTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,                // "user" | "assistant"
    val content: String,
    val citations: List<host.vorv.tools.Citation> = emptyList(),
    val ts: Long = System.currentTimeMillis(),
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
)

class AgentViewModel(
    private val db: VorvDatabase,
    private val keyStore: KeyStore,
    private val appContext: Context,
) : ViewModel() {

    private val tools = listOf(
        MemoryWriteTool(db),
        MemoryRecallTool(db),
        JsExecTool(),
        FileReadTool(appContext),
        FileWriteTool(appContext),
        FileListTool(appContext),
        PythonExecTool(appContext),
        WebSearchTool(keyStore),
        WebFetchTool(),
        AppIntentTool(appContext),
        ShellTool(appContext),
        NotifyTool(appContext),
        ScheduleTool(appContext),
        BrowserAutomateTool(appContext),
    )

    /** Real local engine — created only when the model file actually exists on disk. */
    private var localLlm: LocalLlm? = null

    private val kernel = CognitiveKernel(db, tools).apply {
        onIteration = { taskId, iters ->
            db.taskDao().setIterations(taskId, iters)
        }
    }

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    init {
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
            keyStore.zaiApiKey.collect { key ->
                _state.update { it.copy(hasApiKey = key.isNotBlank()) }
            }
        }
        viewModelScope.launch {
            keyStore.workspaceUri.collect { uri ->
                _state.update { it.copy(workspaceUri = uri) }
            }
        }
        viewModelScope.launch {
            keyStore.llmMode.collect { mode ->
                val id = keyStore.localModelId.first()
                val choice = ModelChoice.fromId(id)
                val ready = ModelManager.isDownloaded(appContext, choice)
                _state.update { it.copy(llmMode = mode, currentLlmLabel = engineLabel(mode, ready, it.hasApiKey, choice)) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val downloaded = ModelChoice.entries.filter { ModelManager.isDownloaded(appContext, it) }
            _state.update { it.copy(downloadedModelIds = downloaded.map { m -> m.id }.toSet()) }
        }
        viewModelScope.launch {
            keyStore.localModelId.collect { id ->
                val choice = ModelChoice.fromId(id)
                val ready = ModelManager.isDownloaded(appContext, choice)
                if (ready) {
                    localLlm = LocalLlm(
                        appContext,
                        ModelManager.modelFile(appContext, choice),
                        displayLabel = "${choice.displayName.substringBefore("(").trim()} (local, offline)",
                    )
                } else {
                    localLlm = null
                }
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
        }
        viewModelScope.launch {
            kernel.trace.collect { step ->
                _state.update { it.copy(liveTrace = it.liveTrace + step) }
            }
        }
    }

    private suspend fun bootstrapSkills() {
        if (db.skillDao().getByName("memory_write") == null) {
            tools.forEach { t -> db.skillDao().upsert(Skill(name = t.name, domain = "tools")) }
        }
    }

    fun setScreen(screen: String) {
        _state.update { it.copy(screen = screen) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) { keyStore.setZaiApiKey(key) }
    }

    fun setLlmMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) { keyStore.setLlmMode(mode) }
    }

    /** Switch the active local model. Triggers reload of LocalLlm with the new file. */
    fun setLocalModel(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            localLlm = null
            keyStore.setLocalModelId(id)
            val choice = ModelChoice.fromId(id)
            val ready = ModelManager.isDownloaded(appContext, choice)
            if (ready) {
                localLlm = LocalLlm(
                    appContext,
                    ModelManager.modelFile(appContext, choice),
                    displayLabel = "${choice.displayName.substringBefore("(").trim()} (local, offline)",
                )
            }
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
        viewModelScope.launch(Dispatchers.IO) { keyStore.setWorkspaceUri(uri) }
    }

    /** One-time download of the selected local model, with real progress in state. */
    fun downloadLocalModel() {
        if (_state.value.downloadingModel) return
        val choice = ModelChoice.fromId(_state.value.selectedModelId)
        if (ModelManager.isDownloaded(appContext, choice)) return
        _state.update { it.copy(downloadingModel = true, downloadProgress = 0) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ModelManager.download(appContext, choice).collect { pct ->
                    _state.update { it.copy(downloadProgress = pct) }
                }
                localLlm = LocalLlm(
                    appContext,
                    ModelManager.modelFile(appContext, choice),
                    displayLabel = "${choice.displayName.substringBefore("(").trim()} (local, offline)",
                )
                val newDownloaded = _state.value.downloadedModelIds + choice.id
                _state.update {
                    it.copy(
                        localModelReady = true,
                        downloadingModel = false,
                        downloadedModelIds = newDownloaded,
                        currentLlmLabel = engineLabel(it.llmMode, true, it.hasApiKey, choice),
                    )
                }
                db.episodeDao().insert(Episode(kind = "lesson", summary = "Local model downloaded: ${choice.id} — offline mode available"))
            } catch (e: Exception) {
                _state.update { it.copy(downloadingModel = false, downloadProgress = 0) }
                val errMsg = ChatMessage(role = "assistant", content = "Model download failed: ${e.message}")
                _state.update { it.copy(messages = it.messages + errMsg) }
            }
        }
    }

    fun deleteLocalModel(choice: ModelChoice) {
        viewModelScope.launch(Dispatchers.IO) {
            ModelManager.modelFile(appContext, choice).delete()
            val activeId = _state.value.selectedModelId
            if (activeId == choice.id) {
                localLlm = null
                val newDownloaded = _state.value.downloadedModelIds - choice.id
                _state.update {
                    it.copy(
                        localModelReady = false,
                        llmMode = "cloud",
                        downloadedModelIds = newDownloaded,
                        currentLlmLabel = engineLabel("cloud", false, it.hasApiKey, choice),
                    )
                }
                keyStore.setLlmMode("cloud")
            } else {
                _state.update {
                    it.copy(downloadedModelIds = it.downloadedModelIds - choice.id)
                }
            }
        }
    }

    /**
     * Send a chat message — kicks off the ReAct kernel.
     * Gating is honest: local mode needs the downloaded model; cloud mode needs an API key.
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
                "Local mode is selected but the model isn't downloaded yet.\n\nOpen Setup → Download local model (~400MB, one time). Or switch to Cloud and paste a Z.ai API key."
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
                val mode = keyStore.llmMode.first()
                val key = keyStore.zaiApiKey.first()
                val llm: Llm = LlmRouter.pick(mode, key, localLlm)
                val wsUri = _state.value.workspaceUri.ifBlank { null }

                val result = kernel.run(task.id, trimmed, llm, wsUri)

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
                host.vorv.service.AgentService.stop(appContext)
            }
        }
    }

    private fun engineLabel(mode: String, ready: Boolean, hasApiKey: Boolean, choice: ModelChoice): String {
        return when (mode) {
            "local" -> if (ready) {
                "${choice.displayName.substringBefore("(").trim()} (local, offline)"
            } else {
                "Local — ${choice.displayName.substringBefore("(").trim()} not downloaded"
            }
            else -> if (hasApiKey) "GLM-4.6 (cloud)" else "Cloud — needs API key"
        }
    }

    /**
     * Teach Vorv a fact directly — stores as high-confidence memory.
     */
    fun teach(topic: String, content: String) {
        if (topic.isBlank() || content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val t = tools.firstOrNull { it.name == "memory_write" }
            val input = com.google.gson.JsonObject().apply {
                addProperty("topic", topic)
                addProperty("content", content)
            }
            t?.run(input, host.vorv.tools.ToolContext("", 0, _state.value.workspaceUri))
        }
    }

    companion object {
        fun factory(db: VorvDatabase, keyStore: KeyStore, ctx: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AgentViewModel(db, keyStore, ctx.applicationContext) as T
        }
    }
}
