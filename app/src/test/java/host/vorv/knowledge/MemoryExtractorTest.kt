package host.vorv.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.0 — pins the LEARNING contracts (owner spec, 2026-11-09):
 * what the extractor mines from the operator's own words, and that it
 * never invents content beyond them. The learner's dedup/strengthen and
 * edge-wiring behavior is pinned in MemoryLearnerStoreTest (Robolectric).
 */
class MemoryExtractorTest {

    @Test
    fun `operator identity is mined from the operator's own words`() {
        val out = MemoryExtractor.fromUserMessage("My name is Ashley")
        assertEquals(1, out.size)
        assertEquals("operator", out.first().kind)
        assertEquals("The operator's name is Ashley.", out.first().content)
    }

    @Test
    fun `call me also captures the operator`() {
        val out = MemoryExtractor.fromUserMessage("Hey, call me Ash from now on")
        assertEquals("operator", out.first().kind)
        assertTrue(out.first().content.contains("Ash"))
    }

    @Test
    fun `preferences are mined verbatim`() {
        val out = MemoryExtractor.fromUserMessage("I prefer short answers with no filler")
        assertEquals(1, out.size)
        assertEquals("preference", out.first().kind)
        assertEquals("I prefer short answers with no filler", out.first().content)
    }

    @Test
    fun `taught facts are mined from remember that`() {
        val out = MemoryExtractor.fromUserMessage("Remember that the porch light timer is set to 7:15pm")
        val fact = out.first { it.kind == "fact" }
        assertTrue(fact.content.startsWith("Operator taught:"))
        assertTrue(fact.content.contains("porch light timer"))
    }

    @Test
    fun `nothing is mined from a bare question`() {
        assertTrue(MemoryExtractor.fromUserMessage("What is the latest version of React?").isEmpty())
    }

    @Test
    fun `too-short messages are ignored`() {
        assertTrue(MemoryExtractor.fromUserMessage("hi").isEmpty())
    }

    @Test
    fun `task outcomes become lessons with the real tool roster`() {
        val c = MemoryExtractor.fromOutcome(
            "Read every file in my workspace and count lines per language",
            ok = true,
            toolNames = listOf("file_list", "file_read", "file_write"),
            iterations = 5,
        )!!
        assertEquals("lesson", c.kind)
        assertTrue(c.content.contains("completed after 5 iteration(s)"))
        assertTrue(c.content.contains("file_list"))
        assertTrue(c.content.contains("workspace"))
    }

    @Test
    fun `failed tasks are remembered harder than successes`() {
        val ok = MemoryExtractor.fromOutcome("Run the build and notify me", true, listOf("shell"), 3)!!
        val fail = MemoryExtractor.fromOutcome("Run the build and notify me", false, listOf("shell"), 3)!!
        assertTrue(fail.salience > ok.salience)
    }

    @Test
    fun `null outcome for junk prompts`() {
        assertNull(MemoryExtractor.fromOutcome("hi", true, emptyList(), 1))
    }

    @Test
    fun `topic labels come from the content's own words`() {
        assertEquals("porch-light-timer-set", MemoryExtractor.topicFrom("The porch light timer is set to 7:15pm"))
    }
}
