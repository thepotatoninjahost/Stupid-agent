package host.vorv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L-12 (code review, 2026-11-20): the old check-before-append cap let any
 * SINGLE line through whole as long as the buffer was still under the cap, so
 * `cat minified.js` (or any binary blob) produced output far larger than the
 * cap, and the remainder was silently dropped with no marker at all.
 */
class CappedOutputTest {

    @Test
    fun `short lines are kept whole and in order`() {
        val out = CappedOutput(100)
        out.appendLine("first")
        out.appendLine("second")
        assertEquals("first\nsecond\n", out.text())
    }

    @Test
    fun `one huge line cannot exceed the cap`() {
        val out = CappedOutput(40)
        out.appendLine("x".repeat(10_000))
        val text = out.text()
        assertTrue("cap exceeded: ${text.length}", text.length < 120)
        assertTrue("the cut must be announced", text.contains("output capped at 40 chars"))
        assertTrue("kept text must be the head of the line", text.startsWith("x".repeat(40)))
    }

    @Test
    fun `a line that exactly fills the buffer is not marked cut`() {
        val out = CappedOutput(6)
        out.appendLine("abcde") // 5 + newline = 6
        assertEquals("abcde\n", out.text())
    }

    @Test
    fun `lines after the cap do not grow the buffer`() {
        val out = CappedOutput(6)
        out.appendLine("abcde")
        out.appendLine("more")
        out.appendLine("more")
        val text = out.text()
        assertTrue(text.startsWith("abcde\n"))
        assertFalse("later lines must not be appended", text.contains("more"))
        assertTrue(text.contains("output capped"))
    }
}
