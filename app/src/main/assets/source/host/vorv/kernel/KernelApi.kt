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

    /**
     * Live trace stream (thinking / tool start+end / answer / telemetry).
     *
     * C-1 (code review, 2026-11): moved onto the shared interface together
     * with [emitTelemetry]. The ViewModel used to collect the concrete
     * class's flow, which forced a `as? CognitiveKernel` cast on the
     * PatchRegistry result — a cast that can never succeed for a genuinely
     * patched class (different ClassLoader ⇒ different runtime identity)
     * and therefore silently threw every real kernel patch away.
     */
    val trace: kotlinx.coroutines.flow.SharedFlow<TraceStep>

    /**
     * Emit a real measured telemetry point from the active LLM into the
     * trace flow. AgentViewModel wires this to LocalLlm.onTelemetry so the
     * UI shows actual tok/s — never fabricated estimates.
     *
     * C-1: this was the ONLY member AgentViewModel needed off the concrete
     * CognitiveKernel type, and needing it is what made the patched-kernel
     * dispatch dead code. On the interface now, a patched kernel's
     * telemetry reaches the UI exactly like the original's does.
     */
    suspend fun emitTelemetry(tokensPerSecond: Float, generateMs: Long, engine: String)

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
