package host.vorv.kernel

import host.vorv.tools.Citation
import host.vorv.tools.Tool

/**
 * KernelApi — the SHARED DISPATCH INTERFACE for the cognitive kernel
 * (v0.6.8 patch-dispatch fix).
 *
 * Same rationale as [host.vorv.healer.HealerApi]: patched classes load
 * under their own classloader and can only be used through an interface
 * both the original and the patch implement. AgentViewModel must construct
 * the kernel through [host.vorv.watchdog.PatchRegistry.create] with this
 * interface for a patch of the kernel to actually run.
 */
interface KernelApi {

    data class TaskResult(
        val ok: Boolean,
        val answer: String,
        val iterations: Int,
        val citations: List<Citation>,
        val toolCalls: Int,
        val error: String? = null,
    )

    suspend fun run(
        taskId: String,
        prompt: String,
        llm: host.vorv.llm.Llm,
        workspaceUri: String?,
        confirm: (suspend (String) -> Boolean)? = null,
    ): TaskResult
}

/** The trace-step types shared between the kernel and the UI. */
sealed class TraceStep {
    data class Thinking(val label: String, val detail: String? = null) : TraceStep()
    data class ToolStart(val iteration: Int, val tool: String, val input: com.google.gson.JsonObject) : TraceStep()
    data class ToolEnd(val iteration: Int, val tool: String, val result: host.vorv.tools.ToolResult) : TraceStep()
    data class Answer(val text: String, val citations: List<Citation>) : TraceStep()
    data class Error(val message: String) : TraceStep()
    data class Done(val summary: String, val iterations: Int) : TraceStep()
    /**
     * Real measured telemetry from the LLM call. tok/s and generate-time
     * come straight from the GenieX profiling data for local inference —
     * never fabricated.
     */
    data class Telemetry(val tokensPerSecond: Float, val generateMs: Long, val engine: String) : TraceStep()
}

/** The tool list stays a plain List<Tool> — tools are dispatchable directly. */
typealias ToolList = List<Tool>
