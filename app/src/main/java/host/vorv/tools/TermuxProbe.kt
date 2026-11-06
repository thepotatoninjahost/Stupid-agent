package host.vorv.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * TermuxProbe — v0.6.14 Setup-tab support for the Termux integration.
 *
 * Three gates sit between Vorv and a working shell tool, and the user used
 * to discover them ONE HONEST ERROR AT A TIME, mid-task:
 *
 *   1. Termux installed                       (app can check)
 *   2. RUN_COMMAND permission granted         (app can check AND request)
 *   3a. allow-external-apps=true in Termux    (app can NOT read or set —
 *       ~/.termux/termux.properties lives inside Termux's private data
 *       directory, unreachable from any other app by Android design)
 *   3b. termux-setup-storage run once         (same — Termux-side)
 *
 * Plus the shell tool's own gate zero — a workspace folder granted on
 * primary local storage — the probe round-trips its output through it.
 *
 * v0.6.17 (field findings #4/#5 aftermath — "keeps coming up with new
 * bullshit"): the gates used to surface ONE PER ERROR, mid-task, so a
 * user who had granted every permission still hit fresh walls minutes
 * later. [setupChecklist] enumerates ALL of them with a live status each,
 * and [nextStepMessage] names exactly ONE action — the first gate still
 * closed. Nothing is held back, nothing floods: the whole wall at once,
 * one brick to fix at a time.
 *
 * This object centralizes the checks, the messages, and a "test the whole
 * chain" probe (echo one marker string through the same base64-transport
 * wrapper ShellTool uses, and see the real result). The Setup tab shows
 * the live checklist, a one-tap GRANT for the permission gate, and this
 * probe; the probe's timeout message names the Termux-side gates with the
 * exact commands to type in Termux — honestly stating the app cannot tell
 * those two apart from its side (no result file appears in either case).
 *
 * The probe returns a TYPED [ProbeResult]: READY (the chain verifiably
 * round-tripped — recorded via SessionGrants.markShellVerified, which
 * also lifts any dead-shell block) or TIMED_OUT (the one-time setup wall
 * — recorded via SessionGrants.blockShell, the SAME breaker the shell
 * tool trips, so the Setup test and real shell calls can never disagree
 * about the wall's state). The ViewModel never string-sniffs the verdict.
 *
 * Consent model: the probe is launched by the user tapping a button whose
 * label shows the exact command ("Test connection — runs
 * `echo vorv-termux-ok` in Termux"); that label IS the informed-consent
 * surface, same as the system permission dialog is for the grant. Nothing
 * else ever runs.
 */
object TermuxProbe {

    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX_PACKAGE = "com.termux"
    const val PROBE_COMMAND = "echo vorv-termux-ok"
    private const val PROBE_TIMEOUT_MS = 20_000L
    private const val POLL_INTERVAL_MS = 250L
    private const val SHELL_DIR = ".vorv-shell"

    /**
     * The reason recorded in SessionGrants when the probe times out — the
     * same dead-shell wall the shell tool's dispatch timeout records.
     * Wording feeds ShellTool's blockedShellMessage prose verbatim.
     */
    const val PROBE_BLOCK_REASON =
        "the connection test never came back — Termux's one-time setup is " +
            "missing on this device"

    // ------------------------------------------- typed probe result (v0.6.17)

    /** What a probe run proved about the Termux chain. */
    enum class ProbeKind {
        /** The marker round-tripped — the whole chain verifiably works. */
        READY,
        /** Dispatched, nothing came back — the Termux-side setup wall. */
        TIMED_OUT,
        /** Some other honest verdict (missing gates, bad workspace, ...). */
        OTHER,
    }

    /**
     * The probe's complete answer: the verbatim human-readable verdict for
     * the Setup card, plus the typed kind so callers act on MEANING, never
     * on string prefixes.
     */
    data class ProbeResult(val verdict: String, val kind: ProbeKind)

    // ------------------------------------ consolidated checklist (v0.6.17)

    /** Live status of one gate row in the Setup checklist. */
    enum class GateStatus { PASS, FAIL, UNKNOWN }

    /** One gate row: number, title, live status, and the exact price of opening it. */
    data class SetupGate(
        val n: Int,
        val title: String,
        val status: GateStatus,
        val detail: String,
    )

    // ---------------------------------------------------------- status

    fun termuxInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true
    } catch (e: Exception) { false }

    fun runCommandGranted(ctx: Context): Boolean =
        ctx.packageManager.checkPermission(RUN_COMMAND_PERMISSION, ctx.packageName) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The single honest answer to "is everything ready?" — null = ready,
     * non-null = the exact wall in front of the next attempt, including
     * the one-tap price of getting past it where one exists.
     * Pure function: the caller does the lookups, tests pin the wording.
     */
    fun readinessError(termuxInstalled: Boolean, permissionGranted: Boolean): String? = when {
        !termuxInstalled ->
            "Termux is not installed. Install it from F-Droid or " +
                "https://github.com/termux/termux-app/releases (the Play Store " +
                "build is deprecated and lacks RUN_COMMAND support)."
        !permissionGranted ->
            "The 'Run commands in Termux environment' permission is not granted " +
                "yet. Tap GRANT below — Android asks once, you allow once, done " +
                "(or: Android Settings → Apps → Vorv → Permissions → Additional " +
                "permissions → Run commands in Termux environment)."
        else -> null
    }

    // ------------------------------------------------- checklist (v0.6.17)
    // Pure: the caller does the lookups, TermuxSetupTest pins every wording.
    // The order IS the setup order — the user fixes gate 1 before gate 4 can
    // even be tested. Every gate is visible at once; nothing is held back
    // for a mid-task surprise. The two Termux-side steps cannot be probed
    // individually (private storage) — their state is inferred honestly:
    // verified round-trip = done, breaker tripped = pending, else untested.

    /**
     * Is the SAF tree URI a folder on primary local storage — the only
     * volume Termux can write that Vorv can read back? Pure string parsing
     * (no android.net.Uri) so unit tests pin it; mirrors the mapping
     * ShellTool and the probe enforce at dispatch time.
     */
    fun workspaceOnPrimaryStorage(uriStr: String?): Boolean {
        if (uriStr.isNullOrBlank()) return false
        val prefix = "content://com.android.externalstorage.documents/tree/"
        if (!uriStr.startsWith(prefix)) return false
        val docId = runCatching {
            java.net.URLDecoder.decode(uriStr.removePrefix(prefix).substringBefore('/'), "UTF-8")
        }.getOrNull() ?: return false
        return docId == "primary" || docId.startsWith("primary:")
    }

    /**
     * v0.6.18 (field finding #6): the exact Termux-side steps, ONE SHORT
     * COMMAND PER LINE. The old `mkdir -p ~/.termux && echo …` one-liner
     * had to be hand-typed on a phone keyboard (finding #7: the chat was
     * not copyable) and came out as `mkdir-p~/.termux` — one word to bash,
     * instant failure, user confused through no fault of their own.
     */
    private fun termuxSideSteps(): String =
        "1) mkdir -p ~/.termux\n" +
            "2) echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
            "3) restart Termux (swipe it from Recents, reopen)\n" +
            "4) termux-setup-storage (accept the Android prompt)"

    /**
     * EVERY gate between the user and a working shell, in setup order, with
     * a live status and the exact price of each. [shellBlocked] outranks
     * [shellVerified] for gate 4 (fail-closed, same order as
     * SessionGrants.statusLine) — the two cannot co-occur through the API.
     */
    fun setupChecklist(
        workspaceUri: String?,
        termuxInstalled: Boolean,
        runCommandGranted: Boolean,
        shellVerified: Boolean,
        shellBlocked: Boolean,
    ): List<SetupGate> {
        val wsGranted = !workspaceUri.isNullOrBlank()
        val wsPrimary = workspaceOnPrimaryStorage(workspaceUri)
        val gate1 = when {
            !wsGranted -> SetupGate(1, "Workspace folder", GateStatus.FAIL,
                "Not granted yet. Open the Files tab and grant a folder on primary " +
                    "local storage (under /storage/emulated/0) — shell output capture " +
                    "and the connection test round-trip through it.")
            !wsPrimary -> SetupGate(1, "Workspace folder", GateStatus.FAIL,
                "Granted, but not on primary local storage — Termux cannot write " +
                    "anything Vorv can read back. Re-grant from a folder under " +
                    "/storage/emulated/0 in the Files tab.")
            else -> SetupGate(1, "Workspace folder", GateStatus.PASS,
                "Granted, on primary local storage.")
        }
        val gate2 = if (termuxInstalled) SetupGate(2, "Termux installed", GateStatus.PASS, "Installed.")
        else SetupGate(2, "Termux installed", GateStatus.FAIL,
            "Not installed — get it from F-Droid or " +
                "github.com/termux/termux-app/releases (the Play Store build is " +
                "deprecated and lacks RUN_COMMAND).")
        val gate3 = if (runCommandGranted) SetupGate(3, "'Run commands in Termux' permission", GateStatus.PASS, "Granted.")
        else SetupGate(3, "'Run commands in Termux' permission", GateStatus.FAIL,
            "Not granted — tap GRANT below (one tap), or Android Settings → Apps → " +
                "Vorv → Permissions → Additional permissions.")
        val gate4 = when {
            shellBlocked -> SetupGate(4, "Termux one-time setup (2 steps, inside Termux)", GateStatus.FAIL,
                "PENDING — a dispatched command never came back, which means these " +
                    "are missing. In Termux, one line at a time:\n${termuxSideSteps()}\n" +
                    "Then tap TEST CONNECTION below — a pass lifts the block automatically.")
            shellVerified -> SetupGate(4, "Termux one-time setup (2 steps, inside Termux)", GateStatus.PASS,
                "Verified this session — a real command round-tripped; both steps are done.")
            else -> SetupGate(4, "Termux one-time setup (2 steps, inside Termux)", GateStatus.UNKNOWN,
                "Not tested this session. In Termux, one line at a time:\n${termuxSideSteps()}\n" +
                    "Tap TEST CONNECTION to verify both at once.")
        }
        return listOf(gate1, gate2, gate3, gate4)
    }

    /**
     * The ONE action to take now: the first gate still not green. Null when
     * every gate is open — the caller shows the all-clear instead.
     */
    fun nextStepMessage(gates: List<SetupGate>): String? {
        val first = gates.firstOrNull { it.status != GateStatus.PASS } ?: return null
        return "NEXT STEP ${first.n}/4 — ${first.title}: ${first.detail}"
    }

    /**
     * The red banner while the dead-shell circuit breaker is active. The
     * model's status line points the user here — this is what they find.
     */
    fun blockedBannerMessage(): String =
        "SHELL BLOCKED — the last dispatched command never came back " +
            "(Termux one-time setup missing). Every shell call now fails " +
            "instantly instead of waiting 30 seconds, and the assistant knows " +
            "to tell you the exact steps instead of retrying. Fix gate 4 " +
            "below, then tap TEST CONNECTION — a pass lifts the block " +
            "automatically."

    // ---------------------------------------------------------- probe

    /**
     * Runs [PROBE_COMMAND] in Termux through the proven ShellTool wrapper
     * pattern (base64 transport, output files in the workspace, exit-code
     * poll) and returns a COMPLETE typed verdict for the Setup card. Never
     * throws; every failure becomes honest text.
     *
     * Session-state side effects (symmetric with ShellTool's own dispatch
     * sites, so the probe and the tool can never disagree): a full
     * round-trip calls [SessionGrants.markShellVerified] (lifting any
     * dead-shell block); a timeout calls [SessionGrants.blockShell] with
     * [PROBE_BLOCK_REASON] — the same breaker the shell tool trips.
     */
    suspend fun runProbe(appContext: Context, workspaceUri: String?): ProbeResult = withContext(Dispatchers.IO) {
        val wsUri = workspaceUri?.takeIf { it.isNotBlank() }
            ?: return@withContext ProbeResult(noWorkspaceMessage(), ProbeKind.OTHER)

        if (!termuxInstalled(appContext)) return@withContext ProbeResult(
            readinessError(termuxInstalled = false, permissionGranted = false)!!, ProbeKind.OTHER)

        if (!runCommandGranted(appContext)) return@withContext ProbeResult(
            readinessError(termuxInstalled = true, permissionGranted = false)!!, ProbeKind.OTHER)

        val rawDir = rawWorkspacePath(wsUri)
            ?: return@withContext ProbeResult(notPrimaryStorageMessage(), ProbeKind.OTHER)

        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, Uri.parse(wsUri))
            ?: return@withContext ProbeResult(
                "Workspace folder could not be opened — re-grant it in the Files tab.", ProbeKind.OTHER)
        val shellDir = root.findFile(SHELL_DIR)?.takeIf { it.isDirectory }
            ?: root.createDirectory(SHELL_DIR)
            ?: return@withContext ProbeResult(
                "Could not create .$SHELL_DIR in the workspace — re-grant it in the Files tab.", ProbeKind.OTHER)

        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val outName = "$id.out"; val errName = "$id.err"; val codeName = "$id.code"
        val b64 = android.util.Base64.encodeToString(
            PROBE_COMMAND.toByteArray(),
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING or android.util.Base64.NO_CLOSE)
        val wrapper = "printf '%s' '$b64' | base64 -d | bash > '$rawDir/$SHELL_DIR/$outName' " +
            "2> '$rawDir/$SHELL_DIR/$errName'; echo \$? > '$rawDir/$SHELL_DIR/$codeName'"

        val dispatched = runCatching {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapper))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            appContext.startService(intent); true
        }.getOrDefault(false)
        if (!dispatched) return@withContext ProbeResult(dispatchFailedMessage(), ProbeKind.OTHER)

        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        var codeDoc: androidx.documentfile.provider.DocumentFile? = null
        while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            codeDoc = shellDir.findFile(codeName)
            if (codeDoc != null) break
        }

        val outDoc = shellDir.findFile(outName)
        val errDoc = shellDir.findFile(errName)
        val out = readDoc(appContext, outDoc) ?: ""
        val err = readDoc(appContext, errDoc) ?: ""
        val code = codeDoc?.let { readDoc(appContext, it)?.trim() }

        // Clean up — the probe must not litter the workspace.
        listOf(outName, errName, codeName).forEach { n -> runCatching { shellDir.findFile(n)?.delete() } }

        when {
            codeDoc == null -> {
                // v0.6.17: the SAME dead-shell wall the shell tool's dispatch
                // timeout records. Trip the shared breaker here too — then the
                // Setup test and real shell calls can never disagree about
                // the wall's state, and every shell call after this fails
                // fast with the manual fix instead of burning 30 seconds.
                SessionGrants.blockShell(PROBE_BLOCK_REASON)
                ProbeResult(probeTimeoutMessage(PROBE_TIMEOUT_MS), ProbeKind.TIMED_OUT)
            }
            code == "0" && out.contains("vorv-termux-ok") -> {
                // The chain verifiably round-tripped. Record it (v0.6.15
                // invariant, now owned by the probe itself) — the system
                // prompt tells the model the shell WORKS, and any dead-shell
                // block lifts: proof, not a guess.
                SessionGrants.markShellVerified()
                ProbeResult(
                    "ALL GOOD — Vorv can run commands in Termux and read their real " +
                        "output back. The shell tool is fully operational.",
                    ProbeKind.READY)
            }
            else -> ProbeResult(
                "The command ran in Termux but did not produce the expected " +
                    "marker (exit code: ${code ?: "?"}). Real stdout: \"${out.take(500)}\". " +
                    "Real stderr: \"${err.take(500)}\".",
                ProbeKind.OTHER)
        }
    }

    // ------------------------------------------------------- messages
    // Pure builders — pinned by TermuxSetupTest. Every wall comes with its
    // price: the one-tap path where it exists, the exact manual commands
    // where it does not, and an honest statement when the app cannot tell
    // two causes apart.

    fun noWorkspaceMessage(): String =
        "No workspace folder granted. The test round-trips its output through " +
            "the workspace — open the Files tab and grant a folder on primary " +
            "local storage first."

    fun notPrimaryStorageMessage(): String =
        "The workspace is not on primary local storage, so Termux cannot write " +
            "anything Vorv can read back. Re-grant the workspace from a folder " +
            "under internal shared storage (/storage/emulated/0) in the Files tab."

    fun dispatchFailedMessage(): String =
        "Termux refused the connection outright (the RUN_COMMAND intent was " +
            "rejected). This usually means Termux was updated or force-stopped " +
            "in a state that dropped the permission — tap RECHECK, then GRANT " +
            "again if needed."

    fun probeTimeoutMessage(timeoutMs: Long): String = buildString {
        appendLine("The command was sent but produced no result within ${timeoutMs / 1000}s.")
        appendLine("Two Termux-side gates can cause this, and Vorv honestly cannot")
        appendLine("tell them apart from its side — in both cases no output file")
        appendLine("ever appears. Check BOTH in Termux, one line at a time:")
        appendLine()
        appendLine("1. Allow external apps (most common miss). In Termux run:")
        appendLine("   mkdir -p ~/.termux")
        appendLine("   echo 'allow-external-apps=true' >> ~/.termux/termux.properties")
        appendLine("   then fully restart Termux (swipe it from Recents, reopen).")
        appendLine()
        appendLine("2. Storage access. In Termux run once:")
        appendLine("   termux-setup-storage   (accept the Android prompt)")
        appendLine()
        appendLine("Why Vorv cannot do these for you: both files live inside Termux's")
        appendLine("private data directory, which Android blocks every other app from")
        appendLine("reading or writing. One manual setup, then the shell works forever.")
    }

    // --------------------------------------------------------- shared

    /**
     * Same mapping as ShellTool — by M-1 (code review, 2026-11) it IS the
     * shared mapping now: [SafPaths.rawWorkspacePath] is the single copy of
     * the primary-storage SAF → path logic, used by ShellTool, this probe
     * and the healer. The v0.6.18 root-grant fix (latent since v0.6.14, in
     * BOTH copies then): `docId.substringBefore(":", "")` returns "" (the
     * missing-delimiter value) for a colon-less docId, so granting the ROOT
     * of primary storage ("tree/primary") made the volume "" and dispatch
     * REJECTED the workspace as not-primary — while workspaceOnPrimaryStorage
     * (correct) showed gate 1 PASS. The checklist said green, the shell
     * said no — exactly the contradiction that burned the field user.
     * Deliberate asymmetry: no colon → volume IS the docId, rest "".
     */
    private fun rawWorkspacePath(uriStr: String): String? = SafPaths.rawWorkspacePath(uriStr)

    private fun readDoc(ctx: Context, f: androidx.documentfile.provider.DocumentFile?): String? = try {
        f?.takeIf { it.isFile }?.let {
            ctx.contentResolver.openInputStream(it.uri)?.use { s -> s.bufferedReader().readText() }
        }
    } catch (e: Exception) { null }
}
