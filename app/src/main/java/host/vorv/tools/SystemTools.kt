package host.vorv.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "Vorv/ShellTool"

/**
 * DialGuard (audit finding H-1): refuses premium-rate and malformed numbers.
 * ACTION_DIAL only pre-fills the dialer, but a confused model could still
 * aim the user at a pay-per-call number; combined with the user-confirmation
 * dialog this closes the loop.
 */
object DialGuard {
    private val premiumPatterns = listOf(
        Regex("""^\+?1-?9(00|76)"""),          // US 1-900 / 1-976 premium
        Regex("""^09\d{6,}$"""),                // UK 09xx premium
        Regex("""^0900"""),                     // DE 0900 premium-rate (M12: was '^090',
                                              // which matched EVERY Japanese mobile)
        Regex("""^89\d{6,}$"""),                // IT 89x value-added
        // L-2 (code review, 2026-11): HK adult 166x lines are EIGHT digits.
        // The old '^166\d' prefix form matched the international form of
        // every US number with area code 660–669 written without '+'
        // ("1-660-555-0123" → "16605550123" → refused) — an 11-digit US
        // number is not an HK adult line. Anchoring to exactly 8 digits
        // keeps every real HK 166xxxxx refusal and clears the false
        // positive; the user-confirmation dialog remains the backstop.
        Regex("""^166\d{5}$"""),                // HK 8-digit adult 166xxxxx
        Regex("""^190\d"""),                    // AU 190x premium
        Regex("""^118\d{3}$"""),                // UK directory 118xxx
        Regex("""^1-?877-?ext"""),              // obviously malformed junk
    )

    /**
     * M12 (code review, 2026-11): Japanese mobile prefixes — 090/080/070
     * followed by 8 digits — are ORDINARY numbers, not premium. The old
     * patterns refused every one of them ('^09\d{6,}$' and '^090' both
     * match 090-XXXX-XXXX), so the tool blocked normal Japanese calls.
     * International form (+81 90-…) drops the leading 0, hence 0?.
     * An 11-digit 090… number is ALSO a valid UK premium shape — that
     * collision is inherent without a country code; the JP mobile
     * interpretation wins (the finding's exact case) and the
     * user-confirmation dialog remains the backstop for every dial.
     */
    private val jpMobile = Regex("""^(\+81[ -]?)?0?[789]0\d{8}$""")

    /** null = acceptable; non-null = honest refusal reason. */
    fun validate(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "empty phone number"
        val allowedChars = trimmed.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' || it == '.' }
        if (!allowedChars) return "phone number contains invalid characters"
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 7) return "phone number too short (${digits.length} digits)"
        if (digits.length > 15) return "phone number too long (${digits.length} digits, max 15)"
        val normalized = trimmed.replace(Regex("[\\s().-]"), "")
        // M12: Japanese mobile shapes are checked BEFORE the premium list —
        // see [jpMobile].
        if (jpMobile.containsMatchIn(normalized)) return null
        val hit = premiumPatterns.firstOrNull { it.containsMatchIn(normalized) }
        if (hit != null) return "refusing to dial premium-rate number '$trimmed'"
        return null
    }
}

/**
 * Monotonic, collision-free notification ids (audit L-10): derived from a
 * random start + AtomicInteger, never from a truncated timestamp.
 */
object NotificationIds {
    private val next = java.util.concurrent.atomic.AtomicInteger(
        (System.currentTimeMillis() % 1_000_000L).toInt() + 1000)

    fun nextId(): Int = next.incrementAndGet()
}

/**
 * app_intent — send real Android intents (audit findings H-1, L-2, L-3).
 *
 * EVERY action requires explicit user confirmation through the consent
 * bridge (ctx.confirm). The dialog shows exactly what will happen, in
 * full — nothing is ever cut off (v0.6.11, audit N-I4). If the
 * bridge is unavailable the tool DENIES — fail closed, never open.
 *
 * "view" additionally restricts to http/https URLs (no file://, no app deep
 * links); "dial" additionally enforces DialGuard.
 */
class AppIntentTool(private val appContext: Context) : Tool {
    override val name = "app_intent"
    override val description =
        "Send an Android intent: open an http/https URL, share text, dial a number, " +
        "or open an installed app. EVERY action requires the user to approve a " +
        "confirmation dialog first — if they deny it, report that the action was " +
        "not performed. Premium-rate numbers are refused outright."

