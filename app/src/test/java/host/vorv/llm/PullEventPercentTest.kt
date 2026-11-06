package host.vorv.llm

import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.FileProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * M7 (code review, 2026-11): the pull progress flow must never emit a
 * bogus 0%. The old mapping returned 0 for unknown events AND for Progress
 * events whose total size was not yet known — so the download bar flickered
 * back to 0% mid-download. Now those events map to null and are not
 * emitted at all.
 *
 * Uses the REAL GenieX SDK event types (data classes, plain JVM) — no
 * fakes.
 */
class PullEventPercentTest {

    private fun progress(vararg files: FileProgress) =
        ModelManagerWrapper.PullEvent.Progress(files.toList())

    @Test
    fun `progress with a known total maps to the honest percent`() {
        val e = progress(FileProgress("model.gguf", 500L, 1000L))
        assertEquals(50, pullEventPercent(e))
    }

    @Test
    fun `zero total maps to null - no emission, no flicker`() {
        val e = progress(FileProgress("model.gguf", 0L, 0L))
        assertNull(pullEventPercent(e))
    }

    @Test
    fun `unknown-but-nonzero done with zero total still maps to null`() {
        // A provider that reports downloaded bytes before sizes: 0% is a
        // LIE here, and it used to be what the bar showed.
        val e = progress(FileProgress("model.gguf", 12345L, 0L))
        assertNull(pullEventPercent(e))
    }

    @Test
    fun `multi-file progress aggregates across files`() {
        val e = progress(
            FileProgress("a.gguf", 250L, 500L),
            FileProgress("b.gguf", 250L, 500L),
        )
        assertEquals(50, pullEventPercent(e))
    }

    @Test
    fun `completed maps to 100`() {
        assertEquals(100, pullEventPercent(ModelManagerWrapper.PullEvent.Completed()))
    }

    @Test
    fun `error events throw the honest failure`() {
        try {
            pullEventPercent(ModelManagerWrapper.PullEvent.Error(-100009, "AUTH"))
            fail("Error must throw, not emit")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("-100009"))
            assertTrue(e.message!!.contains("AUTH"))
        }
    }

    @Test
    fun `percent never exceeds 100`() {
        val e = progress(FileProgress("model.gguf", 2000L, 1000L))
        assertEquals(100, pullEventPercent(e))
    }
}
