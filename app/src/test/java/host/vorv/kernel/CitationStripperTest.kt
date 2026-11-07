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

    // ---- night session 2026-11-08: the citation gate (stripValidated) ----

    @Test fun `stripValidated keeps backed tags and numbers them`() {
        val r = CitationStripper.stripValidated("A [cite:web:u1] B.") { type, ref -> type == "web" && ref == "u1" }
        assertEquals("A [1] B.", r.text)
        assertEquals(listOf("u1"), r.citations.map { it.ref })
        assertTrue(r.forged.isEmpty())
    }

    @Test fun `stripValidated swaps rejected tags for the uncited marker`() {
        val r = CitationStripper.stripValidated("A [cite:memory:42] and B [cite:memory:99].") { _, ref -> ref == "42" }
        assertEquals("A [1] and B [uncited].", r.text)
        assertEquals(listOf("42"), r.citations.map { it.ref })
        assertEquals(listOf("99"), r.forged.map { it.ref })
    }

    @Test fun `stripValidated numbers only the kept citations`() {
        val r = CitationStripper.stripValidated("X [cite:file:a] Y [cite:file:b] Z [cite:file:c].") { _, ref -> ref != "b" }
        assertEquals("X [1] Y [uncited] Z [2].", r.text)
        assertEquals(listOf("a", "c"), r.citations.map { it.ref })
        assertEquals(listOf("b"), r.forged.map { it.ref })
    }

    @Test fun `stripValidated rejects everything when nothing is backed`() {
        val r = CitationStripper.stripValidated("N [cite:tel:555].") { _, _ -> false }
        assertEquals("N [uncited].", r.text)
        assertTrue(r.citations.isEmpty())
        assertEquals(1, r.forged.size)
        assertEquals("tel", r.forged[0].type)
    }
}
