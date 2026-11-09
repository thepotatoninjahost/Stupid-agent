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
        KnowledgeNodeEntity::class,
        KnowledgeEdgeEntity::class,
        OperatorProfileEntity::class,
    ],
    version = 4,
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
    abstract fun knowledgeGraphDao(): KnowledgeGraphDao

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
         * untouched. rowId is the autoincrement PK (insertion-order tiebreak,
         * explicit because Room cannot query SQLite's implicit rowid); id is
         * UNIQUE so re-inserting a logical message replaces its row.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `id` TEXT NOT NULL,
                        `ts` INTEGER NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `citationsJson` TEXT NOT NULL
                    )""".trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_messages_ts` ON `chat_messages` (`ts`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_messages_id` ON `chat_messages` (`id`)"
                )
            }
        }

        /**
         * v3 → v4 (owner spec, 2026-11-09): the knowledge web — nodes, edges,
         * and the single-operator profile. Fresh CREATEs on upgrade; existing
         * tables (including the flat `knowledge` memory) are untouched — the
         * web is a new associative layer, not a replacement.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `knowledge_nodes` (
                        `id` TEXT NOT NULL,
                        `ts` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `topic` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `citationType` TEXT NOT NULL,
                        `citationRef` TEXT NOT NULL,
                        `salience` REAL NOT NULL,
                        `accessCount` INTEGER NOT NULL,
                        `lastAccessedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_kind` ON `knowledge_nodes` (`kind`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_topic` ON `knowledge_nodes` (`topic`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_ts` ON `knowledge_nodes` (`ts`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_salience` ON `knowledge_nodes` (`salience`)")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `knowledge_edges` (
                        `id` TEXT NOT NULL,
                        `fromId` TEXT NOT NULL,
                        `toId` TEXT NOT NULL,
                        `relation` TEXT NOT NULL,
                        `weight` REAL NOT NULL,
                        `ts` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_edges_fromId` ON `knowledge_edges` (`fromId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_edges_toId` ON `knowledge_edges` (`toId`)")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_edges_fromId_toId_relation` " +
                        "ON `knowledge_edges` (`fromId`, `toId`, `relation`)"
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `operator_profile` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `learnedFrom` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )""".trimIndent()
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