    override val timeoutMs: Long get() = 70_000L   // confirmation dialog + intent dispatch
    override val maxCallsPerTask: Int get() = 15

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Intent action: 'view', 'share', 'dial', 'open_app'")
            })
            add("data", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Action-specific data. view=http(s) URL, dial=phone number, open_app=package name, share=text to share.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("action"); add("data") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val action = input.get("action")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing action",
                durationMs = System.currentTimeMillis() - t0)
        val data = input.get("data")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing data",
                durationMs = System.currentTimeMillis() - t0)

        val confirm = ctx.confirm
            ?: return@withContext ToolResult(ok = false, output = "",
                error = "User confirmation bridge unavailable — refusing to send intent (fail closed).",
                durationMs = System.currentTimeMillis() - t0)

        try {
            val intent = when (action.lowercase()) {
                "view" -> {
                    val uri = Uri.parse(data)
                    val scheme = uri.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") {
                        return@withContext ToolResult(ok = false, output = "",
                            error = "Refusing to view URL with scheme '$scheme' — only http/https allowed.",
                            durationMs = System.currentTimeMillis() - t0)
                    }
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "dial" -> {
                    val refusal = DialGuard.validate(data)
                    if (refusal != null) {
                        return@withContext ToolResult(ok = false, output = "", error = refusal,
                            durationMs = System.currentTimeMillis() - t0)
                    }
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(data)}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "share" -> Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, data)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                "open_app" -> appContext.packageManager.getLaunchIntentForPackage(data)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?: return@withContext ToolResult(ok = false, output = "",
                        error = "App '$data' not installed",
                        durationMs = System.currentTimeMillis() - t0)
                else -> return@withContext ToolResult(ok = false, output = "",
                    error = "unknown action: $action",
                    durationMs = System.currentTimeMillis() - t0)
            }

            val summary = when (action.lowercase()) {
                "view" -> "open $data in your browser"
                "dial" -> "open the dialer with $data"
                "share" -> "share the text: \"$data\""
                else -> "launch the app '$data'"
            }
            val allowed = confirm("Vorv wants to $summary")
            if (!allowed) {
                return@withContext ToolResult(ok = false, output = "",
                    error = "User DENIED the confirmation to $summary. Do not claim it happened.",
                    durationMs = System.currentTimeMillis() - t0)
            }

            appContext.startActivity(intent)
            ToolResult(
                ok = true,
                output = "User approved; intent '$action' sent: $data",
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "intent iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "intent failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }
}

