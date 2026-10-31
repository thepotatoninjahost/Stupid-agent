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
import host.vorv.tools.AppIntentTool
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
    val hasApiKey: Boolean = false,
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
    )

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

    fun setWorkspaceUri(uri: String) {
        viewModelScope.launch(Dispatchers.IO) { keyStore.setWorkspaceUri(uri) }
    }

    /**
     * Send a chat message — kicks off the ReAct kernel.
     */
    fun send(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || _state.value.busy) return

        // Pre-check: API key must be set
        if (!_state.value.hasApiKey) {
            val errMsg = ChatMessage(
                role = "assistant",
                content = "I need a Z.ai API key to think.\n\nOpen Setup tab → paste your key from z.ai/manage-apikey → Save.\n\nThen ask again.",
            )
            _state.update {
                it.copy(messages = it.messages + ChatMessage(role = "user", content = trimmed) + errMsg)
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
                val key = keyStore.zaiApiKey.first()
                if (key.isBlank()) {
                    val errMsg = ChatMessage(role = "assistant", content = "No API key set. Open Setup tab.")
                    _state.update { it.copy(messages = it.messages + errMsg, busy = false, liveTrace = emptyList()) }
                    db.taskDao().fail(task.id, "no_api_key")
                    return@launch
                }
                val llm: Llm = CloudLlm(key)
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
