package host.vorv.data.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Timestamped event log. Every meaningful thing that happens is recorded here.
 * Read on startup so the agent remembers its own past.
 */
@Entity(tableName = "episodes", indices = [Index("ts"), Index("kind")])
data class Episode(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val ts: Long = System.currentTimeMillis(),
    val kind: String,           // "boot" | "task_started" | "task_completed" | "tool_call" | "lesson" | "error"
    val summary: String,
    val detailsJson: String = "{}",
)

/**
 * Long-term knowledge entries with citation tracking.
 */
@Entity(tableName = "knowledge", indices = [Index("topic"), Index("source"), Index("citationType")])
data class KnowledgeEntry(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val ts: Long = System.currentTimeMillis(),
    val topic: String,
    val content: String,
    val source: String,             // "user-taught" | "memory_recall" | "web_fetch" | "python_exec" | "reflection"
    val citationType: String,       // "memory" | "web" | "exec" | "file" | "uncited"
    val citationRef: String = "",
    val confidence: Float = 0.5f,
    val timesRecalled: Int = 0,
    val lastRecalledAt: Long? = null,
)

/** FTS4 mirror of KnowledgeEntry.content + topic for semantic-ish recall. */
@Entity(tableName = "knowledge_fts")
@Fts4(contentEntity = KnowledgeEntry::class)
data class KnowledgeFts(
    val topic: String,
    val content: String,
)

/**
 * Skills = capabilities the agent has. Tracks success rate per skill.
 */
@Entity(tableName = "skills", indices = [Index("name", unique = true), Index("domain")])
data class Skill(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val domain: String,             // "tools" | "reasoning" | "language" | "domain"
    val level: Int = 1,
    val xp: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastUsedAt: Long = System.currentTimeMillis(),
)

/**
 * Tasks the agent is/was working on. Persistent queue.
 */
@Entity(tableName = "tasks", indices = [Index("status"), Index("createdAt")])
data class AgentTask(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val prompt: String,
    val status: String,             // "queued" | "in_progress" | "completed" | "failed" | "stuck"
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val iterations: Int = 0,
    val summary: String? = null,
    val error: String? = null,
)

/**
 * Verifiable execution log. Every tool call lands here with real I/O.
 *
 * outputJson stores a preview (first 2000 chars) so the Tasks list stays
 * light; when the full output exceeded the preview, outputFilePath points
 * at a file under cacheDir/tool_outputs/ holding the complete text.
 */
@Entity(tableName = "tool_calls", indices = [Index("taskId"), Index("ts"), Index("toolName")])
data class ToolCall(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val taskId: String,
    val ts: Long = System.currentTimeMillis(),
    val iteration: Int,
    val toolName: String,
    val inputJson: String,
    val outputJson: String,
    val exitCode: Int,              // 0 = success, non-zero = error
    val durationMs: Long,
    val errorMessage: String? = null,
    val outputFilePath: String? = null,   // schema v2 — full output file when preview was truncated
)

/**
 * Artifacts the agent produced — files, code, etc.
 */
@Entity(tableName = "artifacts", indices = [Index("taskId"), Index("ts")])
data class Artifact(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val taskId: String,
    val ts: Long = System.currentTimeMillis(),
    val name: String,
    val language: String,
    val contentUri: String? = null,    // SAF URI if persisted to workspace
    val contentPreview: String,        // first ~500 chars
    val fullContent: String,            // entire content (for small artifacts)
)

/**
 * M1 (code review, 2026-11): persisted chat history. The conversation used
 * to be memory-only — every restart lost every message while the tool-call
 * logs survived, which reads to the user as "the app forgot what we just
 * said". Messages are written as they are appended and reloaded in
 * AgentViewModel's init. Citations are serialized as a JSON array.
 */
@Entity(tableName = "chat_messages", indices = [Index("ts")])
data class ChatMessageEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val ts: Long = System.currentTimeMillis(),
    val role: String,               // "user" | "assistant"
    val content: String,
    val citationsJson: String = "[]",
)