/**
 * shell — run bash commands via Termux's RUN_COMMAND intent with REAL
 * output capture (audit finding C-3).
 *
 * v0.5.x lied: RUN_COMMAND is fire-and-forget, so the tool returned a
 * placeholder string with ok=true and the LLM treated it as command output.
 *
 * v0.6.0 does the round-trip for real:
 *  1. Requires a workspace folder granted on PRIMARY local storage (the
 *     SAF tree URI is mapped to its /storage/emulated/0/... path).
 *  2. Creates a hidden .vorv-shell/ directory in the workspace.
 *  3. Dispatches: bash -c "printf %s <b64> | base64 -d | bash > out 2> err; echo $? > code"
 *     — the user command travels base64-encoded (no quoting hell), and the
 *     wrapper writes stdout/stderr/exit-code to files BOTH Termux and Vorv
 *     can reach.
 *  4. Polls for the exit-code file via SAF until it appears or the timeout
 *     expires.
 *  5. Reads the REAL stdout/stderr/exit-code, returns them, cleans up.
 *
 * v0.6.9 (audit H-2): shell now goes through the USER-CONSENT bridge with
 * the command text in the dialog — the same bar as app_intent. Arbitrary
 * bash is strictly more powerful than opening a URL; before this fix it
 * was the ONLY powerful action without a gate. Fail-closed when the
 * bridge is unavailable.
 *
 * v0.6.11 (audit N-I4): the dialog shows the COMPLETE command — every
 * line, never truncated. The user approves exactly what they see, and
 * they see all of it.
 *
 * v0.6.14: the RUN_COMMAND wall gets a DOOR. Termux declares that
 * permission protectionLevel="dangerous" — runtime-requestable — so when
 * it is missing, the tool now pops Android's own one-tap permission
 * dialog (after the command consent, so the user knows WHAT the grant is
 * for). Denial or an unavailable dialog falls back to the honest manual
 * error. The tool budget grows 125s → 300s because two serial dialogs
 * (consent up to 60s + permission up to 60s) plus the command timeout
 * must fit inside the kernel-enforced wall clock.
 *
 * v0.6.15 (field finding #3 — "Vorv says it doesn't have access to
 * Termux"): the model DENIED having the tool instead of calling it. The
 * old description led with "Requires: Termux installed, RUN_COMMAND
 * permission granted, ..." — four prerequisites read as a disclaimer of
 * unavailability, so the model answered from that belief and the honest
 * errors never fired. The description now leads with what to DO (call it
 * whenever the user asks for shell work) and explicitly forbids claiming
 * no access without calling. Live status lives in the system prompt via
 * SessionGrants.statusLine().
 *
 * v0.6.15 (session grants): when the user has chosen "Allow all shell
 * commands this session", the per-command dialog is replaced by an audit
 * record (SessionGrants.auditAutoRun — the COMPLETE command into the
 * episode log) plus a counter visible in Setup. The grant is the user's
 * explicit, session-scoped, revocable choice — not a default, never
 * silent. A verified round-trip (exit-code file appeared) marks the
 * session verified so the prompt line can state the truth.
 *
 * Setup requirements, all surfaced as honest errors:
 *  - Termux installed (F-Droid/GitHub build)
 *  - RUN_COMMAND permission granted (one-tap when Vorv asks, or Setup →
 *    Termux integration, or Android Settings)
 *  - Termux storage access enabled (run `termux-setup-storage` once)
 *  - Workspace on primary local storage
 */
class ShellTool(private val appContext: Context) : Tool {
    override val name = "shell"

    /**
     * v0.6.15 (field finding #3): rewritten to lead with what to DO. The
     * old "Requires: ..." opener read as a disclaimer of unavailability —
     * the model answered "I don't have access to Termux" without ever
     * calling. Now it orders the call and forbids the false denial; the
     * prerequisites live in the honest errors that fire only when
     * something is actually missing.
     */
    override val description =
        "Run a bash command in Termux and return the REAL stdout, stderr and exit code. " +
        "Termux is a full Linux environment with the pkg package manager: install " +
        "software with pkg install -y <name> (e.g. pkg install -y git python nodejs " +
        "ripgrep) — NEVER claim Termux cannot install tools or software. ALWAYS call " +
        "this tool whenever the user asks for anything involving Termux, the shell, " +
        "installing software, or running commands on this device. NEVER claim you " +
        "cannot access Termux without calling this tool first — if setup is incomplete " +
        "it returns an honest error naming the exact fix, and it never fakes output."

