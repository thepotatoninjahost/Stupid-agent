package host.vorv.data.dao

import androidx.room.*
import host.vorv.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {
    @Insert suspend fun insert(episode: Episode)
    @Query("SELECT * FROM episodes ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<Episode>>
    @Query("SELECT * FROM episodes ORDER BY ts DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<Episode>
    /** Retention cleanup (audit: data retention). Returns rows removed. */
    @Query("DELETE FROM episodes WHERE ts < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: KnowledgeEntry)

    @Update suspend fun update(entry: KnowledgeEntry)

    @Delete suspend fun delete(entry: KnowledgeEntry)

    @Query("SELECT * FROM knowledge ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<KnowledgeEntry>>

    @Query("SELECT * FROM knowledge WHERE id = :id LIMIT 1")
    suspend fun get(id: String): KnowledgeEntry?

    /**
     * FTS search across topic + content. Returns top-K by recency.
     * Used by memory_recall tool.
     */
    @Query("""
        SELECT k.* FROM knowledge k
        JOIN knowledge_fts f ON k.rowid = f.rowid
        WHERE knowledge_fts MATCH :query
        ORDER BY k.ts DESC
        LIMIT :limit
    """)
    suspend fun searchFts(query: String, limit: Int = 5): List<KnowledgeEntry>

    @Query("SELECT * FROM knowledge WHERE topic LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY ts DESC LIMIT :limit")
    suspend fun searchLike(query: String, limit: Int = 5): List<KnowledgeEntry>

    @Query("UPDATE knowledge SET timesRecalled = timesRecalled + 1, lastRecalledAt = :ts WHERE id = :id")
    suspend fun markRecalled(id: String, ts: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM knowledge")
    suspend fun count(): Int
}

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: Skill)

    /**
     * INSERT OR IGNORE keyed on the unique index over skills.name.
     * Kernel calls this before every recordSuccess/recordFailure so stats
     * updates never silently no-op for a tool that wasn't bootstrapped yet
     * (audit findings M-8 / M-9).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureExists(skill: Skill)

    @Query("SELECT * FROM skills WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Skill?

    @Query("SELECT * FROM skills ORDER BY level DESC, xp DESC")
    fun observeAll(): Flow<List<Skill>>

    @Query("UPDATE skills SET successCount = successCount + 1, xp = xp + 5, lastUsedAt = :ts WHERE name = :name")
    suspend fun recordSuccess(name: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE skills SET failCount = failCount + 1, lastUsedAt = :ts WHERE name = :name")
    suspend fun recordFailure(name: String, ts: Long = System.currentTimeMillis())
}

@Dao
interface TaskDao {
    @Insert suspend fun insert(task: AgentTask): Long
    @Update suspend fun update(task: AgentTask)
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AgentTask>>
    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AgentTask?
    @Query("SELECT * FROM tasks WHERE status = 'queued' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueued(): AgentTask?
    @Query("SELECT * FROM tasks WHERE status = 'in_progress' ORDER BY createdAt ASC LIMIT 1")
    suspend fun currentInprogress(): AgentTask?
    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)
    @Query("UPDATE tasks SET iterations = :iterations WHERE id = :id")
    suspend fun setIterations(id: String, iterations: Int)
    @Query("UPDATE tasks SET summary = :summary, status = :status, completedAt = :ts WHERE id = :id")
    suspend fun complete(id: String, summary: String, status: String = "completed", ts: Long = System.currentTimeMillis())
    @Query("UPDATE tasks SET error = :error, status = 'failed', completedAt = :ts WHERE id = :id")
    suspend fun fail(id: String, error: String, ts: Long = System.currentTimeMillis())
    /** Retention cleanup (audit: data retention). Returns rows removed. */
    @Query("DELETE FROM tasks WHERE completedAt IS NOT NULL AND completedAt < :cutoff")
    suspend fun deleteCompletedBefore(cutoff: Long): Int
}

@Dao
interface ToolCallDao {
    @Insert suspend fun insert(call: ToolCall)
    @Query("SELECT * FROM tool_calls WHERE taskId = :taskId ORDER BY iteration ASC, ts ASC")
    fun observeForTask(taskId: String): Flow<List<ToolCall>>
    @Query("SELECT * FROM tool_calls WHERE taskId = :taskId ORDER BY iteration ASC, ts ASC")
    suspend fun forTask(taskId: String): List<ToolCall>
    @Query("SELECT * FROM tool_calls ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ToolCall>>
    /** Retention cleanup (audit: data retention). Returns rows removed. */
    @Query("DELETE FROM tool_calls WHERE ts < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

@Dao
interface ArtifactDao {
    @Insert suspend fun insert(artifact: Artifact)
    @Query("SELECT * FROM artifacts WHERE taskId = :taskId ORDER BY ts ASC")
    fun observeForTask(taskId: String): Flow<List<Artifact>>
    @Query("SELECT * FROM artifacts ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<Artifact>>
    @Query("SELECT * FROM artifacts WHERE id = :id LIMIT 1")
    suspend fun get(id: String): Artifact?
}

/**
 * M1 (code review, 2026-11): persisted chat history access. [ChatMessageEntity.rowId]
 * is the tiebreak so same-millisecond messages (user echo + busy hint) reload
 * in the order they were written. The window keeps the NEWEST [limit]
 * messages (inner query DESC), then presents them chronologically (outer
 * ASC) — a bounded RECENT window, not the oldest N.
 */
@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: ChatMessageEntity)

    @Query("SELECT * FROM (SELECT * FROM chat_messages ORDER BY ts DESC, rowId DESC LIMIT :limit) ORDER BY ts ASC, rowId ASC")
    suspend fun loadRecent(limit: Int = 500): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun count(): Int

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}
