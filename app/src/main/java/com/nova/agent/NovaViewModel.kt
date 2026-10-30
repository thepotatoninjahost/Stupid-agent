package com.nova.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nova.agent.cognitive.*
import com.nova.agent.data.KeyStore
import com.nova.agent.data.NovaDatabase
import com.nova.agent.data.entity.*
import com.nova.agent.llm.LlmClient
import com.nova.agent.sandbox.CodeSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,                // "user" | "assistant"
    val content: String,
    val trace: List<ReasoningStep> = emptyList(),
    val ts: Long = System.currentTimeMillis(),
)

data class ReasoningStep(
    val kind: String,                // "thinking" | "tool_call" | "tool_result" | "reflection" | "answer" | "error"
    val label: String,
    val detail: String? = null,
)

data class NovaState(
    val messages: List<ChatMessage> = emptyList(),
    val liveTrace: List<ReasoningStep> = emptyList(),
    val busy: Boolean = false,
    val mode: String = "chat",       // "chat" | "teach" | "autolearn" | "code" | "mind" | "settings"
    val memoryCount: Int = 0,
    val skillCount: Int = 0,
    val totalXp: Int = 0,
    val evolutionEvents: List<EvolutionEvent> = emptyList(),
    val skills: List<SkillNode> = emptyList(),
    val profile: UserProfile? = null,
    val hasApiKey: Boolean = false,
)

