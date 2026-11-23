package host.vorv.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * One pipe, one ceiling. A command that floods stdout used to grow a
 * StringBuilder without limit; the drain now stops at [LinuxSystem.MAX_OUTPUT]
 * and says so — on stdout and on stderr independently.
 */
class GuestStreamsTest {

    private fun stream(chars: Int, fill: CharSequence = "x") = ByteArrayInputStream(
        fill.toString().repeat((chars / fill.length) + 1).toByteArray().copyOf(chars),
    )

    @Test
    fun `a short pipe arrives whole and untouched`() {
        val out = StringBuilder()
        LinuxSystem.drainCapped(stream(1_000), out)
        assertEquals(1_000, out.length)
        assertTrue(out.none { it != 'x' })
    }

    @Test
    fun `a flood stops at the ceiling and carries the marker once`() {
        val out = StringBuilder()
        LinuxSystem.drainCapped(stream(LinuxSystem.MAX_OUTPUT * 2), out)
        assertTrue("length ${out.length}", out.length in LinuxSystem.MAX_OUTPUT..(LinuxSystem.MAX_OUTPUT + 200))
        assertEquals(
            "the marker is appended exactly once",
            1,
            Regex(Regex.escape(LinuxSystem.TRUNCATED)).findAll(out).count(),
        )
    }
}
