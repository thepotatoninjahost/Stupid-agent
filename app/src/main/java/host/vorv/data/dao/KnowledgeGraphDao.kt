package host.vorv.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import host.vorv.data.entity.KnowledgeEdgeEntity
import host.vorv.data.entity.KnowledgeNodeEntity
import host.vorv.data.entity.OperatorProfileEntity

/**
 * v0.8.0 — persistence for the knowledge web. See KnowledgeEntities.kt
 * for what the graph IS; the activation algorithm that reads it lives in
 * host.vorv.knowledge.KnowledgeGraph (pure Kotlin, unit-tested).
 *
 * Scale note (honest, per the no-promises rule): the on-device graph is
 * expected in the hundreds-to-low-thousands of nodes. recentNodes/recentEdges
 * load a bounded window and activation runs in memory — O(nodes·tokens),
 * microseconds at this scale. If the graph ever outgrows that, the query
 * moves into SQL; the pure algorithm keeps its tests either way.
 */
@Dao
interface KnowledgeGraphDao {

    @Upsert
    suspend fun upsertNode(node: KnowledgeNodeEntity)

    @Upsert
    suspend fun upsertEdge(edge: KnowledgeEdgeEntity)

    @Query("SELECT * FROM knowledge_nodes ORDER BY ts DESC LIMIT :limit")
    suspend fun recentNodes(limit: Int): List<KnowledgeNodeEntity>

    @Query("SELECT * FROM knowledge_edges ORDER BY ts DESC LIMIT :limit")
    suspend fun recentEdges(limit: Int): List<KnowledgeEdgeEntity>

    @Query("SELECT * FROM knowledge_nodes WHERE id = :id")
    suspend fun nodeById(id: String): KnowledgeNodeEntity?

    /** Full-id OR 8-char short-id lookup (the prompt shows short ids). */
    @Query(
        "SELECT * FROM knowledge_nodes WHERE id = :id OR id LIKE :id || '%' " +
            "ORDER BY ts DESC LIMIT 1"
    )
    suspend fun nodeByShortId(id: String): KnowledgeNodeEntity?

    @Query(
        "SELECT * FROM knowledge_nodes WHERE topic LIKE '%' || :q || '%' " +
            "OR content LIKE '%' || :q || '%' ORDER BY salience DESC, ts DESC LIMIT :limit"
    )
    suspend fun search(q: String, limit: Int): List<KnowledgeNodeEntity>

    @Query(
        "SELECT * FROM knowledge_edges WHERE fromId IN (:ids) OR toId IN (:ids)"
    )
    suspend fun edgesTouching(ids: List<String>): List<KnowledgeEdgeEntity>

    @Query(
        "UPDATE knowledge_nodes SET salience = :salience, accessCount = accessCount + 1, " +
            "lastAccessedAt = :ts WHERE id = :id"
    )
    suspend fun touch(id: String, salience: Float, ts: Long)

    @Query("SELECT * FROM knowledge_nodes WHERE kind = 'operator' ORDER BY ts DESC LIMIT 1")
    suspend fun operatorNode(): KnowledgeNodeEntity?

    @Query("SELECT COUNT(*) FROM knowledge_nodes")
    suspend fun nodeCount(): Int

    @Query("SELECT COUNT(*) FROM knowledge_edges")
    suspend fun edgeCount(): Int

    @Query("SELECT kind, COUNT(*) AS c FROM knowledge_nodes GROUP BY kind ORDER BY c DESC")
    suspend fun countsByKind(): List<KindCount>

    /** Result row for countsByKind(). */
    data class KindCount(val kind: String, val c: Int)

    // ------------------------------------------------------- operator profile

    @Upsert
    suspend fun upsertOperator(profile: OperatorProfileEntity)

    @Query("SELECT * FROM operator_profile WHERE id = 'operator' LIMIT 1")
    suspend fun operator(): OperatorProfileEntity?
}
