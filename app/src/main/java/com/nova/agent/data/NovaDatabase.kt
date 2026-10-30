package com.nova.agent.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nova.agent.data.dao.*
import com.nova.agent.data.entity.*

@Database(
    entities = [
        KnowledgeEntry::class,
        SkillNode::class,
        Conversation::class,
        CodeArtifact::class,
        ResearchSession::class,
        ReflectionLog::class,
        UserProfile::class,
        StrategyMemory::class,
        EvolutionEvent::class,
    ],
    version = 1,
    exportSchema = false
)
abstract class NovaDatabase : RoomDatabase() {
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun skillDao(): SkillDao
    abstract fun conversationDao(): ConversationDao
    abstract fun codeArtifactDao(): CodeArtifactDao
    abstract fun reflectionDao(): ReflectionDao
    abstract fun profileDao(): ProfileDao
    abstract fun strategyDao(): StrategyDao
    abstract fun evolutionDao(): EvolutionDao
    abstract fun researchDao(): ResearchDao
}
