package host.vorv.kernel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-4 regression tests (code review, 2026-11): the inline-citation
 * evidence check. The old raw `contains(ref)` substring match let
 * hallucinated refs pass on accidental partial overlaps — these tests pin
 * every attacked edge.
 *
 * Plain JUnit (no Robolectric): [evidenceContainsRef] is pure string logic.
 */
class EvidenceContainsRefTest {

    // ------------------------------------------------- the legitimate uses

    @Test
    fun `a filename ref matches its appearance in a file citation path`() {
        // file_read's captured ref is the full SAF URI; the model cites the
        // readable name — the evidence fallback exists exactly for this.
        val evidence = "read OK content://com.android.externalstorage.documents/…/report.txt (1200 chars)"
        assertTrue(evidenceContainsRef(evidence, "report.txt"))
    }

    @Test
    fun `a url ref matches its appearance in web evidence`() {
        val evidence = "fetched https://example.org/posts/kotlin-tips — 8 214 chars extracted"
        assertTrue(evidenceContainsRef(evidence, "https://example.org/posts/kotlin-tips"))
        assertTrue(evidenceContainsRef(evidence, "example.org"))
    }

    @Test
    fun `a memory id ref matches its appearance in memory evidence`() {
        val evidence = "stored as memory #f81d4fae-7dec (memory_write)"
        assertTrue(evidenceContainsRef(evidence, "f81d4fae"))
    }

    @Test
    fun `underscore refs take the boundary path and match`() {
        assertTrue(evidenceContainsRef("crash_logs: 3 reports", "crash_logs"))
    }

    @Test
    fun `colon-suffixed refs fall back to containment still length-gated`() {
        // "primary:" ends with ':' (non-word edge) → containment branch.
        assertTrue(evidenceContainsRef("docId primary: is the root grant", "primary:"))
        assertFalse(evidenceContainsRef("docId primary is the root grant", "primary:"))
    }

    @Test
    fun `cjk-edged refs match by containment - boundary branch never fires falsely`() {
        // A workspace folder name with Chinese edges inside a file URI: the
        // boundary branch is skipped (Java \b is ASCII-only), containment
        // still works — no false negative, no upgrade-turned-downgrade.
        val evidence = "read OK content://…/树莓派工坊/report.txt"
        assertTrue(evidenceContainsRef(evidence, "树莓派工坊"))
    }

    // --------------------------------------------------- the attacked edges

    @Test
    fun `hallucinated domain inside an unrelated longer domain is rejected`() {
        // "example.com" is literally a substring of "notexample.community"
        // — the pre-fix contains() passed this.
        val evidence = "fetched https://notexample.community/ — the real page"
        assertFalse(evidenceContainsRef(evidence, "example.com"))
    }

    @Test
    fun `short trivial refs never pass the evidence fallback`() {
        val evidence = "the answer is 54231 and the id is e5"
        assertFalse(evidenceContainsRef(evidence, "42"))
        assertFalse(evidenceContainsRef(evidence, "54231".substring(1, 3))) // "42"
        assertFalse(evidenceContainsRef(evidence, "e5"))
        assertFalse(evidenceContainsRef(evidence, ""))
    }

    @Test
    fun `a ref embedded inside a longer token is rejected`() {
        val evidence = "memory id 11f81d4fae stored"
        // "f81d4fae" IS a substring, but embedded mid-token → no boundary.
        assertFalse(evidenceContainsRef(evidence, "f81d4fae"))
        // The whole token still matches itself.
        assertTrue(evidenceContainsRef(evidence, "11f81d4fae"))
    }

    @Test
    fun `regex metacharacters in the ref are inert`() {
        // A ref full of regex syntax must be treated literally, not as a
        // pattern that could match almost anything.
        val evidence = "nothing to see here"
        assertFalse(evidenceContainsRef(evidence, ".*.*"))
        val evidence2 = "ran grep -c 'a\\.b' file.txt"
        assertTrue(evidenceContainsRef(evidence2, "a\\.b"))
    }

    @Test
    fun `absent ref is rejected`() {
        assertFalse(evidenceContainsRef("completely unrelated output", "report.txt"))
    }
}
