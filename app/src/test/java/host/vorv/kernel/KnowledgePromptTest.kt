package host.vorv.kernel

import host.vorv.knowledge.KnowledgeGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.0 — pins the PROMPT contract of the knowledge web: the section is
 * labeled associative (never presented as linear history), nodes carry
 * citable ids, budgets are honest per engine, the single-operator rule and
 * cite-or-refuse are pinned in every non-empty section.
 */
class KnowledgePromptTest {

    private fun activation(
        nodes: List<KnowledgeGraph.Node>,
        edges: List<KnowledgeGraph.Edge> = emptyList(),
    ) = KnowledgeGraph.Activation(nodes, edges, nodes.associate { it.id to 1f })

    @Test
    fun `empty activation produces no section`() {
        assertEquals("", KnowledgePrompt.section(KnowledgeGraph.Activation(emptyList(), emptyList(), emptyMap()), null, false))
    }

    @Test
    fun `section is labeled associative and never claims to be history`() {
        val act = activation(listOf(KnowledgeGraph.Node("aaaaaaaa", "fact", "porch", "Porch light at 7:15pm", 0.9f, 2)))
        val s = KnowledgePrompt.section(act, "Ashley", localEngine = true)
        assertTrue(s.contains("[Knowledge web"))
        assertTrue(s.contains("associative memory"))
        assertTrue(s.contains("NOT a linear history"))
    }

    @Test
    fun `operator identity and honesty rules are pinned`() {
        val act = activation(listOf(KnowledgeGraph.Node("aaaaaaaa", "fact", "porch", "Porch light at 7:15pm", 0.9f, 2)))
        val withName = KnowledgePrompt.section(act, "Ashley", true)
        assertTrue(withName.contains("Single operator: Ashley"))
        assertTrue(withName.contains("cite evidence or say you don't know"))

        val noName = KnowledgePrompt.section(act, null, true)
        assertTrue(noName.contains("name is not yet learned"))
    }

    @Test
    fun `nodes render with citable short ids`() {
        val act = activation(
            listOf(
                KnowledgeGraph.Node("1234567890abcdef", "preference", "short-answers", "I prefer short answers", 0.8f, 1),
            )
        )
        val s = KnowledgePrompt.section(act, null, true)
        assertTrue("short id must appear for citation", s.contains("12345678"))
        assertTrue(s.contains("preference"))
        assertTrue(s.contains("I prefer short answers"))
    }

    @Test
    fun `edges render as typed weighted associations`() {
        val act = activation(
            listOf(
                KnowledgeGraph.Node("aaaaaaaa", "fact", "porch", "Porch light", 0.9f, 0),
                KnowledgeGraph.Node("bbbbbbbb", "fact", "routine", "Evening routine", 0.7f, 0),
            ),
            listOf(KnowledgeGraph.Edge("aaaaaaaa", "bbbbbbbb", "part-of", 0.75f)),
        )
        val s = KnowledgePrompt.section(act, null, true)
        assertTrue(s.contains("aaaaaaaa --part-of(0.75)--> bbbbbbbb"))
    }

    @Test
    fun `local engine budget cuts harder than cloud`() {
        val many = (1..20).map {
            KnowledgeGraph.Node("n$it", "fact", "topic number $it", "content number $it is fairly long here", 0.6f, 0)
        }
        val act = activation(many)
        val local = KnowledgePrompt.section(act, null, true)
        val cloud = KnowledgePrompt.section(act, null, false)
        val count = { s: String -> Regex("(?m)^- ").findAll(s).count() }
        assertTrue("local node budget", count(local) <= 6)
        assertTrue("cloud node budget", count(cloud) <= 12)
        assertTrue(count(cloud) > count(local))
        assertTrue("omission must be announced", local.contains("omitted for budget"))
        assertTrue("cloud must also announce its omissions honestly", cloud.contains("omitted for budget"))
        // Local must omit at least as much as cloud: the tighter budget
        // never buys MORE nodes.
        assertTrue(
            "local must show no more nodes than cloud",
            count(local) <= count(cloud),
        )
    }

    @Test
    fun `long node content is honestly truncated`() {
        val act = activation(
            listOf(KnowledgeGraph.Node("aaaaaaaa", "fact", "long", "x".repeat(500), 0.5f, 0))
        )
        val s = KnowledgePrompt.section(act, null, true)
        assertTrue(s.contains("…(truncated)"))
    }
}
