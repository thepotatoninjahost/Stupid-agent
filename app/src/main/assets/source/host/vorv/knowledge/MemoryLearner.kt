package host.vorv.knowledge

import host.vorv.data.dao.KnowledgeGraphDao
import host.vorv.data.entity.KnowledgeEdgeEntity
import host.vorv.data.entity.KnowledgeNodeEntity
import host.vorv.data.entity.OperatorProfileEntity

/**
 * v0.8.0 — the learning half of the knowledge web (owner spec, 2026-11-09):
 * "It should learn from every chat, project, every experience, and
 * interaction."
 *
 * Two pure pieces and one thin DB piece:
 *
 *  - [MemoryExtractor] (pure, unit-tested) mines every user message for
 *    preferences, facts, and the operator's own identity — no LLM call,
 *    no invented content: a node is only created from words the operator
 *    actually wrote;
 *  - [MemoryExtractor.fromOutcome] turns each finished task into a lesson
 *    node (what was asked, whether it worked, which tools carried it);
 *  - [MemoryLearner] (suspend, DAO-backed) writes nodes, de-duplicates by
 *    topic (a re-learned fact STRENGTHENS the node instead of stacking a
 *    copy), and wires up to three `related` edges to the existing nodes
 *    with the strongest keyword overlap — that is what makes the memory a
 *    WEB instead of a list.
 *
 * Honest limits, stated where they belong: this is heuristic learning —
 * pattern extraction, not comprehension. It catches "I prefer X", "remember
 * that Y", "my name is Z", and task outcomes. It does NOT read minds, and
 * it never writes a node whose content did not literally come from the
 * operator or a real task record. The MEMORY_LEARNER_ENABLED flag exists
 * so the operator can turn auto-learning off entirely (loyalty includes
 * obedience).
 */
object MemoryExtractor {

    /** A mined memory candidate, pre-persistence. */
    data class Candidate(
        val kind: String,      // "preference" | "fact" | "lesson" | "operator"
        val topic: String,
        val content: String,
        val salience: Float,
    )

    private val OPERATOR_PATTERNS = listOf(
        Regex("""(?:my name is|call me|i am|i'm)\s+([a-z][a-z0-9' -]{1,30})""", RegexOption.IGNORE_CASE),
    )
    private val PREFERENCE_PATTERNS = listOf(
        Regex("""i (?:prefer|like|want|always|never)\b[^.!?\n]{3,120}""", RegexOption.IGNORE_CASE),
    )
    private val REMEMBER_PATTERN =
        Regex("""(?:remember(?: this)?(?: that)?|don't forget(?: that)?)[:\s]+([^.!?\n]{3,200})""", RegexOption.IGNORE_CASE)

    /** Short label from a sentence: first four informative words, dashed. */
    fun topicFrom(text: String): String =
        KnowledgeGraph.tokenize(text).take(4).joinToString("-").ifEmpty { "note" }

    /**
     * Mine ONE user message. At most one operator + one preference + one
     * fact per message keeps the graph from exploding on chatty days.
     */
    fun fromUserMessage(text: String): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val trimmed = text.trim()
        if (trimmed.length < 8) return out

        for (rx in OPERATOR_PATTERNS) {
            val m = rx.find(trimmed) ?: continue
            val name = m.groupValues[1].trim().trim('\'').split(Regex("\\s+")).take(2)
                .joinToString(" ")
            if (name.isBlank()) continue
            out.add(
                Candidate(
                    kind = "operator",
                    topic = "operator",
                    content = "The operator's name is $name.",
                    salience = 1.0f,
                )
            )
            break
        }
        for (rx in PREFERENCE_PATTERNS) {
            val m = rx.find(trimmed) ?: continue
            val pref = m.value.trim().replace(Regex("\\s+"), " ")
            out.add(
                Candidate(
                    kind = "preference",
                    topic = topicFrom(pref),
                    content = pref.replaceFirstChar { it.uppercase() },
                    salience = 0.8f,
                )
            )
            break
        }
        REMEMBER_PATTERN.find(trimmed)?.let { m ->
            val fact = m.groupValues[1].trim().replace(Regex("\\s+"), " ")
            if (fact.length >= 4) {
                out.add(
                    Candidate(
                        kind = "fact",
                        topic = topicFrom(fact),
                        content = "Operator taught: $fact.",
                        salience = 0.9f,
                    )
                )
            }
        }
        return out
    }

    /**
     * A finished task becomes a lesson node. Content is assembled ONLY
     * from the real task record — prompt, outcome, tools — never invented.
     */
    fun fromOutcome(
        prompt: String,
        ok: Boolean,
        toolNames: List<String>,
        iterations: Int,
    ): Candidate? {
        val p = prompt.trim()
        if (p.length < 8) return null
        val tools = if (toolNames.isEmpty()) "no tools" else toolNames.distinct().take(5).joinToString(", ")
        val outcome = if (ok) "completed" else "failed"
        return Candidate(
            kind = "lesson",
            topic = topicFrom(p),
            content = "Task \"${p.take(120)}\" $outcome after $iterations iteration(s), tools: $tools.",
            salience = if (ok) 0.45f else 0.65f, // failures are worth remembering harder
        )
    }
}

