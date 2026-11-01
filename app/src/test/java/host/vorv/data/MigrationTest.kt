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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
}
