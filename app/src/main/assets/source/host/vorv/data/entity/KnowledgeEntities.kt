package host.vorv.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v0.8.0 — the knowledge web (owner spec, 2026-11-09): a NON-LINEAR memory.
 *
 * The flat `knowledge` table answers "keyword → rows". It cannot express
 * "the porch light timer relates to the evening routine relates to the
 * smart-home schedule" — association is the whole point of the request.
 * These two tables are that graph: nodes are atomic things learned
 * (facts, preferences, events, lessons, the operator), edges are typed,
 * weighted associations between them. Retrieval is associative activation
 * (see host.vorv.knowledge.KnowledgeGraph), not a linear dump.
 *
 * `knowledge` (the flat table + its FTS mirror) is NOT migrated or removed:
 * memory_write / memory_recall keep working against it; the web is the new
 * associative layer on top. Never downgrade existing memory to ship this.
 */
@Entity(
    tableName = "knowledge_nodes",
    indices = [Index("kind"), Index("topic"), Index("ts"), Index("salience")],
)
data class KnowledgeNodeEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val ts: Long = System.currentTimeMillis(),
    /** "fact" | "preference" | "event" | "lesson" | "skill" | "operator" */
    val kind: String,
    /** Short label, e.g. "porch-light-timer". */
    val topic: String,
    /** The learned content as a full sentence. */
    val content: String,
    /** "chat-learned" | "task-outcome" | "operator" | "memory_web" | "user-taught" */
    val source: String,
    val citationType: String = "memory",
    val citationRef: String = "",
    /** 0..1 — strength. Rises with use, decays with idleness (KnowledgeGraph.decay). */
    val salience: Float = 0.5f,
    val accessCount: Int = 0,
    val lastAccessedAt: Long? = null,
)

/**
 * A typed, weighted, DIRECTED association between two knowledge nodes.
 * Direction is from → to in the sense "from brings to mind to"; retrieval
 * follows both directions (association is symmetric in effect, the
 * direction records who learned it from whom).
 */
@Entity(
    tableName = "knowledge_edges",
    indices = [
        Index("fromId"),
        Index("toId"),
        Index(value = ["fromId", "toId", "relation"], unique = true),
    ],
)
data class KnowledgeEdgeEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val fromId: String,
    val toId: String,
    /** "related" | "caused" | "part-of" | "fixes" | "contradicts" */
    val relation: String,
    /** 0..1 — association strength; grows when the pair co-activates. */
    val weight: Float = 0.3f,
    val ts: Long = System.currentTimeMillis(),
)

/**
 * The operator profile — exactly one row (id = "operator").
 *
 * "100% loyal to me and me only" is implemented as: the agent has exactly
 * ONE principal, recorded here, learned from the owner's own words ("my
 * name is …", "call me …"). The knowledge-web prompt section pins this
 * identity into every task prompt: the agent serves this operator, no one
 * else, and honesty rules apply without exception. A claim of loyalty is
 * worthless; a pinned single-principal identity the agent can read and
 * repeat is enforceable and testable.
 */
@Entity(tableName = "operator_profile")
data class OperatorProfileEntity(
    @PrimaryKey val id: String = "operator",
    val name: String,
    val learnedFrom: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
