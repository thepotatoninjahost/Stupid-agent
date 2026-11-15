package host.vorv.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import host.vorv.linux.LinuxSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * linux_run — a shell in Vorv's own Linux system.
 *
 * This is the general-purpose capability: install packages, compile in any
 * language the distribution carries, run scripts, keep files between calls.
 * It is not a simulator of a computer; it is one.
 */
class LinuxRunTool(private val appContext: Context) : Tool {

    override val name = "linux_run"
    override val description =
        "Run a shell command inside Vorv's own Linux system (Alpine, on the phone, no root, no " +
            "Termux). Packages install with 'apk add' and persist between calls; files written to " +
            "~ survive. Use it to compile or run code in any language the system can install, and " +
            "to inspect results. Long commands should be split so each stays under a minute."

    override val timeoutMs: Long get() = 240_000L
    override val maxCallsPerTask: Int get() = 40

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("command", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "POSIX shell command, e.g. 'apk add gcc && gcc --version'.")
            })
            add("timeout_ms", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Hard limit in milliseconds (default 180000, max 600000).")
            })
        })
        add("required", JsonArray().apply { add("command") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            val command = input.get("command")?.asString?.trim()
            if (command.isNullOrEmpty()) {
                return@withContext fail("command is required")
            }
            val budget = (input.get("timeout_ms")?.asLong ?: 180_000L).coerceIn(1_000L, 600_000L)

            val system = LinuxSystem(appContext)
            val shell = system.run(command, budget)
            val header = when {
                shell.ok -> "exit 0"
                shell.timedOut -> "TIMEOUT — stopped after ${budget}ms"
                else -> "exit ${shell.exitCode}"
            }
            val output = shell.output.trim()
            ToolResult(
                ok = shell.ok,
                output = if (output.isEmpty()) "$header\n(no output)" else "$header\n$output",
                durationMs = System.currentTimeMillis() - started,
                exitCode = if (shell.ok) 0 else shell.exitCode,
                error = when {
                    shell.ok -> null
                    shell.timedOut -> "command exceeded its time limit"
                    else -> "command failed with exit ${shell.exitCode}"
                },
                stderr = if (shell.ok) "" else output.takeLast(600),
                citation = Citation("exec", "linux_run", "ran inside Vorv's Linux system"),
            )
        }

    private fun fail(message: String) = ToolResult(
        ok = false, output = "", durationMs = 0, error = message, stderr = message,
    )
}
