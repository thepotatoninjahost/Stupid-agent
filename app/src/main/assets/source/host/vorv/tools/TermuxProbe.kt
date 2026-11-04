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
 * This object centralizes the checks, the messages, and a "test the whole
 * chain" probe (echo one marker string through the same base64-transport
 * wrapper ShellTool uses, and see the real result). The Setup tab shows
 * the live status, a one-tap GRANT for gate 2, and this probe; the probe's
 * timeout message names gates 3a/3b with the exact commands to type in
 * Termux — honestly stating the app cannot tell those two apart from its
 * side (no result file appears in either case).
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

    // ---------------------------------------------------------- probe

    /**
     * Runs [PROBE_COMMAND] in Termux through the proven ShellTool wrapper
     * pattern (base64 transport, output files in the workspace, exit-code
     * poll) and returns a COMPLETE human-readable verdict for the Setup
     * card. Never throws; every failure becomes honest text.
     */
    suspend fun runProbe(appContext: Context, workspaceUri: String?): String = withContext(Dispatchers.IO) {
        val wsUri = workspaceUri?.takeIf { it.isNotBlank() }
            ?: return@withContext noWorkspaceMessage()

        if (!termuxInstalled(appContext)) return@withContext readinessError(
            termuxInstalled = false, permissionGranted = false
        )!!

        if (!runCommandGranted(appContext)) return@withContext readinessError(
            termuxInstalled = true, permissionGranted = false
        )!!

        val rawDir = rawWorkspacePath(wsUri)
            ?: return@withContext notPrimaryStorageMessage()

        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, Uri.parse(wsUri))
            ?: return@withContext "Workspace folder could not be opened — re-grant it in the Files tab."
        val shellDir = root.findFile(SHELL_DIR)?.takeIf { it.isDirectory }
            ?: root.createDirectory(SHELL_DIR)
            ?: return@withContext "Could not create .$SHELL_DIR in the workspace — re-grant it in the Files tab."

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
        if (!dispatched) return@withContext dispatchFailedMessage()

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
            codeDoc == null -> probeTimeoutMessage(PROBE_TIMEOUT_MS)
            code == "0" && out.contains("vorv-termux-ok") ->
                "ALL GOOD — Vorv can run commands in Termux and read their real " +
                    "output back. The shell tool is fully operational."
            else ->
                "The command ran in Termux but did not produce the expected " +
                    "marker (exit code: ${code ?: "?"}). Real stdout: \"${out.take(500)}\". " +
                    "Real stderr: \"${err.take(500)}\"."
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
        appendLine("ever appears. Check BOTH in Termux:")
        appendLine()
        appendLine("1. Allow external apps (most common miss). In Termux run:")
        appendLine("   mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties")
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

    /** Same mapping as ShellTool.rawWorkspacePath — primary storage only. */
    private fun rawWorkspacePath(uriStr: String): String? {
        val uri = Uri.parse(uriStr)
        if (uri.authority != "com.android.externalstorage.documents") return null
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != "tree") return null
        val docId = Uri.decode(segments[1])
        val volume = docId.substringBefore(":", "")
        val rest = docId.substringAfter(":", "")
        if (volume != "primary") return null
        return if (rest.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$rest"
    }

    private fun readDoc(ctx: Context, f: androidx.documentfile.provider.DocumentFile?): String? = try {
        f?.takeIf { it.isFile }?.let {
            ctx.contentResolver.openInputStream(it.uri)?.use { s -> s.bufferedReader().readText() }
        }
    } catch (e: Exception) { null }
}