    override val timeoutMs: Long get() = 300_000L
    override val maxCallsPerTask: Int get() = 10

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("command", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Bash command to run.")
            })
            add("cwd", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Optional absolute working directory inside Termux. Default: Termux home.")
            })
            add("timeout_ms", JsonObject().apply {
                addProperty("type", "integer")
                // Night session 2026-11-08: the model was never told it can
                // RAISE the limit — slow commands (builds, test suites, pkg
                // installs, sleeps) died at the 30s default and the kill was
                // misread as "Termux setup missing". Teach the lever at
                // planning time; the timeout error teaches it at decision
                // time.
                addProperty("description", "Optional per-command timeout in milliseconds, 1000-120000; " +
                    "default 30000. The command is killed at the limit with an honest error. " +
                    "If the command can legitimately run longer than ~25 seconds (builds, test " +
                    "suites, package installs, long sleeps), pass a higher value — e.g. " +
                    "timeout_ms=120000 — instead of letting it die at the default.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("command") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val command = input.get("command")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing command",
                durationMs = System.currentTimeMillis() - t0)

        // ---- v0.6.16 dead-shell circuit breaker (field finding #4): if a
        //      previous dispatch never came back, the Termux one-time setup
        //      wall is up and retrying CANNOT fix it. Fail fast — no consent
        //      dialog, no dispatch, no 30-second wait — with the exact
        //      manual fix. The block lifts only on a verified round-trip
        //      (Setup probe pass or a real command completing). This is
        //      what turns "endless 30s-timeout retry loop" into "one 30s
        //      timeout, then instant honest errors". ----
        SessionGrants.shellBlockedReason?.let { reason ->
            return@withContext ToolResult(ok = false, output = "",
                error = blockedShellMessage(reason),
                durationMs = System.currentTimeMillis() - t0)
        }

        val timeoutMs = (input.get("timeout_ms")?.takeIf { !it.isJsonNull }?.asInt ?: 30_000)
            .coerceIn(1_000, 120_000)
        val cwd = input.get("cwd")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

        // ---- audit H-2: user-consent gate (fail closed). N-I4: the dialog
        //      shows the COMPLETE command — nothing is ever truncated, no
        //      matter how long. A cut-off preview would ask the user to
        //      approve something they cannot fully see. ----
        // v0.6.15 session grant: the user's explicit "Allow all shell
        // commands this session" choice replaces the per-command dialog.
        // The consent surface moves to the audit trail — the COMPLETE
        // command is logged and counted, revocable in Setup, dead with the
        // process. Every other gate below still runs.
        if (SessionGrants.shellAllCommands) {
            SessionGrants.auditAutoRun(command)
        } else {
            val confirm = ctx.confirm
                ?: return@withContext ToolResult(ok = false, output = "",
                    error = "User confirmation bridge unavailable — refusing to run shell " +
                        "commands (fail closed).",
                    durationMs = System.currentTimeMillis() - t0)
            val allowed = confirm("${CONSENT_PREFIX}\n\n$command\n\nAllow?")
            if (!allowed) {
                return@withContext ToolResult(ok = false, output = "",
                    error = "User DENIED the shell command. Do not claim it ran.",
                    durationMs = System.currentTimeMillis() - t0)
            }
        }

        // ---- prerequisite checks (each with an honest, actionable error) ----
        val wsUri = ctx.workspaceUri?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult(ok = false, output = "",
                error = "No workspace granted. Shell output capture round-trips through the " +
                    "workspace folder — ask the user to open the Files tab and grant one " +
                    "(must be on primary local storage).",
                durationMs = System.currentTimeMillis() - t0)

        val rawDir = rawWorkspacePath(wsUri)
            ?: return@withContext ToolResult(ok = false, output = "",
                error = "Workspace is not on primary local storage, so Termux cannot write " +
                    "output the agent can read back. Re-grant the workspace from a folder " +
                    "under internal shared storage (/storage/emulated/0).",
                durationMs = System.currentTimeMillis() - t0)

        val pm = appContext.packageManager
        val termuxInstalled = try { pm.getPackageInfo("com.termux", 0); true } catch (e: Exception) { false }
        if (!termuxInstalled) {
            return@withContext ToolResult(ok = false, output = "",
                error = "Termux not installed. Install it from F-Droid or " +
                    "https://github.com/termux/termux-app/releases — Play Store version is deprecated.",
                durationMs = System.currentTimeMillis() - t0)
        }

        val hasPermission = pm.checkPermission("com.termux.permission.RUN_COMMAND", appContext.packageName) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            // v0.6.14: offer the one-tap system grant instead of only the
            // manual Settings walk. The command consent already happened,
            // so the user knows exactly what this grant is for. Denial,
            // dismissal, timeout or no-UI all fall through to the honest
            // manual error below — fail closed either way.
            val grantedNow = PermissionBridge.request("com.termux.permission.RUN_COMMAND")
            val stillGranted = pm.checkPermission("com.termux.permission.RUN_COMMAND", appContext.packageName) ==
                PackageManager.PERMISSION_GRANTED
            if (!grantedNow || !stillGranted) {
                return@withContext ToolResult(ok = false, output = "",
                    error = missingPermissionError(),
                    durationMs = System.currentTimeMillis() - t0)
            }
        }

        // ---- prepare the .vorv-shell directory via SAF ----
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, Uri.parse(wsUri))
            ?: return@withContext ToolResult(ok = false, output = "", error = "Invalid workspace URI",
                durationMs = System.currentTimeMillis() - t0)
        val shellDir = root.findFile(SHELL_DIR)?.takeIf { it.isDirectory }
            ?: root.createDirectory(SHELL_DIR)
            ?: return@withContext ToolResult(ok = false, output = "",
                error = "Could not create .$SHELL_DIR directory in the workspace.",
                durationMs = System.currentTimeMillis() - t0)

        // L-3 (code review, 2026-11): sweep stragglers from TIMED-OUT
        // dispatches. When the poll gave up, the Termux side may still have
        // been alive and written its out/err/code files AFTER the tool
        // returned — nothing ever deleted them, so .vorv-shell/ accumulated
        // one file-set per dead call, forever. Any file matching our
        // <16-hex>.{out,err,code} naming that is older than
        // [STALE_SHELL_FILE_MS] (far beyond any in-flight poll window) is
        // removed here, before the current call's files are created. The
        // current call's fresh files can never match the age gate.
        runCatching { sweepStaleShellFiles(shellDir) }
            .onFailure { Log.w(TAG, "stale shell-file sweep failed: ${it.message}") }

        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val outName = "$id.out"
        val errName = "$id.err"
        val codeName = "$id.code"

        // ---- build the wrapper: base64-transport the command, capture everything ----
        val b64 = android.util.Base64.encodeToString(command.toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING or android.util.Base64.NO_CLOSE)
        val outPath = shQuote("$rawDir/$SHELL_DIR/$outName")
        val errPath = shQuote("$rawDir/$SHELL_DIR/$errName")
        val codePath = shQuote("$rawDir/$SHELL_DIR/$codeName")
        val workDir = "/data/data/com.termux/files/home"
        val wrapper = "printf '%s' '$b64' | base64 -d | bash > $outPath 2> $errPath; echo \$? > $codePath"

        try {
            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapper))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", cwd ?: workDir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            try {
                appContext.startService(intent)
            } catch (bg: IllegalStateException) {
                // M13 (code review, 2026-11): Android 8+ forbids startService
                // while the app is BACKGROUNDED. The generic handler used to
                // bury this in "shell dispatch failed: …" without naming the
                // cause or the fix — an honest wall with the exact remedy:
                return@withContext ToolResult(
                    ok = false, output = "",
                    error = backgroundStartError(),
                    durationMs = System.currentTimeMillis() - t0,
                )
            }

            // ---- poll for the exit-code file via SAF ----
            val deadline = System.currentTimeMillis() + timeoutMs
            var codeFile: androidx.documentfile.provider.DocumentFile? = null
            while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
                delay(POLL_INTERVAL_MS)
                codeFile = shellDir.findFile(codeName)
                if (codeFile != null) break
            }

            val out = shellDir.findFile(outName)
            val err = shellDir.findFile(errName)

            if (codeFile == null) {
                // Honest failure — do NOT fabricate output (audit C-3).
                out?.delete(); err?.delete()
                return@withContext ToolResult(
                    ok = false,
                    output = "",
                    error = onDispatchTimeout(timeoutMs.toLong()),
                    durationMs = System.currentTimeMillis() - t0,
                )
            }

            // v0.6.15: the chain just verifiably round-tripped (the exit-code
            // file appeared in the shared workspace). Record it — the system
            // prompt can now tell the model the shell WORKS instead of letting
            // it guess "no access" (field finding #3).
            SessionGrants.markShellVerified()

            val exitCode = readTextCapped(codeFile, 64)?.text?.trim()?.toIntOrNull() ?: -1
            val stdoutCap = readTextCapped(out, MAX_OUTPUT_CHARS)
            val stderrCap = readTextCapped(err, MAX_ERR_CHARS)
            val stdoutText = stdoutCap?.let { c ->
                if (c.truncated) c.text + "\n…(stdout truncated at $MAX_OUTPUT_CHARS chars — the command produced more output than shown)" else c.text
            } ?: ""
            val stderrText = stderrCap?.let { c ->
                if (c.truncated) c.text + "\n…(stderr truncated at $MAX_ERR_CHARS chars)" else c.text
            } ?: ""

            out?.delete(); err?.delete(); codeFile.delete()

            ToolResult(
                ok = exitCode == 0,
                output = stdoutText.ifBlank { if (exitCode == 0) "(no output)" else "" },
                stderr = stderrText,
                exitCode = exitCode,
                error = if (exitCode != 0) "command exited with code $exitCode" else null,
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "shell iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "shell dispatch failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }

    /** Maps a primary-storage SAF tree URI to its /storage/emulated/0/... path. */
    private fun rawWorkspacePath(uriStr: String): String? = SafPaths.rawWorkspacePath(uriStr)

    /**
     * L-3 (code review, 2026-11): sweep stale output files left behind by
     * TIMED-OUT shell dispatches. A dispatch that outlived its poll writes
     * its <id>.out/.err/.code files after the tool already returned — the
     * normal cleanup never sees them. Every later shell call sweeps files
     * matching the exact naming scheme that are older than
     * [STALE_SHELL_FILE_MS] (10 min — far beyond the longest poll window of
     * 120 s, so an in-flight neighbor call can never be touched; the
     * current call's own fresh files are likewise immune by age). Pure over
     * DocumentFile, internal so unit tests pin the name filter.
     */
    internal fun sweepStaleShellFiles(shellDir: androidx.documentfile.provider.DocumentFile, nowMs: Long = System.currentTimeMillis()) {
        for (child in shellDir.listFiles()) {
            val name = child.name ?: continue
            if (!STALE_SHELL_NAME.matches(name)) continue
            val modified = child.lastModified()
            if (modified > 0 && nowMs - modified > STALE_SHELL_FILE_MS) {
                runCatching { child.delete() }
                    .onFailure { Log.w(TAG, "could not delete stale shell file $name: ${it.message}") }
            }
        }
    }

    /** <16 hex>.out|.err|.code — exactly what dispatches create. */
    internal val STALE_SHELL_NAME = Regex("^[0-9a-f]{16}\\.(out|err|code)$")

    /** Age gate for the sweep: 10 minutes, vs a 120 s max poll window. */
    internal const val STALE_SHELL_FILE_MS = 10 * 60_000L

    /**
     * Single-quote for shell, escaping embedded quotes: ' → '\''
     * H-1 (code review, 2026-11): now a delegation to the single shared copy
     * [ShellQuote.sh] — the healer and the Termux probe quote through the
     * same helper, so the escaping discipline cannot drift between call sites.
     */
    private fun shQuote(s: String): String = ShellQuote.sh(s)

    /**
     * H2 (code review, 2026-11): capped streaming read of a workspace file.
     * The old readFile() slurped the WHOLE file with readText() and capped
     * only afterwards — a runaway command writing hundreds of MB to stdout
     * OOM-killed the app before the cap could ever apply. Reads at most
     * [maxChars] chars; null when the file is absent/unreadable. The
     * [CappedRead.truncated] flag tells the caller to append the honest
     * truncation marker.
     */
    private fun readTextCapped(f: androidx.documentfile.provider.DocumentFile?, maxChars: Int): CappedRead? = try {
        f?.let { file ->
            if (!file.isFile) null
            else appContext.contentResolver.openInputStream(file.uri)?.use { ins ->
                readCapped(ins, maxChars)
            }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * Single source of truth for the consent heading (v0.6.15).
         * AgentViewModel matches this prefix to offer the "Allow all this
         * session" button on exactly the shell consent — never any other
         * tool's dialog. The consent message is pinned to start with it
         * (TermuxSmokeTest); ConsentPromptTest pins the wording.
         */
        const val CONSENT_PREFIX = "Vorv wants to run a SHELL COMMAND in Termux:"

        private const val SHELL_DIR = ".vorv-shell"
        private const val POLL_INTERVAL_MS = 250L
        private const val MAX_OUTPUT_CHARS = 100_000
        private const val MAX_ERR_CHARS = 20_000

        /** H2 (code review, 2026-11): a capped read — content + whether the
         *  stream held MORE than the cap (the caller appends an honest
         *  truncation marker). */
        internal data class CappedRead(val text: String, val truncated: Boolean)

        /** H2: reads at most [maxChars] characters from [ins] and never
         *  buffers beyond the cap — a runaway command's multi-MB stdout
         *  cannot OOM the process. Pure JVM, unit-tested directly. */
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

        /** v0.6.14 fallback when the one-tap grant was denied, dismissed,
         *  timed out, or no UI was available to ask on. Pinned by
         *  TermuxSetupTest — every wall names its price. */
        internal fun missingPermissionError(): String =
            "RUN_COMMAND permission not granted — the one-tap grant was denied, " +
                "dismissed, or Vorv could not show it (no UI / app in background). " +
                "Grant it instead via: Setup → Termux integration → Grant (one tap), " +
                "or Android Settings → Apps → Vorv → Permissions → Additional " +
                "permissions → Run commands in Termux environment. Also ensure " +
                "~/.termux/termux.properties has allow-external-apps=true (see the " +
                "Termux integration section in Setup)."

        /** M13 (code review, 2026-11): the honest background-start wall.
         *  Android 8+ throws IllegalStateException from startService while
         *  the app is backgrounded — the failure must NAME the cause and
         *  the remedy, not bury them. Pinned verbatim by test. */
        internal fun backgroundStartError(): String =
            "Android blocked the Termux launch: Vorv was in the BACKGROUND " +
                "when the command was dispatched (Android 8+ forbids starting services " +
                "from the background). Bring Vorv to the foreground — open the app — " +
                "and send the command again."

        /**
         * v0.6.16 (field finding #4): the dispatch-timeout error, REBUILT.
         * The old text named three likely causes but not one exact command;
         * the user read "allow-external-apps=true missing" and had no idea
         * what to type where — the recording shows the loop outliving their
         * patience. The new text carries the exact commands, the restart
         * step, the honest cannot-do-it-for-you reason, the verification
         * path, and an explicit do-not-retry order. Pinned by
         * TermuxSmokeTest.
         *
         * v0.6.18 (field findings #6/#7): the commands are now SINGLE-STEP
         * lines. The field user had to type them by hand on a phone
         * keyboard (Vorv's chat was not copyable) and the `&&` one-liner
         * came out as `mkdir-p~/.termux` — bash treated it as one word and
         * failed. Short lines, one command each, numbered.
         */
        internal fun dispatchTimeoutMessage(timeoutMs: Long): String = buildString {
            appendLine("The command was sent to Termux but produced no result within ${timeoutMs / 1000}s.")
            appendLine("This is almost always Termux's ONE-TIME setup missing — Vorv cannot")
            appendLine("fix it for you (the files live inside Termux's private storage, which")
            appendLine("Android blocks every other app from touching). The user must run")
            appendLine("these steps IN TERMUX, one line at a time:")
            appendLine()
            appendLine("1. mkdir -p ~/.termux")
            appendLine("2. echo 'allow-external-apps=true' >> ~/.termux/termux.properties")
            appendLine("3. Fully restart Termux (swipe it from Recents, reopen)")
            appendLine("4. termux-setup-storage   (accept the Android prompt)")
            appendLine()
            appendLine("Then verify: Setup → Termux integration → Test connection — until it")
            appendLine("reports ALL GOOD, further shell commands will fail instantly.")
            appendLine("Do NOT retry this command. Tell the user the four steps above in plain")
            appendLine("English and wait. This command's actual result is UNKNOWN — do not")
            appendLine("claim it succeeded.")
        }

        /**
         * Night session 2026-11-08: the dispatch-deadline verdict, split by
         * what the session actually knows. v0.6.16 raised the dead-shell
         * wall on EVERY dispatch timeout — correct when nothing has ever
         * round-tripped (field finding #4), but wrong for a VERIFIED shell:
         * `sleep 60` at the 30s default is a slow command, not the setup
         * wall, and bricking the session's shell over it turned one honest
         * timeout into a misdiagnosed session kill. Unverified → the wall
         * goes up exactly as before (pinned by TermuxSmokeTest E6).
         * Verified → honest slow-command error, no wall (E7).
         */
        internal fun onDispatchTimeout(timeoutMs: Long): String {
            if (SessionGrants.shellVerified) {
                return slowCommandTimeoutMessage(timeoutMs)
            }
            SessionGrants.blockShell(
                "the command never came back within ${timeoutMs}ms — Termux's " +
                    "one-time setup is missing on this device")
            return dispatchTimeoutMessage(timeoutMs)
        }

        /**
         * Night session 2026-11-08: the honest error for a timeout on a
         * VERIFIED shell — the chain is known-good, so the command is slow
         * or still running. Says what is known, what is not, and the exact
         * retry lever. Pinned by TermuxSmokeTest (E7).
         */
        internal fun slowCommandTimeoutMessage(timeoutMs: Long): String = buildString {
            appendLine("The command did not produce a result within ${timeoutMs / 1000}s (timeout_ms=${timeoutMs}).")
            appendLine("The Termux chain is VERIFIED working this session — this is not a setup")
            appendLine("problem. The command may simply run longer than the budget, and it may")
            appendLine("still be running now: its actual result is UNKNOWN. Do not claim it")
            appendLine("succeeded or failed.")
            appendLine("If it needs more time, retry it with timeout_ms=120000 (the maximum, 120s)")
            appendLine("— e.g. builds, test suites, package installs, long sleeps.")
            appendLine("If it should have finished quickly, the Termux session may have died:")
            appendLine("tell the user to reopen Termux, or check Setup → Termux integration →")
            appendLine("Test connection.")
        }

        /**
         * v0.6.16: the fail-fast error while the dead-shell block is active.
         * The wall, the fix, and the do-not-retry order — short, because the
         * full steps were already delivered with the timeout that set it.
         * v0.6.18: single-step commands (field findings #6/#7) — no `&&`
         * chains for a phone keyboard.
         */
        internal fun blockedShellMessage(reason: String): String =
            "Shell is disabled for the rest of this session ($reason). A previous " +
                "command never came back, which means Termux's one-time manual setup " +
                "is missing — retrying cannot fix it. Do NOT call shell again; instead " +
                "tell the user to run IN TERMUX, one line at a time: (1) mkdir -p " +
                "~/.termux, (2) echo 'allow-external-apps=true' >> ~/.termux/" +
                "termux.properties, (3) fully restart Termux, (4) termux-setup-storage " +
                "— then Setup → Termux integration → Test connection. It re-enables " +
                "automatically when that test passes."
    }
}

