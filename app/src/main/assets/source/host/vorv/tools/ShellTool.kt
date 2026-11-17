package host.vorv.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import host.vorv.linux.LinuxSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * shell — run commands in Vorv's own Linux system.
 *
 * The guest is [LinuxSystem]: a real Debian 12 userland under proot, inside this
 * app, needing no root, no Termux and no other installed app. The tool returns
 * the REAL exit code, stdout and stderr; when the guest is missing or a
 * command overruns its limit it says exactly that and never invents output.
 *
 * The user's workspace is bound into the guest at [GUEST_WORKSPACE] when the
 * folder is on primary storage, so what the shell writes is the same folder the
 * file tools and the user see — no output round-trip through SAF.
 *
 * Consent is unchanged from the previous implementation: the user approves the
 * COMPLETE command text before it runs (fail-closed when the bridge is absent),
 * and the session grant replaces the per-command dialog with an audit record.
 */
class ShellTool(
    private val guest: GuestShell,
    private val appContext: Context,
) : Tool {

    override val name = "shell"

    override val description =
        "Run a shell command inside Vorv's own Linux system (Debian 12, on this phone, no root " +
            "and no Termux) and get the REAL stdout, stderr and exit code. This IS a full Linux " +
            "environment with a package manager: install software with 'apt-get install -y <name>' " +
            "after 'apt-get update' (e.g. apt-get update && apt-get install -y gcc git nodejs) — " +
            "NEVER claim it cannot install tools. There is no apk on this system. ALWAYS " +
            "call this tool for anything involving the shell, compiling, installing software, or " +
            "running commands on this device. Never claim you have no shell without calling this " +
            "first; if something is missing it returns an honest error naming the fix. The user's " +
            "workspace folder is visible at " + GUEST_WORKSPACE + "."

    override val timeoutMs: Long get() = 300_000L
    override val maxCallsPerTask: Int get() = 10

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("command", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "POSIX shell command to run, e.g. 'apt-get update && apt-get install -y gcc'.")
            })
            add("cwd", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Optional working directory. Default: $GUEST_WORKSPACE when a " +
                    "workspace folder is granted, otherwise /root.")
            })
            add("timeout_ms", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Optional per-command timeout in milliseconds, 1000-120000; " +
                    "default 60000. Builds and package installs can legitimately need more — say so " +
                    "with a higher value instead of letting the command die at the default.")
            })
        })
        add("required", JsonArray().apply { add("command") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        fun fail(message: String) = ToolResult(
            ok = false, output = "", error = message, durationMs = System.currentTimeMillis() - t0,
        )

        val command = input.get("command")?.takeIf { !it.isJsonNull }?.asString
            ?: return@withContext fail("missing command")

        // A previous command that never returned at all means the guest itself is
        // wedged; retrying cannot fix that, so stop instead of burning the budget.
        SessionGrants.shellBlockedReason?.let { reason -> return@withContext fail(blockedShellMessage(reason)) }

        val timeoutMs = (input.get("timeout_ms")?.takeIf { !it.isJsonNull }?.asInt ?: 60_000)
            .coerceIn(1_000, 120_000)

        if (SessionGrants.shellAllCommands) {
            SessionGrants.auditAutoRun(command)
        } else {
            val confirm = ctx.confirm
                ?: return@withContext fail(
                    "User confirmation bridge unavailable — refusing to run shell commands (fail closed).",
                )
            if (!confirm("$CONSENT_PREFIX\n\n$command\n\nAllow?")) {
                return@withContext fail("User DENIED the shell command. Do not claim it ran.")
            }
        }

        if (!guest.isAvailable()) {
            return@withContext fail(
                "This build carries no Linux system, so there is nothing to run the command on. " +
                    "Install a build packaged with tools/forge/fetch-linux.sh — do not claim the " +
                    "command ran.",
            )
        }

        val rawWorkspace = ctx.workspaceUri?.takeIf { it.isNotBlank() }?.let { SafPaths.rawWorkspacePath(it) }
        val binds = rawWorkspace?.let { listOf(it to GUEST_WORKSPACE) } ?: emptyList()
        val workDir = input.get("cwd")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            ?: if (rawWorkspace != null) GUEST_WORKSPACE else "/root"

        val shell = try {
            guest.run(command, timeoutMs.toLong(), binds, workDir)
        } catch (e: Exception) {
            return@withContext fail("shell could not start inside the Linux system: ${e.message}")
        }

        if (shell.timedOut) {
            // The process is gone — that is known. Whether the command had already
            // done its work is not, so say both things.
            return@withContext ToolResult(
                ok = false, output = shell.output,
                error = timeoutMessage(timeoutMs.toLong()),
                durationMs = System.currentTimeMillis() - t0,
                exitCode = -1,
                stderr = shell.error.orEmpty(),
            )
        }

        SessionGrants.markShellVerified()
        val out = capWithMarker(shell.output.trimEnd(), MAX_OUTPUT_CHARS)
        ToolResult(
            ok = shell.exitCode == 0,
            output = out.ifBlank { if (shell.exitCode == 0) "(no output)" else "" },
            stderr = shell.error.orEmpty(),
            exitCode = shell.exitCode,
            error = if (shell.exitCode != 0) "command exited with code ${shell.exitCode}" else null,
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("exec", ctx.taskId, "shell iter ${ctx.iteration}"),
        )
    }

    companion object {
        /** Where the user's workspace folder appears inside the guest. */
        const val GUEST_WORKSPACE = "/workspace"

        const val MAX_OUTPUT_CHARS = 100_000

        /**
         * Heading of the consent dialog. AgentViewModel offers its "allow all
         * this session" button on messages that start with this exact string.
         */
        const val CONSENT_PREFIX = "Vorv wants to run a SHELL COMMAND in its own Linux system:"

        /** The wall when the guest never returns at all. Names the one real fix. */
        internal fun blockedShellMessage(reason: String): String =
            "Shell is disabled for the rest of this session ($reason). The Linux system stopped " +
                "answering, and retrying the same call cannot fix that. Do NOT call shell again. " +
                "Tell the user to reboot the phone (or force-stop and reopen Vorv) — the guest is " +
                "recreated on the next command, which re-enables shell automatically."

        /** Honest timeout: what is known, what is not, and the lever. */
        internal fun timeoutMessage(timeoutMs: Long): String =
            "The command was stopped after ${timeoutMs / 1000}s and produced no final result. " +
                "It may have finished its work before being stopped — that is UNKNOWN, so do not " +
                "claim it succeeded or failed. If it legitimately needs longer (builds, package " +
                "installs), retry with timeout_ms=120000, or split it into smaller steps."

        /** Capped streaming read: content plus whether the stream held more. */
        internal data class CappedRead(val text: String, val truncated: Boolean)

        internal fun readCapped(ins: java.io.InputStream, maxChars: Int): CappedRead {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(ins, Charsets.UTF_8))
            val sb = StringBuilder()
            val buf = CharArray(8 * 1024)
            var total = 0
            while (total < maxChars) {
                val n = reader.read(buf, 0, minOf(buf.size, maxChars - total))
                if (n <= 0) break
                total += n
                sb.append(buf, 0, n)
            }
            val truncated = total >= maxChars && reader.read() != -1
            return CappedRead(sb.toString(), truncated)
        }
    }
}
