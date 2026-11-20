package host.vorv.tools

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
 *
 * H-1 (code review, 2026-11-20): this tool is the same capability as
 * [ShellTool] — a POSIX shell in the same guest, with the network and apt —
 * but it used to run with NO consent dialog and no audit record, so one
 * hostile sentence in a fetched page was enough to execute code. It now
 * follows exactly the same discipline as the shell tool: the user approves
 * the COMPLETE command (or granted the session once, in which case the
 * command goes to the audit trail), the dead-shell wall is honoured, and an
 * absent consent bridge fails closed.
 */
class LinuxRunTool(private val guest: GuestShell) : Tool {

    override val name = "linux_run"
    override val description =
        "Run a shell command inside Vorv's own Linux system (Debian 12, on the phone, no root, no " +
            "Termux). 'apt-get install -y' installs packages after 'apt-get update'; there is no apk " +
            "here. The app filesystem persists between calls; files written to " +
            "~ survive. Use it to compile or run code in any language the system can install, and " +
            "to inspect results. The user approves the exact command before it runs. Long commands " +
            "should be split so each stays under a minute."

    /**
     * L-11 (code review, 2026-11-20): the schema promised 600 s while this
     * ceiling was 240 s, so a command the model was told it could run for ten
     * minutes was cut at four. The kernel enforces [timeoutMs]; keeping the
     * tool's own budget strictly below it means the model sees the honest
     * "TIMEOUT — stopped after …" answer instead of a cancelled call.
     */
    override val timeoutMs: Long get() = MAX_TIMEOUT_MS + 30_000L
    override val maxCallsPerTask: Int get() = 40

    companion object {
        /** The longest single command the schema advertises and the budget honours. */
        const val MAX_TIMEOUT_MS = 600_000L
        private const val DEFAULT_TIMEOUT_MS = 180_000L
    }

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("command", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "POSIX shell command, e.g. 'apt-get install -y gcc && gcc --version'.")
            })
            add("timeout_ms", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "Hard limit in milliseconds (default $DEFAULT_TIMEOUT_MS, max $MAX_TIMEOUT_MS).",
                )
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
            val budget = (input.get("timeout_ms")?.asLong ?: DEFAULT_TIMEOUT_MS)
                .coerceIn(1_000L, MAX_TIMEOUT_MS)

            // H-1: the same wall as `shell`. A guest that never came back will
            // not answer this call either — fail fast instead of burning a
            // second multi-minute timeout on it.
            SessionGrants.shellBlockedReason?.let { reason ->
                return@withContext fail(ShellTool.blockedShellMessage(reason))
            }

            // H-1: same consent discipline as `shell`, on the COMPLETE command
            // text. The session grant replaces the per-command dialog with an
            // audit row, never with silence.
            if (SessionGrants.shellAllCommands) {
                SessionGrants.auditAutoRun(command)
            } else {
                val confirm = ctx.confirm
                    ?: return@withContext fail(
                        "User confirmation bridge unavailable — refusing to run a Linux command " +
                            "(fail closed).",
                    )
                if (!confirm("${ShellTool.CONSENT_PREFIX}\n\n$command\n\nAllow?")) {
                    return@withContext fail(
                        "User DENIED the command. Do not claim it ran, and do not retry it " +
                            "without changing what you are asking for.",
                    )
                }
            }

            val shell = try {
                guest.run(command, budget, emptyList(), "/root")
            } catch (e: Exception) {
                // The dispatch never came back: this is the wedge the dead-shell
                // wall exists for (#6), so arm it rather than retrying.
                SessionGrants.blockShell("the Linux dispatch failed: ${e.message ?: e.javaClass.simpleName}")
                return@withContext fail(
                    "The command could not be dispatched to the Linux system: ${e.message}. " +
                        "Nothing ran.",
                )
            }
            if (shell.ok) SessionGrants.markShellVerified()
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
