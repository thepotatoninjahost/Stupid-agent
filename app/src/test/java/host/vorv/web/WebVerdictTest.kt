package host.vorv.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVerdictTest {

    @Test
    fun `all checks passing and a clean console is a pass`() {
        val v = WebVerdict.fromEvaluation(
            """{"ok":true,"checks":[{"name":"title","pass":true}]}""", emptyList(),
        )
        assertTrue(v.ok)
        assertEquals(1, v.checks.size)
    }

    @Test
    fun `an escaped string answer is unwrapped`() {
        val v = WebVerdict.fromEvaluation(
            "\"{\\\"ok\\\":true,\\\"checks\\\":[{\\\"name\\\":\\\"a\\\",\\\"pass\\\":true}]}\"", emptyList(),
        )
        assertTrue(v.ok)
    }

    @Test
    fun `a failing check fails the run even when the page says ok`() {
        val v = WebVerdict.fromEvaluation(
            """{"ok":true,"checks":[{"name":"add","pass":false,"detail":"got 3, want 4"}]}""", emptyList(),
        )
        assertFalse(v.ok)
        assertTrue(v.reason!!.contains("1 of 1"))
    }

    @Test
    fun `a console error fails an otherwise clean run`() {
        val v = WebVerdict.fromEvaluation(
            """{"ok":true,"checks":[{"name":"x","pass":true}]}""",
            listOf("Uncaught TypeError: x is undefined (app.js:7)"),
        )
        assertFalse(v.ok)
        assertTrue(v.reason!!.contains("JavaScript error"))
    }

    @Test
    fun `no checks at all is a failure, not a pass`() {
        val v = WebVerdict.fromEvaluation("""{"ok":true,"checks":[]}""", emptyList())
        assertFalse(v.ok)
        assertTrue(v.reason!!.contains("no checks ran"))
    }

    @Test
    fun `null or empty evaluation fails with a stated reason`() {
        listOf(null, "", "null", "\"\"").forEach { raw ->
            val v = WebVerdict.fromEvaluation(raw, emptyList())
            assertFalse("raw=$raw must fail", v.ok)
            assertTrue(v.reason!!.contains("nothing was verified"))
        }
    }

    @Test
    fun `a thrown script is reported as the reason`() {
        val v = WebVerdict.fromEvaluation("""{"error":"TypeError: boom"}""", emptyList())
        assertFalse(v.ok)
        assertTrue(v.reason!!.contains("TypeError: boom"))
    }

    @Test
    fun `garbage output fails instead of being optimistically accepted`() {
        val v = WebVerdict.fromEvaluation("<html>404</html>", emptyList())
        assertFalse(v.ok)
        assertTrue(v.reason!!.contains("not JSON"))
    }

    @Test
    fun `the report lists every failing check by name`() {
        val v = WebVerdict.fromEvaluation(
            """{"checks":[{"name":"save","pass":false},{"name":"load","pass":true}]}""", emptyList(),
        )
        val report = v.report()
        assertTrue(report.startsWith("FAIL"))
        assertTrue(report.contains("FAIL save"))
        assertTrue(report.contains("ok   load"))
    }
}
