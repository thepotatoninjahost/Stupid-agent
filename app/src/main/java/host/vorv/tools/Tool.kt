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
 */
data class ToolContext(
    val taskId: String,
    val iteration: Int,
    val workspaceUri: String?,       // SAF URI — null if no folder granted
)

/**
 * Tool contract. Every tool the agent can call implements this.
 *
 * The schema is an OpenAI-style function definition — the LLM
 * gets this list at planning time so it knows what's available.
 */
interface Tool {
    val name: String
    val description: String

    /** JSON schema describing input. e.g. {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]} */
    fun schema(): JsonObject

    /** Execute the tool. MUST return real output, never invented. */
    suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult
}
