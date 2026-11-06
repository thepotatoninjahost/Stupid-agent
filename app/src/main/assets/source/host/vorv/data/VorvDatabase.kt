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
        ChatMessageEntity::class,
    ],
    version = 3,
    // Schema JSON export is deliberately OFF: it's tooling sugar, and the
    // KSP arg-based export path is flaky across variants in clean CI
    // environments (Room 2.6.1 "Empty schema file" in kspReleaseKotlin).
    // The migration itself is real and directly tested — see MigrationTest,
    // which builds a v1 database, runs MIGRATION_1_2 and asserts the column
    // + data survival without needing schema snapshots.
    exportSchema = false,
)
abstract class VorvDatabase : RoomDatabase() {
    abstract fun episodeDao(): EpisodeDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun skillDao(): SkillDao
    abstract fun taskDao(): TaskDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun chatMessageDao(): ChatMessageDao

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

        /**
         * v2 → v3 (code review M1, 2026-11): the chat_messages table — chat
         * history becomes persistent. Fresh CREATE on upgrade; existing data
         * untouched.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` TEXT NOT NULL,
                        `ts` INTEGER NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `citationsJson` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )""".trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_messages_ts` ON `chat_messages` (`ts`)"
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
