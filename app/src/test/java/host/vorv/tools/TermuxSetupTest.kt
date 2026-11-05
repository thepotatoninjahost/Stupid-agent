package host.vorv.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Termux setup tests (v0.6.14).
 *
 * The bug from the field recording: asked to use Termux, Vorv hit the
 * RUN_COMMAND permission wall and could only recite a five-step Settings
 * path — honest, but the one-tap grant Android actually supports
 * (protectionLevel="dangerous" = runtime-requestable) was never offered.
 *
 * These tests pin the CONTRACT of the fix:
 *  - the PermissionBridge fails closed when no UI is attached,
 *  - every readiness/permission wall names its price (the one-tap path
 *    where it exists, the exact manual commands where it does not),
 *  - the probe timeout message honestly states which two Termux-side
 *    gates it cannot tell apart, and why it cannot reach them.
 *
 * v0.6.17: the consolidated checklist — every gate visible at once with a
 * live status, and exactly ONE next step (the field complaint was "keeps
 * coming up with new bullshit": gates surfacing one per error, mid-task).
 * These tests pin the whole wall: all four gates, every state, and the
 * next-step ordering.
 *
 * The real dialog and the real Termux round-trip run on the device;
 * here we pin the words the user is shown.
 */
class TermuxSetupTest {

    // ------------------------------------------------------ PermissionBridge

    @Test
    fun `bridge fails closed when no handler is installed`() = runBlocking {
        val saved = PermissionBridge.handler
        PermissionBridge.handler = null
        try {
            assertFalse(PermissionBridge.request("com.termux.permission.RUN_COMMAND"))
        } finally {
            PermissionBridge.handler = saved
        }
    }

    @Test
    fun `bridge returns the handler's answer verbatim`() = runBlocking {
        val saved = PermissionBridge.handler
        PermissionBridge.handler = { true }
        try {
            assertTrue(PermissionBridge.request("any.permission"))
        } finally {
            PermissionBridge.handler = saved
        }
    }

    // ---------------------------------------------------- readiness errors

    @Test
    fun `not installed names the honest install sources`() {
        val msg = TermuxProbe.readinessError(termuxInstalled = false, permissionGranted = false)!!
        assertTrue(msg.contains("Termux is not installed"))
        assertTrue("F-Droid link must be there", msg.contains("F-Droid"))
        assertTrue("GitHub releases link must be there", msg.contains("termux-app/releases"))
        assertTrue("Play Store deprecation must be stated", msg.contains("deprecated"))
    }

    @Test
    fun `missing permission names the one-tap path first`() {
        val msg = TermuxProbe.readinessError(termuxInstalled = true, permissionGranted = false)!!
        assertTrue(msg.contains("GRANT"))
        assertTrue("the one-tap promise must be stated", msg.contains("one tap") || msg.contains("Android asks once"))
        assertTrue("manual Settings fallback must survive", msg.contains("Additional permissions"))
    }

    @Test
    fun `fully ready is null - no wall left`() {
        assertNull(TermuxProbe.readinessError(termuxInstalled = true, permissionGranted = true))
    }

    // ---------------------------------------------- shell fallback message

    @Test
    fun `shell missing-permission fallback names every path and both dialog outcomes`() {
        val msg = ShellTool.missingPermissionError()
        // The honest reasons the one-tap grant did not happen.
        assertTrue(msg.contains("denied"))
        assertTrue(msg.contains("dismissed"))
        assertTrue(msg.contains("could not show it"))
        // The two remaining paths to the grant.
        assertTrue("Setup tab path", msg.contains("Setup → Termux integration"))
        assertTrue("manual Settings path", msg.contains("Additional permissions"))
        // The second Termux-side gate is not forgotten.
        assertTrue("allow-external-apps reminder", msg.contains("allow-external-apps=true"))
        assertFalse("no ellipsis — nothing cut off", msg.contains("…"))
    }

    // ---------------------------------------------------- probe messages

    @Test
    fun `probe timeout names both Termux-side gates and why Vorv cannot reach them`() {
        val msg = TermuxProbe.probeTimeoutMessage(20_000)
        assertTrue("the honest cannot-tell-apart statement", msg.contains("cannot") && msg.contains("apart"))
        assertTrue("allow-external-apps step with the exact command", msg.contains("allow-external-apps=true"))
        assertTrue("termux-setup-storage step", msg.contains("termux-setup-storage"))
        assertTrue("restart Termux instruction", msg.contains("restart Termux"))
        // WHY it can't be automated — the honest limitation.
        assertTrue("private data directory explanation", msg.contains("private data directory"))
        assertFalse("no ellipsis — nothing cut off", msg.contains("…"))
    }

    // ------------------------------ v0.6.18: single-step commands (#6)
    // The field user hand-typed the old `mkdir -p ~/.termux && echo …`
    // one-liner on a phone keyboard (the chat was not copyable) and bash
    // saw `mkdir-p~/.termux` — one word, instant failure. Every command
    // Vorv shows a human must now be ONE short line. No `&&` anywhere.

    @Test
    fun `probe timeout commands are single-step lines - no ampersand chains`() {
        val msg = TermuxProbe.probeTimeoutMessage(20_000)
        assertTrue("mkdir on its own line", msg.contains("\n   mkdir -p ~/.termux\n"))
        assertTrue("echo command intact on its own line",
            msg.contains("\n   echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n"))
        assertFalse("no && one-liner for a phone keyboard", msg.contains("&&"))
    }

    @Test
    fun `gate 4 detail commands are single-step lines - no ampersand chains`() {
        // Both the UNKNOWN (untested) and FAIL (blocked) variants carry the
        // steps; pin them both to single-step lines.
        val untested = TermuxProbe.setupChecklist(primaryWs, true, true, false, false)[3].detail
        val blocked = TermuxProbe.setupChecklist(primaryWs, true, true, false, true)[3].detail
        listOf(untested, blocked).forEach { d ->
            assertTrue(d.contains("mkdir -p ~/.termux"))
            assertTrue(d.contains("echo 'allow-external-apps=true' >> ~/.termux/termux.properties"))
            assertFalse("no && one-liner for a phone keyboard", d.contains("&&"))
        }
    }

    @Test
    fun `probe messages for missing workspace and wrong storage are actionable`() {
        assertTrue(TermuxProbe.noWorkspaceMessage().contains("Files tab"))
        assertTrue(TermuxProbe.notPrimaryStorageMessage().contains("/storage/emulated/0"))
    }

    @Test
    fun `probe command is exactly the marker echo - nothing else ever runs`() {
        assertTrue(TermuxProbe.PROBE_COMMAND == "echo vorv-termux-ok")
    }

    // --------------------------------- consolidated checklist (v0.6.17)

    private val primaryWs = "content://com.android.externalstorage.documents/tree/primary%3AVorv"
    private val sdWs = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AVorv"

    @Test
    fun `checklist all green when workspace primary and chain verified`() {
        val gates = TermuxProbe.setupChecklist(primaryWs, true, true, shellVerified = true, shellBlocked = false)
        assertEquals(4, gates.size)
        assertTrue(gates.all { it.status == TermuxProbe.GateStatus.PASS })
        assertNull("no next step when every gate is open", TermuxProbe.nextStepMessage(gates))
    }

    @Test
    fun `missing workspace fails gate 1 and is THE one next step`() {
        val gates = TermuxProbe.setupChecklist(null, true, true, false, false)
        assertEquals(TermuxProbe.GateStatus.FAIL, gates[0].status)
        assertTrue("must point at the Files tab", gates[0].detail.contains("Files tab"))
        val next = TermuxProbe.nextStepMessage(gates)!!
        assertTrue("first gate first: $next", next.startsWith("NEXT STEP 1/4"))
        assertTrue(next.contains("Workspace folder"))
    }

    @Test
    fun `workspace off primary storage fails gate 1 with the re-grant path`() {
        val gates = TermuxProbe.setupChecklist(sdWs, true, true, false, false)
        assertEquals(TermuxProbe.GateStatus.FAIL, gates[0].status)
        assertTrue("must name primary local storage", gates[0].detail.contains("/storage/emulated/0"))
    }

    @Test
    fun `workspaceOnPrimaryStorage parses the real SAF tree URI shapes`() {
        assertTrue(TermuxProbe.workspaceOnPrimaryStorage(primaryWs))
        assertTrue(TermuxProbe.workspaceOnPrimaryStorage(
            "content://com.android.externalstorage.documents/tree/primary"))
        assertFalse("SD-card volume is not primary", TermuxProbe.workspaceOnPrimaryStorage(sdWs))
        assertFalse("other providers are not external storage", TermuxProbe.workspaceOnPrimaryStorage(
            "content://com.android.providers.downloads.documents/tree/downloads"))
        assertFalse(TermuxProbe.workspaceOnPrimaryStorage(null))
        assertFalse(TermuxProbe.workspaceOnPrimaryStorage(""))
    }

    @Test
    fun `termux missing fails gate 2 with the honest install sources`() {
        val gates = TermuxProbe.setupChecklist(primaryWs, false, false, false, false)
        assertEquals(TermuxProbe.GateStatus.FAIL, gates[1].status)
        assertTrue(gates[1].detail.contains("F-Droid"))
        assertTrue("Play Store deprecation must be stated", gates[1].detail.contains("deprecated"))
    }

    @Test
    fun `permission missing fails gate 3 with the one-tap grant`() {
        val gates = TermuxProbe.setupChecklist(primaryWs, true, false, false, false)
        assertEquals(TermuxProbe.GateStatus.FAIL, gates[2].status)
        assertTrue(gates[2].detail.contains("GRANT"))
    }

    @Test
    fun `untested Termux-side setup is UNKNOWN and the next step is the test`() {
        val gates = TermuxProbe.setupChecklist(primaryWs, true, true, false, false)
        assertEquals(TermuxProbe.GateStatus.UNKNOWN, gates[3].status)
        assertTrue("both manual steps present even before testing",
            gates[3].detail.contains("allow-external-apps=true") &&
                gates[3].detail.contains("termux-setup-storage"))
        val next = TermuxProbe.nextStepMessage(gates)!!
        assertTrue("gates 1-3 pass, so gate 4 is the step: $next", next.startsWith("NEXT STEP 4/4"))
        assertTrue(next.contains("TEST CONNECTION"))
    }

    @Test
    fun `blocked breaker fails gate 4 with the exact steps and lift path`() {
        val gates = TermuxProbe.setupChecklist(primaryWs, true, true, false, true)
        assertEquals(TermuxProbe.GateStatus.FAIL, gates[3].status)
        assertTrue(gates[3].detail.contains("allow-external-apps=true"))
        assertTrue(gates[3].detail.contains("termux-setup-storage"))
        assertTrue("the lift path must be stated", gates[3].detail.contains("lifts the block"))
    }

    @Test
    fun `blocked outranks verified for gate 4 - fail closed like the prompt line`() {
        // Unreachable through the SessionGrants API (markShellVerified
        // clears the block) — but if the two ever disagree, the checklist
        // must fail closed, same order as SessionGrants.statusLine().
        val gates = TermuxProbe.setupChecklist(primaryWs, true, true, shellVerified = true, shellBlocked = true)
        assertEquals(TermuxProbe.GateStatus.FAIL, gates[3].status)
    }

    @Test
    fun `blocked banner names the instant-fail behavior and the lift path`() {
        val msg = TermuxProbe.blockedBannerMessage()
        assertTrue(msg.contains("fails instantly"))
        assertTrue(msg.contains("TEST CONNECTION"))
        assertTrue(msg.contains("lifts the block"))
        assertFalse("no ellipsis — nothing cut off", msg.contains("…"))
        assertFalse("no && one-liner for a phone keyboard", msg.contains("&&"))
    }
}
