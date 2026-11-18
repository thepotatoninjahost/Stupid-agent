package host.vorv.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AttemptTest {

    private val lines = mutableListOf<String>()
    private val original = Attempt.logger

    @Before
    fun capture() {
        lines.clear()
        Attempt.logger = { lines += it }
    }

    @After
    fun restore() {
        Attempt.logger = original
    }

    @Test
    fun `a success returns the value and says nothing`() {
        assertEquals("ok", Attempt.of("read a file") { "ok" })
        assertTrue("a success must not be logged as a failure", lines.isEmpty())
    }

    @Test
    fun `a failure returns null and names what failed and why`() {
        assertNull(Attempt.of("read a file") { error("no such file") })

        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().contains("read a file"))
        assertTrue(lines.single(), lines.single().contains("IllegalStateException"))
        assertTrue(lines.single(), lines.single().contains("no such file"))
    }

    @Test
    fun `orDefault gives the fallback and still logs`() {
        assertEquals(7, Attempt.orDefault("count the files", 7) { error("boom") })
        assertEquals(1, lines.size)
    }
}
