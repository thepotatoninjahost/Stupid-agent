package host.vorv

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The trace file is appended on every launch and used to grow forever. On a
 * phone that has been launched a few hundred times, reading it whole during
 * startup (and again for every crash report) is an out-of-memory kill in the
 * exact place the app reports crashes — which then reads as "the previous boot
 * did not complete".
 */
@RunWith(RobolectricTestRunner::class)
class BootTracerTrimTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun section(n: Int, completed: Boolean = false): String = buildString {
        append("\n" + "=".repeat(60) + "\n")
        appendLine("[12:00:00.000] BOOT_STARTED — pid=$n install=abc epoch=$n")
        appendLine("[12:00:00.500] step_$n")
        if (completed) appendLine("[12:00:01.000] BOOT_COMPLETE — ui is up")
    }

    @Test
    fun `a huge trace is trimmed on the next boot and keeps the newest sections`() {
        val f = BootTracer.traceFile(ctx)
        f.parentFile?.mkdirs()
        repeat(1500) { f.appendText(section(it)) }
        assertTrue("test needs a trace bigger than the cap", f.length() > BootTracer.MAX_BYTES)

        BootTracer.start(ctx)

        assertTrue("trace must be bounded", f.length() <= BootTracer.MAX_BYTES)
        assertTrue("newest sessions survive", f.readText().contains("step_1499"))
        assertTrue("this boot is recorded", f.readText().contains("BOOT_STARTED"))
    }

    @Test
    fun `the previous section is still found in a huge trace`() {
        val f = BootTracer.traceFile(ctx)
        f.parentFile?.mkdirs()
        repeat(1500) { f.appendText(section(it)) }
        f.appendText(section(9999))

        val prev = BootTracer.readPreviousTrace(ctx)
        assertNotNull(prev)
        assertTrue(prev!!.contains("step_1499"))
    }

    @Test
    fun `tailOf never reads more than it is asked for`() {
        val f = java.io.File(ctx.cacheDir, "big.txt")
        f.writeText("A".repeat(50_000) + "END")
        val tail = BootTracer.tailOf(f, 1_000)
        assertEquals(1_000, tail.length)
        assertTrue(tail.endsWith("END"))
    }

    @Test
    fun `a small trace is left untouched by the trim`() {
        val f = BootTracer.traceFile(ctx)
        f.parentFile?.mkdirs()
        f.writeText(section(1))
        val before = f.readText()
        BootTracer.trimIfHuge(ctx)
        assertEquals(before, f.readText())
    }
}
