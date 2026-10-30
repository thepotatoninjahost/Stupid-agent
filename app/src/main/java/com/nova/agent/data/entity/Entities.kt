package com.nova.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Long-term knowledge entries — NOVA's persistent memory.
 * Each entry is a learned fact, concept, pattern, or mistake.
 */
@Entity(
    tableName = "knowledge_entries",
    indices = [Index("topic"), Index("category"), Index("source")]
)
data class KnowledgeEntry(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val topic: String,
    val category: String,            // "concept" | "pattern" | "mistake" | "lesson" | "fact"
    val content: String,
    val source: String,              // "user-taught" | "self-researched" | "inferred" | "user-corrected" | "reflection"
    val confidence: Float = 0.5f,    // 0..1
    val timesRecalled: Int = 0,
    val lastRecalledAt: Long? = null,
    val relatedTo: String? = null,   // JSON array of related IDs
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "skill_nodes", indices = [Index("domain")])
data class SkillNode(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val domain: String,              // "frontend" | "backend" | "algorithms" | "data" | "mobile" | "general"
    val level: Int = 1,
    val xp: Int = 0,
    val lessons: String? = null,     // JSON list of lesson pointers
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val messages: String,            // JSON array of {role, content, trace?, ts}
    val mode: String = "chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "code_artifacts")
data class CodeArtifact(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val language: String,
    val code: String,
    val explanation: String? = null,
    val status: String = "draft",    // "draft" | "tested" | "executed" | "failed"
    val executionResult: String? = null, // JSON
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "research_sessions")
data class ResearchSession(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val topic: String,
    val queries: String,             // JSON list
    val findings: String,             // JSON list
    val lessons: String? = null,
    val status: String = "completed",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reflection_logs", indices = [Index("trigger"), Index("createdAt")])
data class ReflectionLog(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val trigger: String,             // "manual" | "scheduled" | "post-chat"
    val gaps: String,                // JSON array
    val researchDone: String,        // JSON
    val lessonsLearned: String,      // JSON of lesson IDs
    val summary: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: String = "default",
    val name: String? = null,
    val primaryLanguages: String = "[]", // JSON array
    val preferredStack: String? = null,
    val skillLevel: String = "intermediate",
    val interests: String = "[]",         // JSON array
    val styleNotes: String? = null,
    val lessonsTaught: Int = 0,
    val conversationsHad: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "strategy_memory", indices = [Index("questionType"), Index("intent")])
data class StrategyMemory(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val questionType: String,        // "syntax" | "conceptual" | "debugging" | "design" | "research" | "code-request"
    val intent: String,              // intent signature for matching
    val capabilities: String,        // JSON array
    val successScore: Float = 0.5f,
    val trials: Int = 1,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "evolution_events", indices = [Index("kind"), Index("createdAt")])
data class EvolutionEvent(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val kind: String,                 // "skill_up" | "lesson_learned" | "mistake_corrected" | "reflection" | "code_executed"
    val summary: String,
    val details: String,              // JSON
    val createdAt: Long = System.currentTimeMillis()
)
