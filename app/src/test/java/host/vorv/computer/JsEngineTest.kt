package host.vorv.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsEngineTest {

    private val engine = JsEngine()

    private fun runJs(code: String, timeoutMs: Long = 2_000) = engine.run(code, timeoutMs)

    @Test
    fun `runs ordinary javascript and captures console output`() {
        val r = runJs("console.log('hello'); console.log(2 + 2);")
        assertEquals(listOf("hello", "4"), r.output)
        assertEquals(false, r.timedOut)
    }

    @Test
    fun `standard library works without a network or filesystem`() {
        val r = runJs("console.log(JSON.stringify({a: [1,2,3].map(x => x * 2)}));")
        assertEquals(listOf("""{"a":[2,4,6]}"""), r.output)
    }

    @Test
    fun `java classes are unreachable`() {
        val r = runJs(
            """
            var reached = 'no';
            try { reached = java.lang.System.getenv('PATH'); } catch (e) { reached = 'blocked'; }
            console.log(reached);
            """.trimIndent(),
        )
        assertTrue(r.output.toString(), r.output.first().startsWith("blocked"))
    }

    @Test
    fun `package namespaces are unreachable`() {
        val r = runJs("console.log(typeof Packages);")
        assertEquals(listOf("undefined"), r.output)
    }

    @Test
    fun `a runaway loop is stopped and reported as a timeout`() {
        val r = runJs("while (true) { }", timeoutMs = 500)
        assertTrue(r.timedOut)
    }

    @Test
    fun `output is capped so a noisy script cannot exhaust memory`() {
        val small = JsEngine(maxOutput = 64)
        val r = small.run("for (var i = 0; i < 100; i++) console.log('x'.repeat(50));", 2_000)
        assertTrue(r.output.size < 100)
        assertTrue(r.output.joinToString("\n").length <= 64 + 51)
    }

    @Test
    fun `a syntax error is reported, not swallowed`() {
        val r = runJs("console.log(")
        assertTrue(r.output.isNotEmpty())
        assertEquals(false, r.timedOut)
    }
}
