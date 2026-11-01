package host.vorv.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = true,
)
abstract class VorvDatabase : RoomDatabase() {
    abstract fun episodeDao(): EpisodeDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun skillDao(): SkillDao
    abstract fun taskDao(): TaskDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun artifactDao(): ArtifactDao

    companion object {
        /**
         * v1 → v2 (audit finding H-5 / M-7): tool_calls gains a nullable
         * outputFilePath column so full tool outputs can live in a file while
         * the DB keeps a 2000-char preview.
         *
         * There is deliberately NO fallbackToDestructiveMigration anywhere in
         * the app: silently wiping the user's memory/tasks on a schema bump is
         * data loss, not a migration.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tool_calls ADD COLUMN outputFilePath TEXT")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
