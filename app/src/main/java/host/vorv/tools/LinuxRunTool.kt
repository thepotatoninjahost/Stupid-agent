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
 * H-1 (code review, 2026-11): this tool used to run every command with no
 * consent gate at all — `shell` asked, `linux_run` did not, and the model
 * reaches for whichever it likes. It now mirrors `ShellTool`'s gate exactly:
 * the dead-shell breaker first, then the session grant (audited), then the
 * per-command dialog, and fail-closed when the bridge is missing.
 */
class LinuxRunTool(private val guest: GuestShell) : Tool {

    override val name = "linux_run"
    override val description =
        "Run a shell command inside Vorv's own Linux system (Debian 12, on the phone, no root, no " +
            "Termux). 'apt-get install -y' installs packages after 'apt-get update'; there is no apk " +
            "here. The app filesystem persists between calls; files written to " +
            "~ survive. Use it to compile or run code in any language the system can install, and " +
            "to inspect results. Long commands should be split so each stays under a minute."

    override val timeoutMs: Long get() = 240_000L
    override val maxCallsPerTask: Int get() = 40

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("command", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "POSIX shell command, e.g. 'apt-get install -y gcc && gcc --version'.")
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
            // A previous command that never came back means the guest is wedged;
            // retrying cannot fix that (same breaker ShellTool honours).
            SessionGrants.shellBlockedReason?.let { reason ->
                return@withContext fail(ShellTool.blockedShellMessage(reason))
            }

            val budget = (input.get("timeout_ms")?.asLong ?: 180_000L).coerceIn(1_000L, 600_000L)

            // Same consent contract as `shell`: the session grant replaces the
            // dialog but audits every command; without it, the user sees the
            // COMPLETE command and a missing bridge fails closed.
            if (SessionGrants.shellAllCommands) {
                SessionGrants.auditAutoRun(command)
            } else {
                val confirm = ctx.confirm
                    ?: return@withContext fail(
                        "User confirmation bridge unavailable — refusing to run linux_run (fail closed).",
                    )
                if (!confirm("${ShellTool.CONSENT_PREFIX}\n\n$command\n\nAllow?")) {
                    return@withContext fail("User DENIED the linux_run command. Do not claim it ran.")
                }
            }

            val shell = guest.run(command, budget, emptyList(), "/root")
            if (!shell.timedOut) SessionGrants.markShellVerified()
            val header = when {
                shell.ok -> "exit 0"
                shell.timedOut -> "TIMEOUT — stopped after ${budget}ms"
                else -> "exit ${shell.exitCode}"
            }
            val output = shell.output.trim()
            // Same honest cap the shell tool uses: a cut-off transcript says so.
            val shown = capWithMarker(output, 12_000)
            ToolResult(
                ok = shell.ok,
                output = if (shown.isEmpty()) "$header\n(no output)" else "$header\n$shown",
                durationMs = System.currentTimeMillis() - started,
                exitCode = if (shell.ok) 0 else shell.exitCode,
                error = when {
                    shell.ok -> null
                    shell.timedOut -> "command exceeded its time limit"
                    else -> "command failed with exit ${shell.exitCode}"
                },
                // The guest merges stderr into stdout (one pipe), so this is
                // not stderr and must not be labelled as if it were.
                stderr = "",
                citation = Citation("exec", "linux_run", "ran inside Vorv's Linux system"),
            )
        }

    private fun fail(message: String) = ToolResult(
        ok = false, output = "", durationMs = 0, error = message, stderr = message,
    )
}
