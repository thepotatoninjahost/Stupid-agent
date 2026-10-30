package com.nova.agent.data.dao

import androidx.room.*
import com.nova.agent.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: KnowledgeEntry)

    @Query("SELECT * FROM knowledge_entries WHERE topic LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recallByTopic(query: String, limit: Int = 5): List<KnowledgeEntry>

    @Query("SELECT * FROM knowledge_entries WHERE category = 'mistake' AND content LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recallMistakes(query: String, limit: Int = 3): List<KnowledgeEntry>

    @Query("SELECT * FROM knowledge_entries ORDER BY updatedAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 100): Flow<List<KnowledgeEntry>>

    @Delete
    suspend fun delete(entry: KnowledgeEntry)
}

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillNode)

    @Query("SELECT * FROM skill_nodes WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SkillNode?

    @Query("SELECT * FROM skill_nodes ORDER BY level DESC, xp DESC")
    fun observeAll(): Flow<List<SkillNode>>
}

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(conversation: Conversation)

    @Update
    suspend fun update(conversation: Conversation)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<Conversation>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): Conversation?
}

@Dao
interface CodeArtifactDao {
    @Insert
    suspend fun insert(artifact: CodeArtifact)

    @Update
    suspend fun update(artifact: CodeArtifact)

    @Query("SELECT * FROM code_artifacts ORDER BY createdAt DESC LIMIT 50")
    fun observeAll(): Flow<List<CodeArtifact>>
}

@Dao
interface ReflectionDao {
    @Insert
    suspend fun insert(log: ReflectionLog)

    @Query("SELECT * FROM reflection_logs ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastReflection(): ReflectionLog?
}

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfile)

    @Query("SELECT * FROM user_profile WHERE id = 'default' LIMIT 1")
    suspend fun get(): UserProfile?
}

@Dao
interface StrategyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(strategy: StrategyMemory)

    @Query("SELECT * FROM strategy_memory WHERE intent = :intent ORDER BY successScore DESC LIMIT 1")
    suspend fun findBest(intent: String): StrategyMemory?
}

@Dao
interface EvolutionDao {
    @Insert
    suspend fun insert(event: EvolutionEvent)

    @Query("SELECT * FROM evolution_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<EvolutionEvent>>

    @Query("SELECT * FROM evolution_events WHERE kind = :kind ORDER BY createdAt DESC")
    fun observeByKind(kind: String): Flow<List<EvolutionEvent>>
}

@Dao
interface ResearchDao {
    @Insert
    suspend fun insert(session: ResearchSession)

    @Query("SELECT * FROM research_sessions ORDER BY createdAt DESC LIMIT 20")
    fun observeRecent(): Flow<List<ResearchSession>>
}
