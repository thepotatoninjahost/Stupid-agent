package host.vorv.kernel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import host.vorv.data.VorvDatabase
import host.vorv.data.entity.Episode
import host.vorv.data.entity.ToolCall
import host.vorv.llm.Llm
import host.vorv.tools.Citation
import host.vorv.tools.Tool
import host.vorv.tools.ToolContext
import host.vorv.tools.ToolResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Step in the live reasoning trace. UI observes these in real-time.
 */
sealed class TraceStep {
    data class Thinking(val label: String, val detail: String? = null) : TraceStep()
    data class ToolStart(val iteration: Int, val tool: String, val input: JsonObject) : TraceStep()
    data class ToolEnd(val iteration: Int, val tool: String, val result: ToolResult) : TraceStep()
    data class Answer(val text: String, val citations: List<Citation>) : TraceStep()
    data class Error(val message: String) : TraceStep()
    data class Done(val summary: String, val iterations: Int) : TraceStep()
}

/**
 * Result of running a full task.
 */
data class TaskResult(
    val ok: Boolean,
    val answer: String,
    val iterations: Int,
    val citations: List<Citation>,
    val toolCalls: Int,
    val error: String? = null,
)

/**
 * Real ReAct loop. perceive → plan → tool → observe → repeat.
 *
 * Hard cap: MAX_ITERATIONS. On hitting cap, summarize what happened and stop.
 * Every iteration logged to tool_calls table for verifiable replay.
 */
