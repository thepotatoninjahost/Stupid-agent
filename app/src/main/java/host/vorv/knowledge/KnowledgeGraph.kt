package host.vorv.knowledge

/**
 * v0.8.0 — the non-linear brain's retrieval core (owner spec, 2026-11-09).
 *
 * A flat memory answers "which rows match this keyword". A KNOWLEDGE WEB
 * answers "what does this remind the agent of" — and that is what
 * [activate] computes:
 *
 *   1. SEED   — nodes whose topic/content share tokens with the message
 *               score salience·hits;
 *   2. SPREAD — one hop along edges adds 0.45·edgeWeight·seedScore, a
 *               second hop 0.15·edgeWeight·that gain: the porch-light
 *               question wakes the evening-routine node that never
 *               matched a single token;
 *   3. CUT    — keep the top [maxNodes] by activation, keep only edges
 *               between kept nodes, top [maxEdges].
 *
 * This is bounded spreading activation — the classical associative-memory
 * retrieval model, in ~100 lines of pure Kotlin. No mysticism, no
 * unimplementable claims: it is exactly as "non-linear" as a graph walk,
 * and it is unit-tested to behave that way.
 *
 * Salience DECAY ([decay]) is honest forgetting: a node unused for a week
 * loses 0.02/day (floor 0.05). Nodes the operator keeps coming back to
 * stay strong; stale trivia fades instead of polluting every prompt.
 */
object KnowledgeGraph {

    /** Pure in-memory node used by activation and prompt weaving. */
    data class Node(
        val id: String,
        val kind: String,
        val topic: String,
        val content: String,
        val salience: Float,
        val accessCount: Int,
    )

    /** Pure in-memory edge. */
    data class Edge(val from: String, val to: String, val relation: String, val weight: Float)

    /** The result of activation: the woken subgraph, strongest first. */
    data class Activation(
        val nodes: List<Node>,
        val edges: List<Edge>,
        /** id → activation score (for tests and the prompt's salience note). */
        val scores: Map<String, Float>,
    )

    private val STOPWORDS = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all", "can", "her", "was",
        "one", "our", "out", "day", "get", "has", "him", "his", "how", "man", "new",
        "now", "old", "see", "two", "way", "who", "did", "its", "let", "she", "too",
        "use", "that", "with", "have", "this", "will", "your", "from", "they", "know",
        "want", "been", "good", "much", "some", "time", "very", "when", "come", "here",
        "just", "like", "long", "make", "many", "over", "such", "take", "than", "them",
        "well", "what", "about", "after", "again", "could", "every", "give",
        "into", "keep", "last", "made", "most", "never", "other", "right", "said",
        "same", "tell", "their", "there", "these", "thing", "think", "those", "where",
        "which", "while", "would", "please", "thanks", "okay", "yeah", "going",
    )

    /** Tokenize for matching: lowercase words, stopwords and 1-2 char fragments out. */
    fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9']+"))
            .filter { it.length >= 3 && it !in STOPWORDS }
            .distinct()

    /**
     * Associative activation. Deterministic; empty when nothing wakes.
     */
    fun activate(
        message: String,
        nodes: List<Node>,
        edges: List<Edge>,
        maxNodes: Int = 12,
        maxEdges: Int = 10,
    ): Activation {
        if (nodes.isEmpty() || maxNodes <= 0) return Activation(emptyList(), emptyList(), emptyMap())

        val tokens = tokenize(message)
        if (tokens.isEmpty()) return Activation(emptyList(), emptyList(), emptyMap())

        // 1. Seed: token hits × salience.
        val seed = HashMap<String, Float>()
        for (n in nodes) {
            val nodeTokens = (tokenize(n.topic) + tokenize(n.content)).toSet()
            val hits = tokens.count { it in nodeTokens }
            if (hits > 0) seed[n.id] = n.salience * hits
        }
        if (seed.isEmpty()) return Activation(emptyList(), emptyList(), emptyMap())

        // 2. Spread: association carries activation across the web.
        val adjacency = HashMap<String, MutableList<Edge>>()
        for (e in edges) {
            adjacency.getOrPut(e.from) { mutableListOf() }.add(e)
            adjacency.getOrPut(e.to) { mutableListOf() }.add(e)
        }
        val scores = HashMap<String, Float>(seed)
        for ((seedId, seedScore) in seed) {
            for (e in adjacency[seedId].orEmpty()) {
                val other = if (e.from == seedId) e.to else e.from
                val gain = 0.45f * e.weight * seedScore
                scores[other] = (scores[other] ?: 0f) + gain
                // Second hop from the first-hop node.
                for (e2 in adjacency[other].orEmpty()) {
                    if (e2 === e) continue
                    val third = if (e2.from == other) e2.to else e2.from
                    if (seed.containsKey(third)) continue // already seeded
                    scores[third] = (scores[third] ?: 0f) + 0.15f * e2.weight * gain
                }
            }
        }

        // 3. Cut: top nodes; edges only between kept nodes; deterministic order.
        val kept = nodes
            .filter { (scores[it.id] ?: 0f) > 0f }
            .sortedWith(compareByDescending<Node> { scores[it.id] ?: 0f }.thenBy { it.id })
            .take(maxNodes)
        val keptIds = kept.map { it.id }.toSet()
        val keptEdges = edges
            .filter { it.from in keptIds && it.to in keptIds }
            .sortedWith(compareByDescending<Edge> { it.weight }.thenBy { it.from }.thenBy { it.to })
            .take(maxEdges)
        return Activation(kept, keptEdges, scores.filterKeys { it in keptIds })
    }

    /**
     * Honest forgetting: -0.02/day after [graceDays] idle, floor 0.05.
     * Called lazily when the learner re-reads a node, never in a hot loop.
     */
    fun decay(salience: Float, lastAccessedAt: Long?, now: Long, graceDays: Int = 7): Float {
        if (lastAccessedAt == null) return salience
        val idleDays = ((now - lastAccessedAt) / 86_400_000L).toInt()
        if (idleDays <= graceDays) return salience
        return maxOf(0.05f, salience - 0.02f * (idleDays - graceDays))
    }

    /**
     * Keyword overlap between two texts, normalized 0..1 — used by the
     * learner to decide which existing nodes a new node should connect to.
     */
    fun relatedness(a: String, b: String): Float {
        val ta = tokenize(a).toSet()
        val tb = tokenize(b).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val shared = ta.intersect(tb).size
        return shared.toFloat() / maxOf(ta.size, tb.size)
    }
}
