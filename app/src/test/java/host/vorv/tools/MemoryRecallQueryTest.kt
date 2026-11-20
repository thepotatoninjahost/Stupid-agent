package host.vorv.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import host.vorv.data.VorvDatabase
import host.vorv.data.entity.KnowledgeEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #14 (code review, 2026-11-20): memory_recall passed the raw user string into
 * `knowledge_fts MATCH "$query*"`. Everything FTS4 treats as syntax — a quote, a
 * colon, a minus, a bare `*`, the words OR/NOT/NEAR — either threw
 * SQLiteException (silently downgraded to a different LIKE search) or changed
 * the query's meaning. These tests run the REAL Room FTS4 table, so the syntax
 * is SQLite's, not a mock's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MemoryRecallQueryTest {

    private lateinit var db: VorvDatabase
    private lateinit var tool: MemoryRecallTool
    private val ctx = ToolContext(taskId = "t", iteration = 1, workspaceUri = null, confirm = null)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VorvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tool = MemoryRecallTool(db)
        runBlocking {
            db.knowledgeDao().upsert(
                KnowledgeEntry(
                    id = "id-porch-light",
                    topic = "porch-light",
                    content = "The porch light is controlled by the switch by the front door.",
                    source = "user-taught",
                    citationType = "memory",
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun recall(query: String, limit: Int = 5) = runBlocking {
        tool.run(
            JsonObject().apply {
                addProperty("query", query)
                addProperty("limit", limit)
            },
            ctx,
        )
    }

    @Test
    fun `a real word still finds the memory`() {
        val r = recall("porch")
        assertTrue(r.ok)
        assertTrue(r.output, r.output.contains("porch light is controlled"))
    }

    @Test
    fun `quote injection is a literal, not syntax`() {
        for (q in listOf("\"", "porch\" light", "\"\"\"\"", "porch\" OR \"1\"=\"1")) {
            val r = recall(q)
            assertTrue("query <$q> crashed", r.ok)
        }
    }

    @Test
    fun `punctuation heavy queries are safe`() {
        for (q in listOf("C++", "a:b", "-porch", "*", "porch*", "(porch", "porch)", "NEAR/2(porch)", "^porch")) {
            val r = recall(q)
            assertTrue("query <$q> crashed", r.ok)
        }
    }

    @Test
    fun `operator words are quoted so they cannot change the query`() {
        // Unquoted, OR would make this match the second branch too; quoted, it is
        // an ordinary (absent) word and the AND keeps the result empty.
        val r = recall("porch OR light")
        assertTrue(r.ok)
        assertTrue(r.output, r.output.contains("No memories matched"))
    }

    @Test
    fun `nothing searchable left is empty, not an error`() {
        val r = recall("\"\" -- ** ..")
        assertTrue(r.ok)
        assertTrue(r.output, r.output.contains("No memories matched"))
    }

    @Test
    fun `a pasted paragraph is capped`() {
        val words = (1..200).joinToString(" ") { "w$it" }
        val r = recall(words)
        assertTrue(r.ok)
        assertEquals(8, ftsQuery(words)!!.split(" ").size)
    }

    @Test
    fun `no matches is still ok and names the query`() {
        val r = recall("nothing-like-this")
        assertTrue(r.ok)
        assertTrue(r.output, r.output.contains("nothing-like-this"))
    }

    @Test
    fun `ftsQuery makes every word a quoted literal`() {
        assertEquals("\"porch\"* \"light\"*", ftsQuery("porch light"))
        assertEquals("\"C\"* \"plus\"*", ftsQuery("C++ plus"))
        assertNull(ftsQuery("?!?"))
        assertEquals("\"OR\"*", ftsQuery("OR"))
    }
}