class CognitiveKernel(
    private val db: VorvDatabase,
    private val tools: List<Tool>,
) {
    companion object {
        const val MAX_ITERATIONS = 20
    }

    private val _trace = MutableSharedFlow<TraceStep>(replay = 0, extraBufferCapacity = 64)
    val trace: SharedFlow<TraceStep> = _trace.asSharedFlow()

    private val toolMap: Map<String, Tool> = tools.associateBy { it.name }

    /**
     * Callback the ViewModel can use to write iteration count back to the task record
     * after each loop iteration. Fixes the "0 iters" bug.
     */
    var onIteration: suspend (taskId: String, iterations: Int) -> Unit = { _, _ -> }

    suspend fun run(taskId: String, prompt: String, llm: Llm, workspaceUri: String?): TaskResult {
        db.episodeDao().insert(Episode(kind = "task_started", summary = prompt.take(200), detailsJson = "{\"id\":\"$taskId\"}"))

        val toolSpecs = tools.map { t ->
            Llm.ToolSpec(name = t.name, description = t.description, parameters = t.schema())
        }

        val messages = mutableListOf<Llm.Message>()
        messages.add(Llm.Message("system", buildSystemPrompt(workspaceUri)))
        messages.add(Llm.Message("user", prompt))

        val citations = mutableListOf<Citation>()
        var iterations = 0
        var lastError: String? = null

        try {
            while (iterations < MAX_ITERATIONS) {
                iterations++
                // Fix: write iteration count back to task record every iteration
                onIteration(taskId, iterations)
                _trace.emit(TraceStep.Thinking("Iteration $iterations", "Planning next step…"))

                val resp = try {
                    llm.step(messages, toolSpecs)
                } catch (e: Exception) {
                    lastError = e.message
                    _trace.emit(TraceStep.Error("LLM call failed: ${e.message}"))
                    break
                }

                when (resp) {
                    is Llm.LlmResponse.Text -> {
                        // Done. Check for inline citations, post answer.
                        val clean = stripCitationTags(resp.content, citations)
                        // Citation enforcement: if no tool was called AND no inline citation
                        // was attached, the model produced an uncited answer. Flag it so the
                        // UI can render a warning chip. This is not a block — the user still
                        // sees the answer — but it is honest about provenance.
                        val uncited = citations.isEmpty() && iterations == 1
                        val finalClean = if (uncited) "[uncited] $clean" else clean
                        messages.add(Llm.Message("assistant", resp.content))
                        _trace.emit(TraceStep.Answer(finalClean, citations.toList()))
                        _trace.emit(TraceStep.Done(finalClean.take(200), iterations))
                        db.episodeDao().insert(Episode(kind = "task_completed", summary = finalClean.take(200), detailsJson = "{\"iterations\":$iterations,\"uncited\":$uncited}"))
                        return TaskResult(true, finalClean, iterations, citations.toList(), iterations - 1)
                    }
                    is Llm.LlmResponse.ToolUse -> {
                        // Record assistant message with tool_calls
                        messages.add(Llm.Message("assistant", resp.reasoning ?: "", toolCalls = resp.toolCalls))

                        for (tc in resp.toolCalls) {
                            val tool = toolMap[tc.name]
                            if (tool == null) {
                                val errMsg = "Unknown tool: ${tc.name}"
                                _trace.emit(TraceStep.Error(errMsg))
                                messages.add(Llm.Message("tool", "{\"error\":\"$errMsg\"}", toolCallId = tc.id))
                                continue
                            }

                            val input = try {
                                JsonParser.parseString(tc.arguments).asJsonObject
                            } catch (e: Exception) {
                                JsonObject().apply { addProperty("error", "Bad arguments JSON: ${e.message}") }
                            }

                            _trace.emit(TraceStep.ToolStart(iterations, tc.name, input))

                            val tCtx = ToolContext(
                                taskId = taskId,
                                iteration = iterations,
                                workspaceUri = workspaceUri,
                            )

                            val t0 = System.currentTimeMillis()
                            val result = try {
                                tool.run(input, tCtx)
                            } catch (e: Exception) {
                                ToolResult(ok = false, output = "", stderr = "", error = e.message, durationMs = System.currentTimeMillis() - t0)
                            }
                            val actualMs = System.currentTimeMillis() - t0

                            // Persist verifiable log
                            db.toolCallDao().insert(ToolCall(
                                taskId = taskId,
                                iteration = iterations,
                                toolName = tc.name,
                                inputJson = input.toString(),
                                outputJson = result.output.take(2000),
                                exitCode = result.exitCode,
                                durationMs = actualMs,
                                errorMessage = result.error,
                            ))

                            // Update skill tracker
                            if (result.ok) db.skillDao().recordSuccess(tc.name)
                            else db.skillDao().recordFailure(tc.name)

                            // Capture citation
                            result.citation?.let { citations.add(it) }

                            _trace.emit(TraceStep.ToolEnd(iterations, tc.name, result))

                            // Feed real result back to LLM — never invented
                            val toolResultJson = JsonObject().apply {
                                addProperty("ok", result.ok)
                                addProperty("output", result.output.take(2000))
                                if (result.error != null) addProperty("error", result.error)
                                if (result.citation != null) add("citation", JsonObject().apply {
                                    addProperty("type", result.citation.type)
                                    addProperty("ref", result.citation.ref)
                                    addProperty("label", result.citation.label)
                                })
                            }
                            messages.add(Llm.Message("tool", toolResultJson.toString(), toolCallId = tc.id))
                        }
                    }
                }
            }

            // Hit cap — summarize
            val summary = "Reached max iterations ($MAX_ITERATIONS) without a final answer. " +
                "Last ${iterations} tool calls executed; see Tasks tab for the full log."
            _trace.emit(TraceStep.Done(summary, iterations))
            db.episodeDao().insert(Episode(kind = "task_completed", summary = "stuck", detailsJson = "{\"iterations\":$iterations}"))
            return TaskResult(false, summary, iterations, citations.toList(), iterations, error = "max_iterations")

        } catch (e: Exception) {
            lastError = e.message
            _trace.emit(TraceStep.Error("Kernel exception: ${e.message}"))
            db.episodeDao().insert(Episode(kind = "error", summary = e.message ?: "unknown", detailsJson = "{\"taskId\":\"$taskId\"}"))
            return TaskResult(false, "Error: ${e.message}", iterations, citations.toList(), iterations - 1, error = e.message)
        }
    }

    private fun buildSystemPrompt(workspaceUri: String?): String = buildString {
        appendLine("You are Vorv — a local-first autonomous agent on Android.")
        appendLine("You have access to real tools. Pick a tool, see its real output, then pick the next.")
        appendLine()
        appendLine("HARD RULES:")
        appendLine("1. Use the actual tool output. Never invent what a tool returned.")
        appendLine("2. If a tool fails, adapt — try a different approach or ask for clarification.")
        appendLine("3. For factual claims, attach a citation using [cite:<type>:<ref>] syntax. Types: memory, web, exec, file.")
        appendLine("4. If you cannot cite a fact, prefix it with [uncited].")
        appendLine("5. Max $MAX_ITERATIONS iterations per task. Finish when you have the answer.")
        appendLine("6. When done, respond with text only (no tool call).")
        appendLine()
        appendLine("Available tools:")
        tools.forEach { t -> appendLine("- ${t.name}: ${t.description}") }
        appendLine()
        appendLine("Workspace: ${if (workspaceUri.isNullOrBlank()) "(none granted — file tools will fail until user picks a folder in Files tab)" else "granted"}")
    }

    /**
     * Replace [cite:memory:abc123] with a citation chip marker and collect Citation objects.
     */
    private fun stripCitationTags(text: String, out: MutableList<Citation>): String {
        val pattern = Regex("""\[cite:(\w+):([^\]]+)\]""")
        var i = 0
        return pattern.replace(text) { mr ->
            val type = mr.groupValues[1]
            val ref = mr.groupValues[2]
            i++
            out.add(Citation(type, ref, "$type#$ref".take(20)))
            "[$i]"
        }
    }
}
