package host.vorv.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationStripperTest {

    @Test fun `strips single citation tag and numbers it`() {
        val r = CitationStripper.strip("Paris is in France [cite:memory:abc123].")
        assertEquals("Paris is in France [1].", r.text)
        assertEquals(1, r.citations.size)
        assertEquals("memory", r.citations[0].type)
        assertEquals("abc123", r.citations[0].ref)
    }

    @Test fun `multiple tags numbered in order`() {
        val r = CitationStripper.strip("A [cite:web:https://x] then B [cite:file:content://y].")
        assertEquals("A [1] then B [2].", r.text)
        assertEquals(2, r.citations.size)
        assertEquals("web", r.citations[0].type)
        assertEquals("file", r.citations[1].type)
    }

    @Test fun `no tags returns text unchanged and empty list`() {
        val r = CitationStripper.strip("Plain factual claim.")
        assertEquals("Plain factual claim.", r.text)
        assertTrue(r.citations.isEmpty())
    }

    @Test fun `urls with colons inside ref are captured`() {
        val r = CitationStripper.strip("See [cite:web:https://example.com/a?b=c].")
        assertEquals("See [1].", r.text)
        assertEquals("https://example.com/a?b=c", r.citations[0].ref)
    }

    @Test fun `type must be word chars`() {
        val r = CitationStripper.strip("Bad [cite:bad type:ref] stays.")
        assertEquals("Bad [cite:bad type:ref] stays.", r.text)
        assertTrue(r.citations.isEmpty())
    }

    @Test fun `uncited detection logic works on empty lists`() {
        val r = CitationStripper.strip("Some claim.")
        assertFalse(r.citations.isNotEmpty())
    }
}
