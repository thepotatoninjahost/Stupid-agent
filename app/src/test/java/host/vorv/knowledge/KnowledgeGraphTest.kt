package host.vorv.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.0 — pins the associative-retrieval CONTRACT of the knowledge web
 * (owner spec, 2026-11-09). These are the tests that keep the brain
 * non-linear: if someone "simplifies" activation back to keyword lookup,
 * the second-hop test fails; if decay stops forgetting, the decay test
 * fails; if edge cutting drops working edges, the subgraph test fails.
 */
class KnowledgeGraphTest {

    private fun node(id: String, topic: String, content: String, salience: Float = 0.5f) =
        KnowledgeGraph.Node(id, "fact", topic, content, salience, 0)

    @Test
    fun `tokenize drops stopwords and fragments`() {
        val tokens = KnowledgeGraph.tokenize("The porch light timer is set to 7:15pm, okay?")
        assertEquals(listOf("porch", "light", "timer", "set", "15pm"), tokens)
    }

    @Test
    fun `seeding matches by token and ranks by salience times hits`() {
        val nodes = listOf(
            node("a", "porch-light", "The porch light timer is set to 7:15pm"),
            node("b", "coffee", "Coffee is ground the night before", salience = 0.9f),
        )
        val act = KnowledgeGraph.activate("what about the porch light?", nodes, emptyList())
        assertEquals(1, act.nodes.size)
        assertEquals("a", act.nodes.first().id)
    }

    @Test
    fun `spread wakes a neighbor that matched zero tokens`() {
        // The evening-routine node shares NO token with the question — only
        // an edge from the porch-light node can wake it. THIS is the
        // non-linear property the whole web exists for.
        val nodes = listOf(
            node("porch", "porch-light", "The porch light timer is set to 7:15pm"),
            node("routine", "evening-routine", "Wind-down starts with dimmed lights and no screens"),
            node("unrelated", "taxes", "Quarterly filings are due in April"),
        )
        val edges = listOf(KnowledgeGraph.Edge("porch", "routine", "part-of", 0.8f))
        val act = KnowledgeGraph.activate("question about the porch light", nodes, edges)
        assertTrue("porch must wake", act.nodes.any { it.id == "porch" })
        assertTrue(
            "the zero-token neighbor must wake via its edge — the non-linear property",
            act.nodes.any { it.id == "routine" },
        )
        assertTrue(
            "the disconnected node must stay asleep",
            act.nodes.none { it.id == "unrelated" },
        )
    }

    @Test
    fun `second hop carries less activation than the first`() {
        val nodes = listOf(
            node("seed", "alpha", "alpha topic words here"),
            node("hop1", "beta", "beta content"),
            node("hop2", "gamma", "gamma content"),
        )
        val edges = listOf(
            KnowledgeGraph.Edge("seed", "hop1", "related", 1.0f),
            KnowledgeGraph.Edge("hop1", "hop2", "related", 1.0f),
        )
        val act = KnowledgeGraph.activate("alpha topic words", nodes, edges)
        val s1 = act.scores.getValue("hop1")
        val s2 = act.scores.getValue("hop2")
        assertTrue("hop1 ($s1) must outscore hop2 ($s2)", s1 > s2)
        assertEquals("seed must rank first", "seed", act.nodes.first().id)
    }

    @Test
    fun `caps are respected and edges only survive between kept nodes`() {
        val nodes = (1..20).map { node("n$it", "topic number $it", "content number $it") }
        val edges = (1..19).map { KnowledgeGraph.Edge("n$it", "n${it + 1}", "related", 0.5f) }
        val act = KnowledgeGraph.activate("topic number 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15", nodes, edges, maxNodes = 5, maxEdges = 3)
        assertTrue("node cap: ${act.nodes.size}", act.nodes.size <= 5)
        assertTrue("edge cap: ${act.edges.size}", act.edges.size <= 3)
        val kept = act.nodes.map { it.id }.toSet()
        assertTrue(
            "no edge may dangle outside the kept subgraph",
            act.edges.all { it.from in kept && it.to in kept },
        )
    }

    @Test
    fun `nothing wakes on an unrelated message`() {
        val nodes = listOf(node("a", "porch-light", "porch light timer"))
        val act = KnowledgeGraph.activate("tell me about quantum chromodynamics", nodes, emptyList())
        assertTrue(act.nodes.isEmpty())
        assertTrue(act.edges.isEmpty())
    }

    @Test
    fun `decay forgets slowly after a grace week and floors at zero dot zero five`() {
        val now = 100L * 86_400_000L
        // 10 idle days: 3 past grace → 0.8 - 0.06 = 0.74
        assertEquals(0.74f, KnowledgeGraph.decay(0.8f, now - 10L * 86_400_000L, now), 0.001f)
        // within grace: unchanged
        assertEquals(0.8f, KnowledgeGraph.decay(0.8f, now - 5L * 86_400_000L, now), 0.001f)
        // never below the floor
        assertEquals(0.05f, KnowledgeGraph.decay(0.3f, now - 400L * 86_400_000L, now), 0.001f)
        // never accessed: no decay signal
        assertEquals(0.8f, KnowledgeGraph.decay(0.8f, null, now), 0.001f)
    }

    @Test
    fun `relatedness is overlap over the larger token set`() {
        val r = KnowledgeGraph.relatedness("porch light timer evening", "porch light schedule")
        // shared {porch, light}; larger set = 4 → 0.5
        assertEquals(0.5f, r, 0.001f)
        assertEquals(0f, KnowledgeGraph.relatedness("alpha beta", "gamma delta"), 0.001f)
    }
}
