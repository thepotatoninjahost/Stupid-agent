package host.vorv.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComputerTest {

    private fun tmpHome(): ComputerHome =
        ComputerHome(File(System.getProperty("java.io.tmpdir"), "computer-test-${System.nanoTime()}"))

    private class FakeEngine(
        override val id: String,
        private val ready: Boolean = true,
        private val body: (String, Long) -> EngineRun = { _, _ -> EngineRun(listOf("ok"), false, null) },
    ) : Engine {
        override fun available() = ready
        override fun run(code: String, timeoutMs: Long) = body(code, timeoutMs)
    }

    @Test
    fun `runs on a ready engine and records an audit line`() {
        val home = tmpHome()
        val computer = Computer(home, listOf(FakeEngine("js") { _, _ -> EngineRun(listOf("hi"), false, null) }))

        val outcome = computer.run("js", "console.log('hi')", 1_000)

        assertTrue(outcome is Computer.Outcome.Ok)
        assertEquals(listOf("hi"), (outcome as Computer.Outcome.Ok).output)
        val audit = home.auditFile.readText()
        assertTrue(audit.contains("engine=js"))
        assertTrue(audit.contains("verdict=ok"))
    }

    @Test
    fun `refuses an unknown engine without running anything`() {
        val computer = Computer(tmpHome(), listOf(FakeEngine("js")))
        val outcome = computer.run("ruby", "puts 1", 1_000)
        assertTrue(outcome is Computer.Outcome.Refused)
        assertTrue((outcome as Computer.Outcome.Refused).reason.contains("unknown engine"))
    }

    @Test
    fun `refuses an unavailable engine with a reason the user can read`() {
        val computer = Computer(tmpHome(), listOf(FakeEngine("python", ready = false)))
        val outcome = computer.run("python", "print(1)", 1_000)
        assertTrue(outcome is Computer.Outcome.Refused)
        assertTrue((outcome as Computer.Outcome.Refused).reason.contains("not available"))
    }

    @Test
    fun `empty program is refused rather than executed`() {
        val computer = Computer(tmpHome(), listOf(FakeEngine("js")))
        assertTrue(computer.run("js", "   \n", 1_000) is Computer.Outcome.Refused)
    }

    @Test
    fun `workspace quota stops work before it starts`() {
        val home = tmpHome()
        tempFile(home, bytes = 1_000)
        val computer = Computer(home, listOf(FakeEngine("js")), quotaBytes = 1_500)
        val outcome = computer.run("js", "1", 1_000, estimatedBytes = 1_000)
        assertTrue(outcome is Computer.Outcome.Refused)
        assertTrue((outcome as Computer.Outcome.Refused).reason.startsWith("workspace full"))
        // Fits without the estimate, so the refusal is the quota, not the tree.
        assertTrue(computer.run("js", "1", 1_000, estimatedBytes = 100) is Computer.Outcome.Ok)
    }

    @Test
    fun `timeout is reported as a timeout, not a failure`() {
        val computer = Computer(
            tmpHome(),
            listOf(FakeEngine("js") { _, _ -> EngineRun(listOf("partial"), true, null) }),
        )
        val outcome = computer.run("js", "while(true){}", 500)
        assertTrue(outcome is Computer.Outcome.TimedOut)
        assertEquals(listOf("partial"), (outcome as Computer.Outcome.TimedOut).output)
    }

    @Test
    fun `an engine crash is captured and does not propagate`() {
        val computer = Computer(tmpHome(), listOf(FakeEngine("js") { _, _ -> throw IllegalStateException("boom") }))
        val outcome = computer.run("js", "1", 1_000)
        assertTrue(outcome is Computer.Outcome.Failed)
        assertTrue((outcome as Computer.Outcome.Failed).detail.contains("boom"))
    }

    @Test
    fun `scratch directories are per task and cannot escape the workspace`() {
        val home = tmpHome()
        home.provision()
        val a = home.scratchFor("../../etc/passwd")
        assertEquals(home.scratchDir, a.parentFile)
        assertEquals("etcpasswd", a.name)
    }

    @Test
    fun `provision is idempotent and reports nothing missing`() {
        val home = tmpHome()
        assertTrue(home.provision().isEmpty())
        assertTrue(home.provision().isEmpty())
        assertTrue(home.homeDir.isDirectory)
        assertTrue(home.projectsDir.isDirectory)
        assertNull(home.root.takeIf { !it.isDirectory })
    }

    @Test
    fun `used bytes counts files under the root only`() {
        val home = tmpHome()
        tempFile(home, bytes = 2_048)
        assertEquals(2_048L, home.usedBytes())
        assertNotNull(home.auditFile.parentFile)
    }

    private fun tempFile(home: ComputerHome, bytes: Int) {
        home.provision()
        File(home.homeDir, "payload.bin").writeBytes(ByteArray(bytes))
    }
}