/** Reads/writes the web. Every public call is safe to wrap in runCatching. */
class MemoryLearner(private val dao: KnowledgeGraphDao) {

    /** Learn from one user chat message. Returns the ids it wrote/touched. */
    suspend fun ingestUserMessage(text: String, now: Long = System.currentTimeMillis()): List<String> {
        val candidates = MemoryExtractor.fromUserMessage(text)
        val written = mutableListOf<String>()
        for (c in candidates) write(c, source = "chat-learned", now = now)?.let { written.add(it) }
        return written
    }

    /** Learn from one finished task. */
    suspend fun ingestOutcome(
        prompt: String,
        ok: Boolean,
        toolNames: List<String>,
        iterations: Int,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val c = MemoryExtractor.fromOutcome(prompt, ok, toolNames, iterations) ?: return null
        return write(c, source = "task-outcome", now = now)
    }

    /**
     * Write one candidate: dedup by topic (strengthen, don't stack), wire
     * up to three `related` edges by keyword overlap, special-case the
     * operator profile. Returns the node id, or null for a no-op.
     */
    private suspend fun write(c: MemoryExtractor.Candidate, source: String, now: Long): String? {
        if (c.kind == "operator") {
            val name = c.content.removePrefix("The operator's name is ").removeSuffix(".").trim()
            if (name.isBlank()) return null
            dao.upsertOperator(
                OperatorProfileEntity(
                    id = "operator",
                    name = name,
                    learnedFrom = source,
                    updatedAt = now,
                )
            )
            // The operator node itself: single, always salient.
            val existing = dao.operatorNode()
            val node = existing?.copy(
                content = c.content,
                salience = 1.0f,
                lastAccessedAt = now,
                accessCount = existing.accessCount + 1,
            ) ?: KnowledgeNodeEntity(
                kind = "operator",
                topic = "operator",
                content = c.content,
                source = "operator",
                citationType = "memory",
                citationRef = "operator",
                salience = 1.0f,
                accessCount = 1,
                lastAccessedAt = now,
            )
            dao.upsertNode(node)
            return node.id
        }

        // Dedup: same kind+topic → strengthen and refresh instead of a copy.
        val existing = dao.search(c.topic, limit = 20)
            .firstOrNull { it.kind == c.kind && it.topic == c.topic }
        if (existing != null) {
            val salience = KnowledgeGraph.decay(existing.salience, existing.lastAccessedAt, now)
                .coerceAtMost(1f) + 0.1f
            dao.touch(
                existing.id,
                salience = salience.coerceAtMost(1f),
                ts = now,
            )
            return existing.id
        }

        val id = java.util.UUID.randomUUID().toString()
        dao.upsertNode(
            KnowledgeNodeEntity(
                id = id,
                ts = now,
                kind = c.kind,
                topic = c.topic,
                content = c.content,
                source = source,
                citationType = "memory",
                citationRef = id,
                salience = c.salience,
                accessCount = 0,
                lastAccessedAt = now,
            )
        )

        // Wire `related` edges to the most-related existing nodes (max 3).
        val neighbors = dao.recentNodes(200)
            .filter { it.id != id }
            .map { it to KnowledgeGraph.relatedness("${it.topic} ${it.content}", "${c.topic} ${c.content}") }
            .filter { it.second >= 0.15f }
            .sortedByDescending { it.second }
            .take(3)
        for ((n, strength) in neighbors) {
            dao.upsertEdge(
                KnowledgeEdgeEntity(
                    fromId = id,
                    toId = n.id,
                    relation = "related",
                    weight = strength.coerceIn(0.15f, 0.9f),
                    ts = now,
                )
            )
        }
        return id
    }
}
