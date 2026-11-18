package host.vorv.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A script that logs in a loop must not be able to exhaust memory. */
class JsSandboxOutputCapTest {

    @Test
    fun `a runaway log stops at the cap and says so`() {
        val out = StringBuilder()
        val err = StringBuilder()
        val console = JsExecTool.SandboxConsole(out, err)

        repeat(5_000) { console.log("x".repeat(200)) }

        assertTrue("output kept growing without a bound", out.length < 70_000)
        assertTrue("the truncation was silent", out.contains("truncated"))
        assertEquals(0, err.length)
    }

    @Test
    fun `ordinary output is passed through unchanged`() {
        val out = StringBuilder()
        val err = StringBuilder()
        val console = JsExecTool.SandboxConsole(out, err)

        console.log("hello", 42)
        console.warn("careful")

        assertEquals("hello 42\n", out.toString())
        assertEquals("WARN: careful\n", err.toString())
    }
}
