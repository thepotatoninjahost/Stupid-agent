package host.vorv.data

import androidx.room.Database
import androidx.room.RoomDatabase
import host.vorv.data.dao.*
import host.vorv.data.entity.*

@Database(
    entities = [
        Episode::class,
        KnowledgeEntry::class,
        KnowledgeFts::class,
        Skill::class,
        AgentTask::class,
        ToolCall::class,
        Artifact::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class VorvDatabase : RoomDatabase() {
    abstract fun episodeDao(): EpisodeDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun skillDao(): SkillDao
    abstract fun taskDao(): TaskDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun artifactDao(): ArtifactDao
}
