package host.vorv.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import host.vorv.data.entity.ChatMessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M1 (code review, 2026-11): chat history is PERSISTENT. The conversation
 * used to be memory-only — every restart lost every message while tool-call
 * logs survived. These tests pin the persistence layer the ViewModel uses:
 * write-as-appended, reload-in-order (rowid tiebreak for same-millisecond
 * messages), citations round-trip, and clearAll for the wipe-everything flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatPersistenceTest {

    private lateinit var db: VorvDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VorvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() {
        db.close()
    }

    @Test
    fun `messages round-trip with content and citations`() = runBlocking {
        val dao = db.chatMessageDao()
        dao.insert(ChatMessageEntity(id = "a", ts = 100, role = "user", content = "read the file"))
        dao.insert(ChatMessageEntity(
            id = "b", ts = 200, role = "assistant", content = "done",
            citationsJson = """[{"type":"file","ref":"content://x","label":"notes.txt"}]""",
        ))

        val loaded = dao.loadRecent()
        assertEquals(2, loaded.size)
        assertEquals("read the file", loaded[0].content)
        assertEquals("user", loaded[0].role)
        assertEquals("done", loaded[1].content)
        assertTrue(loaded[1].citationsJson.contains("notes.txt"))
    }

    @Test
    fun `load order is ts then rowid - same-millisecond messages keep write order`() = runBlocking {
        val dao = db.chatMessageDao()
        val sameTs = 42L
        // A user echo and the busy-hint share a millisecond in real sends.
        dao.insert(ChatMessageEntity(id = "first", ts = sameTs, role = "user", content = "hello?"))
        dao.insert(ChatMessageEntity(id = "second", ts = sameTs, role = "assistant", content = "still working"))

        val loaded = dao.loadRecent()
        assertEquals(listOf("hello?", "still working"), loaded.map { it.content })
    }

    @Test
    fun `recent window is bounded`() = runBlocking {
        val dao = db.chatMessageDao()
        repeat(600) { i ->
            dao.insert(ChatMessageEntity(id = "m$i", ts = i.toLong(), role = "user", content = "m$i"))
        }
        val loaded = dao.loadRecent(limit = 500)
        assertEquals(500, loaded.size)
        // The NEWEST 500 survive, oldest dropped.
        assertEquals("m100", loaded.first().content)
        assertEquals("m599", loaded.last().content)
    }

    @Test
    fun `count and clearAll support the wipe-everything flow`() = runBlocking {
        val dao = db.chatMessageDao()
        dao.insert(ChatMessageEntity(id = "x", ts = 1, role = "user", content = "x"))
        dao.insert(ChatMessageEntity(id = "y", ts = 2, role = "assistant", content = "y"))
        assertEquals(2, dao.count())

        dao.clearAll()
        assertEquals(0, dao.count())
    }

    @Test
    fun `insert with the same id replaces - no duplicates`() = runBlocking {
        val dao = db.chatMessageDao()
        dao.insert(ChatMessageEntity(id = "dup", ts = 1, role = "user", content = "first"))
        dao.insert(ChatMessageEntity(id = "dup", ts = 1, role = "user", content = "replaced"))
        assertEquals(1, dao.count())
        assertEquals("replaced", dao.loadRecent().first().content)
    }
}
