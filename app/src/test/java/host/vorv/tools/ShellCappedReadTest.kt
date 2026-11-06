package host.vorv.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * H2 (code review, 2026-11): the shell tool's output reader must cap the
 * read AT the stream level. The pre-fix code did readText() first — a
 * runaway command writing hundreds of MB to stdout OOM-killed the app
 * before .take(MAX_OUTPUT_CHARS) ever ran.
 */
class ShellCappedReadTest {

    private fun streamOf(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `small stream is read fully and not flagged truncated`() {
        val r = ShellTool.readCapped(streamOf("hello world"), 100)
        assertEquals("hello world", r.text)
        assertFalse(r.truncated)
    }

    @Test
    fun `oversized stream is capped at exactly maxChars`() {
        val big = "x".repeat(50_000)
        val r = ShellTool.readCapped(streamOf(big), 10_000)
        assertEquals(10_000, r.text.length)
        assertTrue("more content remained — must be flagged", r.truncated)
    }

    @Test
    fun `stream exactly at the cap is not truncated`() {
        val exact = "y".repeat(1_000)
        val r = ShellTool.readCapped(streamOf(exact), 1_000)
        assertEquals(1_000, r.text.length)
        assertFalse("exactly-at-cap is complete, not truncated", r.truncated)
    }

    @Test
    fun `empty stream reads empty`() {
        val r = ShellTool.readCapped(streamOf(""), 100)
        assertEquals("", r.text)
        assertFalse(r.truncated)
    }

    @Test
    fun `multi-byte utf8 content survives the cap`() {
        // 3-byte UTF-8 chars: the cap counts CHARS, not bytes.
        val content = "π".repeat(500)
        val r = ShellTool.readCapped(streamOf(content), 200)
        assertEquals(200, r.text.length)
        assertTrue(r.text.all { it == 'π' })
        assertTrue(r.truncated)
    }

    @Test
    fun `a multi-megabyte stream cannot inflate memory beyond the cap`() {
        // The regression essence: 8 MB of output, cap 10k chars. The old
        // readText() materialised all 8 MB; readCapped must not.
        val big = java.util.Random(42).let { rnd ->
            val chars = CharArray(8 * 1024 * 1024)
            repeat(chars.size) { chars[it] = ('a' + rnd.nextInt(26)) }
            String(chars)
        }
        val r = ShellTool.readCapped(streamOf(big), 10_000)
        assertEquals(10_000, r.text.length)
        assertTrue(r.truncated)
    }
}
