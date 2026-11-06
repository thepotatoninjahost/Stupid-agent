package host.vorv.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests MIGRATION_1_2 directly: builds a v1-shaped tool_calls table by
 * executing the v1 DDL, runs the migration, and asserts the new column
 * exists and existing data survives (audit findings H-5 / M-7).
 *
 * This tests the real migration object used by the Room builder — the same
 * one production wires via addMigrations(). The destructive-fallback
 * behavior is gone, so this migration is the ONLY path from v1 data.
 *
 * `application = Application::class` — Robolectric instantiates the manifest
 * Application class by default, and VorvApplication eagerly initializes the
 * Android Keystore-backed SecretsStore, which Robolectric does not implement
 * (java.security.KeyStoreException). This test only exercises SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {

    private val v1ToolCallsDdl = """
        CREATE TABLE IF NOT EXISTS `tool_calls` (
            `id` TEXT NOT NULL,
            `taskId` TEXT NOT NULL,
            `ts` INTEGER NOT NULL,
            `iteration` INTEGER NOT NULL,
            `toolName` TEXT NOT NULL,
            `inputJson` TEXT NOT NULL,
            `outputJson` TEXT NOT NULL,
            `exitCode` INTEGER NOT NULL,
            `durationMs` INTEGER NOT NULL,
            `errorMessage` TEXT,
            PRIMARY KEY(`id`)
        )
    """.trimIndent()

    private fun openDb(context: Context, name: String, createV1: Boolean): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    if (createV1) db.execSQL(v1ToolCallsDdl)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migration adds outputFilePath column and preserves data`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val db = openDb(context, "migration-test.db", createV1 = true)
        try {
            // Seed a v1 row.
            db.execSQL(
                """INSERT INTO tool_calls (id, taskId, ts, iteration, toolName, inputJson, outputJson, exitCode, durationMs, errorMessage)
                   VALUES ('row-1', 'task-1', 12345, 1, 'file_read', '{}', 'hello', 0, 42, NULL)"""
            )

            // Run the real migration.
            VorvDatabase.MIGRATION_1_2.migrate(db)

            // Column exists.
            val cols = mutableListOf<String>()
            db.query("PRAGMA table_info(tool_calls)").use { c ->
                while (c.moveToNext()) cols.add(c.getString(1))
            }
            assertTrue("outputFilePath column must exist after migration", cols.contains("outputFilePath"))

            // Data survived.
            db.query("SELECT id, outputJson, outputFilePath FROM tool_calls WHERE id = 'row-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("hello", c.getString(1))
                assertTrue("outputFilePath must default to NULL", c.isNull(2))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration is idempotent-safe on an empty table`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val db = openDb(context, "migration-empty.db", createV1 = true)
        try {
            VorvDatabase.MIGRATION_1_2.migrate(db)
            db.query("SELECT COUNT(*) FROM tool_calls").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `database exposes all migrations`() {
        assertTrue(VorvDatabase.ALL_MIGRATIONS.contains(VorvDatabase.MIGRATION_1_2))
    }

    // ------------------------------------------- M1: v2 -> v3 chat_messages

    private val v2ToolCallsDdl = """
        CREATE TABLE IF NOT EXISTS `tool_calls` (
            `id` TEXT NOT NULL,
            `taskId` TEXT NOT NULL,
            `ts` INTEGER NOT NULL,
            `iteration` INTEGER NOT NULL,
            `toolName` TEXT NOT NULL,
            `inputJson` TEXT NOT NULL,
            `outputJson` TEXT NOT NULL,
            `exitCode` INTEGER NOT NULL,
            `durationMs` INTEGER NOT NULL,
            `errorMessage` TEXT,
            `outputFilePath` TEXT,
            PRIMARY KEY(`id`)
        )
    """.trimIndent()

    private fun openV2Db(context: Context, name: String): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(v2ToolCallsDdl)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migration 2_3 creates chat_messages and preserves existing data`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val db = openV2Db(context, "migration-2-3.db")
        try {
            db.execSQL(
                """INSERT INTO tool_calls (id, taskId, ts, iteration, toolName, inputJson, outputJson, exitCode, durationMs, errorMessage, outputFilePath)
                   VALUES ('keep-1', 'task-1', 12345, 1, 'shell', '{}', 'ok', 0, 42, NULL, NULL)"""
            )

            VorvDatabase.MIGRATION_2_3.migrate(db)

            // The new table exists with the exact expected columns.
            val cols = mutableListOf<String>()
            db.query("PRAGMA table_info(chat_messages)").use { c ->
                while (c.moveToNext()) cols.add(c.getString(1))
            }
            assertEquals(listOf("rowId", "id", "ts", "role", "content", "citationsJson"), cols)

            // Both indices exist: ts (lookup) and the UNIQUE id (replace).
            val idx = mutableListOf<String>()
            db.query("PRAGMA index_list(chat_messages)").use { c ->
                while (c.moveToNext()) idx.add(c.getString(1))
            }
            assertTrue("ts index must exist", idx.contains("index_chat_messages_ts"))
            assertTrue("unique id index must exist", idx.contains("index_chat_messages_id"))

            // Pre-existing data survived.
            db.query("SELECT COUNT(*) FROM tool_calls").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }

            // The new table accepts the production insert shape (rowId auto).
            db.execSQL(
                """INSERT INTO chat_messages (id, ts, role, content, citationsJson)
                   VALUES ('m-1', 1, 'user', 'hello', '[]')"""
            )
            db.query("SELECT content FROM chat_messages WHERE id = 'm-1'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("hello", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `database exposes the 2_3 migration`() {
        assertTrue(VorvDatabase.ALL_MIGRATIONS.contains(VorvDatabase.MIGRATION_2_3))
    }
}