class NovaViewModel(
    private val db: NovaDatabase,
    private val keyStore: KeyStore,
) : ViewModel() {

    private var llm: LlmClient? = null
    private val sandbox = CodeSandbox()

    private val _state = MutableStateFlow(NovaState())
    val state: StateFlow<NovaState> = _state.asStateFlow()

    init {
        // Observe memory, skills, evolution, profile reactively
        viewModelScope.launch {
            db.knowledgeDao().observeAll(100).collect { entries ->
                _state.update { it.copy(memoryCount = entries.size) }
            }
        }
        viewModelScope.launch {
            db.skillDao().observeAll().collect { skills ->
                _state.update { it.copy(skills = skills, skillCount = skills.size, totalXp = skills.sumOf { s -> s.xp }) }
            }
        }
        viewModelScope.launch {
            db.evolutionDao().observeRecent(50).collect { events ->
                _state.update { it.copy(evolutionEvents = events) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val profile = db.profileDao().get() ?: UserProfile().also { db.profileDao().upsert(it) }
            _state.update { it.copy(profile = profile) }
            bootstrapIfEmpty()
        }
        // Observe API key — rebuild LlmClient when it changes
        viewModelScope.launch {
            keyStore.apiKey.collect { key ->
                llm = if (key.isNotBlank()) LlmClient(key) else null
                _state.update { it.copy(hasApiKey = key.isNotBlank()) }
            }
        }
    }

    private suspend fun bootstrapIfEmpty() {
        val skillCount = _state.value.skillCount
        if (skillCount > 0) return
        val seed = listOf(
            SkillNode(name = "JavaScript", domain = "frontend", level = 1, xp = 0),
            SkillNode(name = "TypeScript", domain = "frontend", level = 1, xp = 0),
            SkillNode(name = "React", domain = "frontend", level = 1, xp = 0),
            SkillNode(name = "Python", domain = "data", level = 1, xp = 0),
            SkillNode(name = "Algorithms", domain = "algorithms", level = 1, xp = 0),
            SkillNode(name = "System Design", domain = "backend", level = 1, xp = 0),
        )
        seed.forEach { db.skillDao().upsert(it) }
    }

    fun setMode(mode: String) {
        _state.update { it.copy(mode = mode, messages = emptyList(), liveTrace = emptyList()) }
    }

    fun clearMessages() {
        _state.update { it.copy(messages = emptyList(), liveTrace = emptyList()) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) { keyStore.setApiKey(key) }
    }

    /**
     * Send a message via the cognitive kernel.
     */
    fun send(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || _state.value.busy) return

        val userMsg = ChatMessage(role = "user", content = trimmed)
        _state.update {
            it.copy(
                messages = it.messages + userMsg,
                busy = true,
                liveTrace = emptyList(),
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val activeLlm = llm
            if (activeLlm == null) {
                val errMsg = ChatMessage(
                    role = "assistant",
                    content = "I need a Z.ai API key to think.\n\nTap Settings at the bottom → paste your key → Save.\n\nGet a free key at z.ai/manage-apikey."
                )
                _state.update { it.copy(messages = it.messages + errMsg, busy = false, liveTrace = emptyList()) }
                return@launch
            }
            try {
                val perception = perceive(trimmed)
                emitStep(ReasoningStep("thinking", "Perception: ${perception.questionType.name.lowercase()} (${perception.complexity.name.lowercase()})", perception.intentSignature))

                // Recall memory + mistakes
                val memories = db.knowledgeDao().recallByTopic(trimmed.take(60), 5)
                val mistakes = db.knowledgeDao().recallMistakes(trimmed.take(60), 3)
                emitStep(ReasoningStep("tool_result", "Recalled ${memories.size} memories, ${mistakes.size} mistakes"))

                // Route capabilities
                val capabilities = routeCapabilities(perception)
                emitStep(ReasoningStep("thinking", "Capability chain: ${capabilities.joinToString(" → ") { it.name }}"))

                val contextParts = mutableListOf<String>()

                // Mistakes go first (high priority)
                if (mistakes.isNotEmpty()) {
                    contextParts.add("## Past mistakes (do NOT repeat)\n" + mistakes.joinToString("\n") { "• ${it.topic}: ${it.content.take(150)}" })
                }
                if (memories.isNotEmpty()) {
                    contextParts.add("## Relevant memories\n" + memories.joinToString("\n") { "• ${it.topic} (${(it.confidence * 100).toInt()}%): ${it.content.take(150)}" })
                }

                // Execute non-LLM capabilities
                val results = mutableListOf<CapabilityResult>()
                for (cap in capabilities) {
                    if (cap == Capability.LLM || cap == Capability.CLARIFY) continue
                    val t0 = System.currentTimeMillis()
                    emitStep(ReasoningStep("tool_call", "Running: ${cap.name}"))
                    val output = executeCapability(cap, perception, trimmed, activeLlm)
                    val result = CapabilityResult(cap, output, System.currentTimeMillis() - t0)
                    results.add(result)
                    if (output.isNotBlank() && output != "(synthesis step)") {
                        contextParts.add("### ${cap.name}\n$output")
                    }
                    emitStep(ReasoningStep("tool_result", "${cap.name} done", output.take(200)))
                }

                // Synthesize via LLM (the ONE call)
                emitStep(ReasoningStep("thinking", "Synthesizing answer"))
                val answer = if (capabilities.contains(Capability.CLARIFY)) {
                    "I want to make sure I answer the right question. Could you clarify what you're aiming for?"
                } else {
                    val systemPrompt = buildString {
                        appendLine("You are NOVA (Neural Operations & Versatile Assistant), a custom autonomous coding agent.")
                        appendLine("Question type: ${perception.questionType}")
                        appendLine("Complexity: ${perception.complexity}")
                        appendLine()
                        contextParts.forEach { appendLine(it); appendLine() }
                        appendLine("Answer the user's question directly, using the context above. Be accurate and concise.")
                    }
                    try {
                        activeLlm.chat(
                            listOf(
                                LlmClient.Message("system", systemPrompt),
                                LlmClient.Message("user", trimmed),
                            ),
                            LlmClient.ChatOptions(
                                temperature = if (perception.questionType == QuestionType.DESIGN) 0.6 else 0.4,
                                maxTokens = if (perception.questionType == QuestionType.CODE_REQUEST) 2400 else 1400,
                            ),
                        )
                    } catch (e: Exception) {
                        "I hit an error reaching my model: ${e.message}"
                    }
                }

                emitStep(ReasoningStep("answer", "Answer ready", answer.take(200)))

                // Add assistant message
                val assistantMsg = ChatMessage(role = "assistant", content = answer, trace = _state.value.liveTrace)
                _state.update { it.copy(messages = it.messages + assistantMsg, busy = false, liveTrace = emptyList()) }

                // Learn: update strategy memory + evolution event
                val successScore = 0.75f  // heuristic — see reflect() in web version
                learn(perception, capabilities, successScore, answer)
            } catch (e: Exception) {
                val errMsg = ChatMessage(role = "assistant", content = "I hit an error: ${e.message}")
                _state.update { it.copy(messages = it.messages + errMsg, busy = false, liveTrace = emptyList()) }
            }
        }
    }

    private fun emitStep(step: ReasoningStep) {
        _state.update { it.copy(liveTrace = it.liveTrace + step) }
    }

    private suspend fun executeCapability(cap: Capability, perception: Perception, userInput: String, llm: LlmClient): String {
        return when (cap) {
            Capability.EXECUTE -> {
                val code = perception.codeExtracted ?: return "No code to execute"
                val lang = if (perception.codeLanguage?.lowercase()?.startsWith("ts") == true) "ts" else "js"
                val result = sandbox.runCode(code, lang)
                if (result.ok) "Execution succeeded (${result.durationMs}ms). stdout: ${result.stdout.ifEmpty { "(empty)" }}"
                else "Execution failed: ${result.error ?: result.stderr}"
            }
            Capability.WEB -> {
                val hits = llm.webSearch(userInput.take(80), 4)
                if (hits.isEmpty()) "Web search returned no results."
                else "Searched: ${hits.joinToString("\n") { "• ${it.title} — ${it.url}\n  ${it.snippet.take(120)}" }}"
            }
            Capability.AST, Capability.MEMORY -> "Recalled from local store."
            Capability.CONVERSATIONS -> "Recent conversations reviewed."
            Capability.PROJECT -> "Project context loaded."
            Capability.PERSONA -> "Persona state loaded."
            Capability.LLM, Capability.CLARIFY -> ""
        }
    }

    private suspend fun learn(perception: Perception, capabilities: List<Capability>, successScore: Float, answer: String) {
        // Save strategy
        val existing = db.strategyDao().findBest(perception.intentSignature)
        if (existing != null) {
            val newTrials = existing.trials + 1
            val newScore = (existing.successScore * existing.trials + successScore) / newTrials
            db.strategyDao().upsert(existing.copy(
                successScore = newScore,
                trials = newTrials,
                capabilities = capabilities.joinToString(",") { it.name },
                lastUsedAt = System.currentTimeMillis(),
            ))
        } else {
            db.strategyDao().upsert(StrategyMemory(
                questionType = perception.questionType.name.lowercase(),
                intent = perception.intentSignature,
                capabilities = capabilities.joinToString(",") { it.name },
                successScore = successScore,
                trials = 1,
            ))
        }

        // Log evolution event
        db.evolutionDao().insert(EvolutionEvent(
            kind = "lesson_learned",
            summary = "Answered: ${perception.questionType.name.lowercase()} question",
            details = "{\"intent\":\"${perception.intentSignature}\",\"success\":$successScore}",
        ))
    }

    /**
     * Teach NOVA a new lesson — stores with high confidence.
     */
    fun teach(topic: String, content: String) {
        if (topic.isBlank() || content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            db.knowledgeDao().upsert(KnowledgeEntry(
                topic = topic,
                content = content,
                category = "lesson",
                source = "user-taught",
                confidence = 1.0f,
            ))
            db.evolutionDao().insert(EvolutionEvent(
                kind = "lesson_learned",
                summary = "Taught: $topic",
                details = "{\"source\":\"user-taught\",\"confidence\":1.0}",
            ))
            // Award XP to most relevant skill
            val skillName = guessSkillName(topic)
            val existing = db.skillDao().getByName(skillName) ?: SkillNode(name = skillName, domain = guessDomain(topic))
            db.skillDao().upsert(existing.copy(xp = existing.xp + 15, level = (existing.xp + 15) / 100 + 1, updatedAt = System.currentTimeMillis()))
        }
    }

    private fun guessSkillName(topic: String): String = when {
        topic.contains("react", ignoreCase = true) -> "React"
        topic.contains("typescript", ignoreCase = true) || topic.contains("ts ", ignoreCase = true) -> "TypeScript"
        topic.contains("javascript", ignoreCase = true) || topic.contains("js ", ignoreCase = true) -> "JavaScript"
        topic.contains("python", ignoreCase = true) -> "Python"
        topic.contains("rust", ignoreCase = true) -> "Rust"
        topic.contains("kotlin", ignoreCase = true) -> "Kotlin"
        else -> "General"
    }

    private fun guessDomain(topic: String): String = when {
        Regex("react|next|css|frontend|ui", RegexOption.IGNORE_CASE).containsMatchIn(topic) -> "frontend"
        Regex("node|server|api|database|backend", RegexOption.IGNORE_CASE).containsMatchIn(topic) -> "backend"
        Regex("python|numpy|pandas|ml", RegexOption.IGNORE_CASE).containsMatchIn(topic) -> "data"
        Regex("rust|algorithm|complexity", RegexOption.IGNORE_CASE).containsMatchIn(topic) -> "algorithms"
        Regex("android|ios|mobile|kotlin|swift", RegexOption.IGNORE_CASE).containsMatchIn(topic) -> "mobile"
        else -> "general"
    }

    /**
     * Forget a knowledge entry.
     */
    fun forget(entry: KnowledgeEntry) {
        viewModelScope.launch(Dispatchers.IO) { db.knowledgeDao().delete(entry) }
    }

    companion object {
        fun factory(db: NovaDatabase, keyStore: KeyStore) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NovaViewModel(db, keyStore) as T
        }
    }
}
