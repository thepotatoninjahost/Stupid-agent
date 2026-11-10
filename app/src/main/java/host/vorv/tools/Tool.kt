package host.vorv.tools

import com.google.gson.JsonObject

/**
 * A single piece of evidence the agent can attach to a statement.
 * The UI uses this to render citation chips — tappable proof.
 */
data class Citation(
    val type: String,        // "memory" | "web" | "exec" | "file" | "uncited"
    val ref: String,         // memory ID, URL, tool-call ID, file URI
    val label: String,      // human-readable short label
)

/**
 * Result of running a tool. Always real — never invented.
 */
data class ToolResult(
    val ok: Boolean,
    val output: String,
    val durationMs: Long,
    val stderr: String = "",
    val error: String? = null,
    val citation: Citation? = null,
    val exitCode: Int = if (ok) 0 else 1,
)

/**
 * Context handed to every tool invocation. Tools stay stateless;
 * the context carries everything they need.
 *
 * v0.6.0: [confirm] is the user-consent bridge (audit findings H-1, L-2, L-3).
 * Tools that perform user-visible side effects (intents, dialer, share,
 * launching apps) MUST call it and honor a `false` return. When null
 * (e.g. in unit tests without a UI), sensitive tools must deny by default.
 */
data class ToolContext(
    val taskId: String,
    val iteration: Int,
    val workspaceUri: String?,       // SAF URI — null if no folder granted
    val confirm: (suspend (message: String) -> Boolean)? = null,
    /** Set only by the direct teaching UI, never from model tool arguments. */
    val userAuthoredMemory: Boolean = false,
)

/**
 * M-2 (code review, 2026-11): THE shared honest-truncation helper for tool
 * output caps. The old silent `take(n)` calls (web_fetch 8 000 chars,
 * browser_automate 6 000, file_read 50 000) handed the model a cut-off text
 * with no sign anything was missing — so the model believed it had seen the
 * WHOLE page/file and reasoned on that false belief. Every cap now appends
 * an explicit marker naming the cap and the workaround. Pure string logic
 * so unit tests pin the wording.
 */
internal fun capWithMarker(text: String, maxChars: Int): String =
    if (text.length > maxChars) {
        text.take(maxChars) +
            "\n…(truncated at $maxChars chars — the source produced more than shown; narrow the request or page through the rest)"
    } else text

/**
 * Tool contract. Every tool the agent can call implements this.
 *
 * The schema is an OpenAI-style function definition — the LLM
 * gets this list at planning time so it knows what's available.
 *
 * v0.6.0 kernel-enforced guarantees (audit findings: tool timeouts, rate
 * limits, argument validation):
 *  - [timeoutMs]  — wall-clock budget for a single run() call; the kernel
 *                   wraps execution in withTimeoutOrNull and records an
 *                   honest timeout error when exceeded.
 *  - [maxCallsPerTask] — per-task invocation cap; the kernel refuses with an
 *                   honest error rather than letting a confused model spam
 *                   a tool 20 times.
 */
interface Tool {
    val name: String
    val description: String

    /** JSON schema describing input. e.g. {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]} */
    fun schema(): JsonObject

    /** Execute the tool. MUST return real output, never invented. */
    suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult

    /** Wall-clock budget for one call, enforced by the kernel. */
    val timeoutMs: Long get() = 60_000L

    /** Release per-instance resources when the owning ViewModel is cleared. */
    fun close() = Unit

    /** Max invocations within a single task, enforced by the kernel. */
    val maxCallsPerTask: Int get() = 20
}
