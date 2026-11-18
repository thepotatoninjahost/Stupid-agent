package host.vorv.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 6's table, tested against the real migration the Room builder uses.
 * The point of the test is what must NOT happen: existing chat history being
 * lost, and a repeated fact being stored twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LearningMigrationTest {

    private val v4ChatDdl = """
        CREATE TABLE IF NOT EXISTS `chat_messages` (
            `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `id` TEXT NOT NULL,
            `ts` INTEGER NOT NULL,
            `role` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `citationsJson` TEXT NOT NULL
        )
    """.trimIndent()

    private fun openV4Db(context: Context, name: String): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v4ChatDdl)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migration 4_5 creates the ledger and keeps chat history`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val db = openV4Db(context, "migration-4-5.db")
        try {
            db.execSQL(
                "INSERT INTO chat_messages (id, ts, role, content, citationsJson) " +
                    "VALUES ('m-1', 1000, 'user', 'hello', '[]')",
            )

            VorvDatabase.MIGRATION_4_5.migrate(db)

            val cols = mutableListOf<String>()
            db.query("PRAGMA table_info(learning_rows)").use { c ->
                while (c.moveToNext()) cols.add(c.getString(1))
            }
            assertEquals(
                listOf(
                    "id", "ts", "kind", "sessionId", "inputText", "outputText", "toolCsv",
                    "ok", "errorClass", "engineLabel", "sourceUrl", "quote", "weight",
                    "dedupeKey", "split", "tokenCount",
                ),
                cols,
            )

            val idx = mutableListOf<String>()
            db.query("PRAGMA index_list(learning_rows)").use { c ->
                while (c.moveToNext()) idx.add(c.getString(1))
            }
            assertTrue(idx.contains("index_learning_rows_dedupeKey"))
            assertTrue(idx.contains("index_learning_rows_ts"))
            assertTrue(idx.contains("index_learning_rows_kind"))

            // Chat history is untouched by the upgrade.
            db.query("SELECT content FROM chat_messages WHERE id = 'm-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("hello", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `the same fact cannot be stored twice`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val db = openV4Db(context, "migration-4-5-unique.db")
        try {
            VorvDatabase.MIGRATION_4_5.migrate(db)
            val insert = { key: String ->
                db.execSQL(
                    "INSERT INTO learning_rows (ts, kind, sessionId, inputText, outputText, toolCsv, " +
                        "engineLabel, weight, dedupeKey, split, tokenCount) " +
                        "VALUES (1, 'CHAT_ASSISTANT', '', 'q', 'a', '', '', 0.6, '$key', 'TRAIN', 0)",
                )
            }
            insert("same-key")
            try {
                insert("same-key")
                fail("a duplicate dedupeKey must be refused by the UNIQUE index")
            } catch (expected: android.database.sqlite.SQLiteConstraintException) {
                // The index did its job.
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `the database ships the ledger migration`() {
        assertTrue(VorvDatabase.ALL_MIGRATIONS.contains(VorvDatabase.MIGRATION_4_5))
    }
}
