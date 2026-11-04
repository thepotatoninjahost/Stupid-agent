package host.vorv.tools

import kotlinx.coroutines.runBlocking
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

    @Test
    fun `probe messages for missing workspace and wrong storage are actionable`() {
        assertTrue(TermuxProbe.noWorkspaceMessage().contains("Files tab"))
        assertTrue(TermuxProbe.notPrimaryStorageMessage().contains("/storage/emulated/0"))
    }

    @Test
    fun `probe command is exactly the marker echo - nothing else ever runs`() {
        assertTrue(TermuxProbe.PROBE_COMMAND == "echo vorv-termux-ok")
    }
}
