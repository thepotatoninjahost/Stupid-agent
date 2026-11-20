package host.vorv.tools

import com.google.gson.JsonObject
import host.vorv.linux.LinuxSystem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * shell — the tool the model must reach for, now backed by the app's own
 * Linux system instead of Termux.
 *
 * A [FakeGuest] stands in for proot so the CONTRACT is pinned without a
 * device: consent comes before any execution, a denied command never runs,
 * the workspace is bound where the description promises, the real exit code
 * and stderr come back, and a missing root filesystem is an honest error
 * rather than invented output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShellToolTest {

    private class FakeGuest(
        private val available: Boolean = true,
        private val result: LinuxSystem.Shell = LinuxSystem.Shell(0, "ok\n", null, false),
        private val explode: Exception? = null,
    ) : GuestShell {
        val calls = mutableListOf<Triple<String, List<Pair<String, String>>, String>>()
        override fun isAvailable() = available
        override suspend fun run(
            command: String,
            timeoutMs: Long,
            binds: List<Pair<String, String>>,
            workDir: String,
        ): LinuxSystem.Shell {
            calls += Triple(command, binds, workDir)
            explode?.let { throw it }
            return result
        }
    }

    private val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val workspace = "content://com.android.externalstorage.documents/tree/primary%3AVorv"

    @After
    fun clearSession() = SessionGrants.resetForTest()

    private fun input(command: String = "apk --version") =
        JsonObject().apply { addProperty("command", command) }

    private fun ctx(confirm: (suspend (String) -> Boolean)? = null, workspaceUri: String? = null) =
        ToolContext(taskId = "t1", iteration = 1, workspaceUri = workspaceUri, confirm = confirm)

    /**
     * #6 (code review, 2026-11-20): nothing in production ever armed the
     * dead-shell wall, so the fail-fast guard, the Setup card and the BLOCKED
     * prompt line all described a state the app could not reach.
     */
    @Test
    fun `a dispatch that throws arms the dead-shell wall`() = runBlocking {
        val guest = FakeGuest(explode = IllegalStateException("proot died"))
        val r = ShellTool(guest, context).run(input(), ctx(confirm = { true }))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("Nothing ran"))
        assertTrue(SessionGrants.shellBlocked)
        assertEquals("the failure must be the wall's reason", true, SessionGrants.shellBlockedReason!!.contains("proot died"))
        // And the NEXT call fails fast without touching the guest again.
        val again = ShellTool(guest, context).run(input(), ctx(confirm = { true }))
        assertFalse(again.ok)
        assertEquals(1, guest.calls.size)
    }

    @Test
    fun `missing command fails before anything else`() = runBlocking {
        val guest = FakeGuest()
        val r = ShellTool(guest, context).run(JsonObject(), ctx(confirm = { true }))
        assertFalse(r.ok)
        assertEquals("missing command", r.error)
        assertTrue(guest.calls.isEmpty())
    }

    @Test
    fun `no consent bridge means fail closed`() = runBlocking {
        val guest = FakeGuest()
        val r = ShellTool(guest, context).run(input(), ctx(confirm = null))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("fail closed"))
        assertTrue(guest.calls.isEmpty())
    }

    @Test
    fun `a denied command never reaches the guest`() = runBlocking {
        val guest = FakeGuest()
        val seen = mutableListOf<String>()
        val r = ShellTool(guest, context).run(input("rm -rf /root"), ctx(confirm = { seen += it; false }))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("DENIED"))
        assertTrue(guest.calls.isEmpty())
        assertTrue(seen.single().contains("rm -rf /root"))
    }

    @Test
    fun `an approved command runs and reports the real result`() = runBlocking {
        val guest = FakeGuest(result = LinuxSystem.Shell(0, "Alpine 3.20\n", null, false))
        val r = ShellTool(guest, context).run(input("cat /etc/alpine-release"), ctx(confirm = { true }))
        assertTrue(r.ok)
        assertEquals(0, r.exitCode)
        assertEquals("Alpine 3.20", r.output)
        assertEquals("cat /etc/alpine-release", guest.calls.single().first)
    }

    @Test
    fun `the granted workspace is bound at the path the description promises`() = runBlocking {
        val guest = FakeGuest()
        val r = ShellTool(guest, context).run(input(), ctx(confirm = { true }, workspaceUri = workspace))
        assertTrue(r.ok)
        val (_, binds, workDir) = guest.calls.single()
        assertEquals(listOf("/storage/emulated/0/Vorv" to ShellTool.GUEST_WORKSPACE), binds)
        assertEquals(ShellTool.GUEST_WORKSPACE, workDir)
    }

    @Test
    fun `without a workspace the guest starts in root's home`() = runBlocking {
        val guest = FakeGuest()
        ShellTool(guest, context).run(input(), ctx(confirm = { true }))
        assertEquals("/root", guest.calls.single().third)
    }

    @Test
    fun `a missing Linux system is an honest error, not a fake success`() = runBlocking {
        val guest = FakeGuest(available = false)
        val r = ShellTool(guest, context).run(input(), ctx(confirm = { true }))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("no Linux system"))
        assertTrue(guest.calls.isEmpty())
    }

    @Test
    fun `a non-zero exit keeps the code and the stderr`() = runBlocking {
        val guest = FakeGuest(result = LinuxSystem.Shell(127, "", "sh: nope: not found", false))
        val r = ShellTool(guest, context).run(input("nope"), ctx(confirm = { true }))
        assertFalse(r.ok)
        assertEquals(127, r.exitCode)
        assertEquals("sh: nope: not found", r.stderr)
        assertTrue(r.error!!.contains("127"))
    }

    @Test
    fun `a timeout says what is known and what is not`() = runBlocking {
        val guest = FakeGuest(result = LinuxSystem.Shell(-1, "partial", "timed out", true))
        val r = ShellTool(guest, context).run(input("sleep 999"), ctx(confirm = { true }))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("UNKNOWN"))
        // A timed-out command must not count as a verified round-trip.
        assertFalse(SessionGrants.shellVerified)
    }

    @Test
    fun `the session grant replaces the dialog and is audited`() = runBlocking {
        val guest = FakeGuest()
        var asked = 0
        val audit = mutableListOf<String>()
        SessionGrants.auditSink = { kind, summary, _ -> audit += "$kind $summary" }
        SessionGrants.grantShellForSession()
        val tool = ShellTool(guest, context)
        val confirm = suspend { _: String -> asked++; true }
        tool.run(input("id"), ctx(confirm = confirm))
        tool.run(input("uname"), ctx(confirm = confirm))
        assertEquals("the session grant must skip the dialog", 0, asked)
        assertEquals(2, guest.calls.size)
        assertEquals(2, audit.size)
    }

    @Test
    fun `a wedged guest is not retried`() = runBlocking {
        val guest = FakeGuest()
        SessionGrants.blockShell("no answer")
        val r = ShellTool(guest, context).run(input(), ctx(confirm = { true }))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("disabled for the rest of this session"))
        assertTrue(guest.calls.isEmpty())
    }

    @Test
    fun `its description tells the model a package manager exists`() {
        val d = ShellTool(FakeGuest(), context).description
        // R-1 (review 2026-11-14): the guest is Debian 12, not Alpine.
        assertTrue(d.contains("apt-get install -y"))
        assertFalse("the description offers apk on a Debian system", d.contains("apk add"))
        assertTrue(d.contains(ShellTool.GUEST_WORKSPACE))
        assertFalse(d.contains("_"))
    }
}