/**
 * notify — post a real system notification.
 *
 * v0.6.0: checks POST_NOTIFICATIONS at runtime and fails honestly when it
 * isn't granted (audit finding H-2) — previously the notification silently
 * no-opped while the tool reported success.
 */
class NotifyTool(private val appContext: Context) : Tool {
    override val name = "notify"
    override val description =
        "Post a system notification. Requires the notification permission — if it " +
        "isn't granted, ask the user to enable it in Android Settings → Apps → Vorv → " +
        "Notifications. Use when long tasks complete or the user asked to be reminded."

    override val timeoutMs: Long get() = 5_000L
    override val maxCallsPerTask: Int get() = 10

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("title", JsonObject().apply { addProperty("type", "string") })
            add("text", JsonObject().apply { addProperty("type", "string") })
        })
        add("required", com.google.gson.JsonArray().apply { add("title"); add("text") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val title = input.get("title")?.asString ?: "Vorv"
        val text = input.get("text")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing text",
                durationMs = System.currentTimeMillis() - t0)

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return@withContext ToolResult(ok = false, output = "",
                    error = "POST_NOTIFICATIONS permission not granted — the notification will NOT " +
                        "appear. Ask the user to grant it in Android Settings → Apps → Vorv → " +
                        "Notifications, then try again.",
                    durationMs = System.currentTimeMillis() - t0)
            }
        }

        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // M-6 (code review, 2026-11): this used to create "vorv_agent" —
                // the SAME id AgentService creates for the foreground service —
                // with a different importance. Channels are immutable once
                // created, so whichever component ran first won and the other's
                // importance was silently dropped. User-facing pings now live on
                // their own channel; the FGS owns "vorv_agent_fgs" (and deletes
                // the legacy collided id on upgrade).
                val chan = android.app.NotificationChannel(
                    "vorv_notify", "Vorv Notifications",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Vorv task notifications" }
                nm.createNotificationChannel(chan)
            }
            val notif = androidx.core.app.NotificationCompat.Builder(appContext, "vorv_notify")
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setAutoCancel(true)
                .build()
            nm.notify(NotificationIds.nextId(), notif)

            ToolResult(
                ok = true,
                output = "Notification posted: $title — $text",
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "notify iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "notify failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }
}
