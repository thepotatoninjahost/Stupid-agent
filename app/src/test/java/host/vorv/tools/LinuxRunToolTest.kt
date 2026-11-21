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
 * linux_run — H-1: no guest command runs without the user's approval.
 *
 * `shell` always asked; `linux_run` did not, so the model could run anything
 * on the phone by picking the other tool. These tests pin the contract it now
 * shares: breaker, session grant (audited), per-command dialog, fail-closed
 * without a bridge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LinuxRunToolTest {

    private class FakeGuest(
        private val result: LinuxSystem.Shell = LinuxSystem.Shell(0, "ok\n", null, false),
    ) : GuestShell {
        val calls = mutableListOf<String>()
        override fun isAvailable() = true
        override suspend fun run(
            command: String,
            timeoutMs: Long,
            binds: List<Pair<String, String>>,
            workDir: String,
        ): LinuxSystem.Shell {
            calls += command
            return result
        }
    }

    @After
    fun clearSession() = SessionGrants.resetForTest()

    private fun input(command: String = "uname -a") =
        JsonObject().apply { addProperty("command", command) }

    private fun ctx(confirm: (suspend (String) -> Boolean)? = null) =
        ToolContext(taskId = "t1", iteration = 1, workspaceUri = null, confirm = confirm)

    @Test
    fun `a denied command never runs`() = runBlocking {
        val guest = FakeGuest()
        var asked: String? = null
        val result = LinuxRunTool(guest).run(
            input("rm -rf /root"),
            ctx(confirm = { message ->
                asked = message
                false
            }),
        )

        assertFalse(result.ok)
        assertTrue(guest.calls.isEmpty())
        assertEquals("User DENIED the linux_run command. Do not claim it ran.", result.error)
        val dialog = requireNotNull(asked)
        assertTrue("dialog must carry the full command", dialog.contains("rm -rf /root"))
        assertTrue(
            "AgentViewModel only offers the session grant on this prefix",
            dialog.startsWith(ShellTool.CONSENT_PREFIX),
        )
    }

    @Test
    fun `no confirmation bridge fails closed`() = runBlocking {
        val guest = FakeGuest()
        val result = LinuxRunTool(guest).run(input(), ctx(confirm = null))

        assertFalse(result.ok)
        assertTrue(guest.calls.isEmpty())
        assertTrue(result.error!!.contains("fail closed"))
    }

    @Test
    fun `the session grant runs and audits the command`() = runBlocking {
        val guest = FakeGuest()
        val audited = mutableListOf<String>()
        SessionGrants.auditSink = { _, summary, _ -> audited += summary }
        SessionGrants.grantShellForSession()

        val result = LinuxRunTool(guest).run(input("uname -a"), ctx(confirm = null))

        assertTrue(result.ok)
        assertEquals(listOf("uname -a"), guest.calls)
        assertEquals(1, SessionGrants.autoRunCount)
        assertEquals(listOf("uname -a"), audited)
    }

    @Test
    fun `the dead shell breaker stops the command before asking`() = runBlocking {
        val guest = FakeGuest()
        SessionGrants.blockShell("no root filesystem")
        var asked = false

        val result = LinuxRunTool(guest).run(input(), ctx(confirm = { asked = true; true }))

        assertFalse(result.ok)
        assertFalse("must not ask once the guest is wedged", asked)
        assertTrue(guest.calls.isEmpty())
        assertTrue(result.error!!.contains("disabled for the rest of this session"))
    }

    @Test
    fun `a completed command marks the shell verified`() = runBlocking {
        val guest = FakeGuest()
        val result = LinuxRunTool(guest).run(input(), ctx(confirm = { true }))

        assertTrue(result.ok)
        assertTrue(SessionGrants.shellVerified)
    }

    @Test
    fun `a timed out command does not mark the shell verified`() = runBlocking {
        val guest = FakeGuest(LinuxSystem.Shell(-1, "", null, true))
        val result = LinuxRunTool(guest).run(input(), ctx(confirm = { true }))

        assertFalse(result.ok)
        assertFalse(SessionGrants.shellVerified)
    }
}
