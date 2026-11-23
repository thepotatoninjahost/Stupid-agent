package host.vorv.llm.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key field is a paste target. These pin the three shapes that occur in
 * practice, so a stored key is either one key or a refusal in words — never a
 * header OkHttp rejects with "Unexpected char 0x0a".
 */
class ApiKeyTest {

    @Test
    fun `a trailing newline from a paste is removed, not sent`() {
        assertEquals("sk-or-v1-abc", ApiKey.normalize("  sk-or-v1-abc\r\n"))
    }

    @Test
    fun `a single key with no whitespace passes unchanged`() {
        assertEquals("sk-or-v1-abc", ApiKey.normalize("sk-or-v1-abc"))
    }

    @Test
    fun `four keys on four lines are refused and the count is named`() {
        val e = runCatching { ApiKey.normalize("sk-a\nsk-b\nsk-c\nsk-d") }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("4 values"))
        assertTrue(e.message!!.contains("Paste exactly one key"))
    }

    @Test
    fun `a line break inside one value is refused`() {
        val e = runCatching { ApiKey.normalize("sk-a\nsk-b") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("2 values"))
    }

    @Test
    fun `an inner space is refused as a broken key`() {
        val e = runCatching { ApiKey.normalize("sk-a b") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("space or line break"))
    }

    @Test
    fun `blank stays blank so the caller can say no key yet`() {
        assertEquals("", ApiKey.normalize("   \n "))
    }
}
