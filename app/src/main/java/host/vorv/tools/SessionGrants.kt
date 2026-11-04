package host.vorv.tools

/**
 * SessionGrants — session-scoped shell access state (v0.6.15).
 *
 * WHY THIS EXISTS. Third real-world test finding: asked to use Termux, Vorv
 * answered "I don't have access to Termux" WITHOUT EVER CALLING the shell
 * tool. The tool was registered, reachable and one consent dialog away —
 * but its description enumerated four setup prerequisites, which a small
 * local model reads as a disclaimer of unavailability, and the system
 * prompt carried live status only for the workspace, never for Termux. The
 * model guessed, guessed "no", and the honest-error machinery never even
 * fired. Two fixes, both anchored here:
 *
 *   1. KNOWLEDGE — [markShellVerified] records that the Termux chain
 *      actually round-tripped this session (the Setup probe passed, or a
 *      real shell command completed). The kernel's system prompt then tells
 *      the model the LIVE truth via [statusLine] instead of letting it
 *      guess. When nothing is verified yet, the line explicitly forbids
 *      the false-denial move: call the tool, report its honest error.
 *
 *   2. CONSENT ERGONOMICS — once shell DOES work, every single command
 *      popped a full consent dialog; a ten-command task became ten dialogs,
 *      training the user to click Allow without reading. [shellAllCommands]
 *      is the user's explicit, session-scoped answer: "Allow all shell
 *      commands this session", chosen on the consent dialog itself (never
 *      the default, never silent). While it holds, ShellTool skips the
 *      per-command dialog but (a) counts every auto-run and (b) audits the
 *      COMPLETE command through [auditSink] into the episode log — the
 *      consent surface moves from per-command dialogs to a per-session
 *      grant plus a permanent, reviewable trail. Revocable anytime from
 *      Setup; dies with the process; never persisted to disk.
 *
 * WHY A PROCESS-LEVEL SINGLETON (same argument as PermissionBridge): binary
 * compatibility with REGISTERED PATCHES. Changing ToolContext's or
 * KernelApi.run's signature would crash every previously-approved patch
 * with NoSuchMethodError. This object adds zero signatures; old patches
 * keep working exactly as before (they simply never see session state —
 * they always ask per command, the behavior they were built for).
 *
 * Fail-closed stance: this object grants NOTHING by itself. It only stores
 * what the user explicitly chose, and the tool still runs every other gate
 * (workspace, Termux installed, RUN_COMMAND permission) before executing.
 */
object SessionGrants {

    /** The user's explicit "all shell commands this session" grant. */
    @Volatile
    private var shellAllCommandsFlag: Boolean = false

    /** Wall-clock ms of the first verified Termux round-trip this session. */
    @Volatile
    private var shellVerifiedAtFlag: Long? = null

    /** Commands executed under the session grant (audit counter). */
    @Volatile
    private var autoRunCountValue: Int = 0

    /**
     * Audit channel installed by AgentViewModel → Room episode log. Every
     * command run under the session grant passes through here with its
     * COMPLETE text — the grant replaces the per-command dialog, not the
     * record of what ran. Null (tests, no UI yet) → counted but unlogged;
     * the counter still shows in Setup.
     */
    @Volatile
    var auditSink: (suspend (kind: String, summary: String, detailsJson: String) -> Unit)? = null

    // ------------------------------------------------------------- state

    val shellAllCommands: Boolean get() = shellAllCommandsFlag
    val shellVerified: Boolean get() = shellVerifiedAtFlag != null
    val shellVerifiedAt: Long? get() = shellVerifiedAtFlag
    val autoRunCount: Int get() = autoRunCountValue

    /** The user chose "Allow all shell commands this session" on a consent dialog. */
    fun grantShellForSession() {
        shellAllCommandsFlag = true
    }

    /** Revoke (Setup button) — the very next command asks again. */
    fun revokeShellGrant() {
        shellAllCommandsFlag = false
    }

    /**
     * The Termux chain verifiably round-tripped this session — Setup probe
     * passed or a real shell command completed. Knowledge, not a grant:
     * per-command dialogs continue unless the user separately granted.
     */
    fun markShellVerified() {
        if (shellVerifiedAtFlag == null) shellVerifiedAtFlag = System.currentTimeMillis()
    }

    /**
     * Record one command executed under the session grant. Called by
     * ShellTool INSTEAD of the per-command dialog — the complete command
     * goes to the audit trail, and the counter the user sees in Setup
     * grows. Audit failures must never block execution (the grant already
     * happened); they are swallowed by the sink installer's own runCatching.
     */
    suspend fun auditAutoRun(command: String) {
        autoRunCountValue++
        auditSink?.let { sink ->
            val safe = command.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            runCatching { sink("shell_session_auto", command.take(200), "{\"command\":\"$safe\",\"n\":$autoRunCountValue}") }
        }
    }

    // -------------------------------------------------------- prompt line

    /**
     * The LIVE Termux status line for the kernel's system prompt — the
     * model reads this instead of guessing. Pure function of the session
     * state; wording pinned by TermuxSmokeTest.
     */
    fun statusLine(): String = when {
        shellVerified && shellAllCommands ->
            "Termux shell: VERIFIED this session (a real command round-tripped). " +
                "The user granted ALL shell commands for this session — run them " +
                "directly; each one is logged and the grant is revocable in Setup."
        shellVerified ->
            "Termux shell: VERIFIED this session (a real command round-tripped) — " +
                "it works. Every command still shows the user a consent dialog first; " +
                "that is normal, not a failure."
        else ->
            "Termux shell: not yet verified this session. Do NOT claim you cannot " +
                "access Termux — call the shell tool when asked. If setup is " +
                "incomplete it returns an honest error naming the exact fix; it " +
                "never fakes output, and neither should you."
    }

    // -------------------------------------------------------------- test

    /** Restore the pristine boot state between tests. */
    fun resetForTest() {
        shellAllCommandsFlag = false
        shellVerifiedAtFlag = null
        autoRunCountValue = 0
        auditSink = null
    }
}
