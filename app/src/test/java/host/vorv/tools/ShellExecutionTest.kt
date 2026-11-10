package host.vorv.tools

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.concurrent.TimeUnit

class ShellExecutionTest {
    private fun execute(command: String, budget: Int = 1000, cancel: Boolean = false,
                        expired: Boolean = false, check: (File) -> Unit) {
        val dir = Files.createTempDirectory("shell ' capture").toFile()
        val base = File(dir, "run")
        try {
            if (cancel) File("$base.cancel").writeText("")
            val wrapper = ShellExecution.wrapper(command, base.path, budget,
                System.currentTimeMillis() + if (expired) -1000 else budget + 1000L)
            val process = ProcessBuilder("bash", "-c", wrapper).start()
            assertTrue("supervisor must finish", process.waitFor(8, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            check(base)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `returns real output and exit status with quoted paths`() = execute("printf 'hello'; printf 'error' >&2; exit 7") {
        assertEquals("hello", File("$it.out").readText())
        assertEquals("error", File("$it.err").readText())
        assertEquals("7", File("$it.code").readText())
    }

    @Test fun `deadline stops CPU loop even with closed streams`() = execute("exec 1>&- 2>&-; while :; do :; done") {
        assertEquals("124", File("$it.code").readText())
    }

    @Test fun `output production is bounded`() = execute("while :; do printf '%08000d' 0; done") {
        assertEquals("125", File("$it.code").readText())
        assertEquals(400000L, File("$it.out").length())
    }

    @Test fun `cancellation before dispatch prevents execution`() = execute("echo unsafe", cancel = true) {
        assertEquals("124", File("$it.code").readText())
        assertEquals(0L, File("$it.out").length())
    }

    @Test fun `expired dispatch prevents execution`() = execute("echo unsafe", expired = true) {
        assertEquals("124", File("$it.code").readText())
        assertEquals(0L, File("$it.out").length())
    }

    @Test fun `deadline reaps descendants that detach into another session`() {
        val dir = Files.createTempDirectory("shell-detached").toFile()
        val base = File(dir, "run")
        val ticks = File(dir, "ticks")
        try {
            val child = "exec 1>&- 2>&-; while :; do echo tick >> ${ShellQuote.sh(ticks.path)}; sleep 0.02; done"
            val command = "setsid bash -c ${ShellQuote.sh(child)} & wait"
            val process = ProcessBuilder("bash", "-c", ShellExecution.wrapper(command, base.path, 1000,
                System.currentTimeMillis() + 1000)).start()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals("124", File("$base.code").readText())
            assertTrue(ticks.length() > 0)
            val length = ticks.length()
            Thread.sleep(150)
            assertEquals(length, ticks.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `cancellation stops background descendants before completion`() {
        val dir = Files.createTempDirectory("shell-cancel").toFile()
        val base = File(dir, "run")
        try {
            val command = "(while :; do echo tick; sleep 0.02; done) & wait"
            val process = ProcessBuilder("bash", "-c", ShellExecution.wrapper(command, base.path, 5000,
                System.currentTimeMillis() + 5000)).start()
            Thread.sleep(200)
            File("$base.cancel").writeText("")
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals("124", File("$base.code").readText())
            val length = File("$base.out").length()
            Thread.sleep(150)
            assertEquals(length, File("$base.out").length())
        } finally {
            dir.deleteRecursively()
        }
    }
}
