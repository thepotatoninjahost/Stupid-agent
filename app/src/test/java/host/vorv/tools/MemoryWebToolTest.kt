package host.vorv.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import host.vorv.data.VorvDatabase
import host.vorv.data.entity.KnowledgeEdgeEntity
import host.vorv.data.entity.KnowledgeNodeEntity
import host.vorv.data.entity.OperatorProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.8.0 — pins the memory_web tool contract: the agent's window into its
 * own knowledge web. Fail-closed on bad input, honest when empty, real
 * output only, citation-backed per node (the tappable-proof family).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MemoryWebToolTest {

    private lateinit var db: VorvDatabase
    private lateinit var tool: MemoryWebTool
    private val ctx = ToolContext(taskId = "t", iteration = 1, workspaceUri = null, confirm = null)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VorvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tool = MemoryWebTool(db.knowledgeGraphDao())
    }

    @After fun tearDown() {
        db.close()
    }

    private fun act(action: String, extra: Pair<String, String>? = null): ToolResult = runBlocking {
        val input = JsonObject().apply {
            addProperty("action", action)
            extra?.let { addProperty(it.first, it.second) }
        }
        tool.run(input, ctx)
    }

    @Test
    fun `missing action fails closed with guidance`() {
        val r = runBlocking { tool.run(JsonObject(), ctx) }
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("stats"))
    }

    @Test
    fun `unknown action is refused honestly`() {
        val r = act("explode")
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("unknown action"))
    }

    @Test
    fun `stats on an empty web is honest about emptiness`() {
        val r = act("stats")
        assertTrue(r.ok)
        assertTrue(r.output.contains("0 nodes"))
        assertTrue(r.output.contains("Nothing has been learned"))
    }

    @Test
    fun `stats reports counts kinds and the operator`() = runBlocking {
        val dao = db.knowledgeGraphDao()
        dao.upsertNode(KnowledgeNodeEntity(kind = "fact", topic = "porch", content = "Porch light 7:15pm", source = "chat-learned"))
        dao.upsertNode(KnowledgeNodeEntity(kind = "lesson", topic = "build", content = "Task completed", source = "task-outcome"))
        dao.upsertEdge(KnowledgeEdgeEntity(fromId = dao.recentNodes(2).last().id, toId = dao.recentNodes(2).first().id, relation = "related", weight = 0.5f))
        dao.upsertOperator(OperatorProfileEntity(name = "Ashley", learnedFrom = "chat-learned"))
        val r = act("stats")
        assertTrue(r.ok)
        assertTrue(r.output.contains("2 node(s), 1 edge(s)"))
        assertTrue(r.output.contains("fact: 1"))
        assertTrue(r.output.contains("lesson: 1"))
        assertTrue(r.output.contains("Operator: Ashley"))
    }

    @Test
    fun `query matches and shows associations`() = runBlocking {
        val dao = db.knowledgeGraphDao()
        val a = KnowledgeNodeEntity(kind = "fact", topic = "porch-light", content = "The porch light timer is set to 7:15pm", source = "chat-learned", salience = 0.9f)
        dao.upsertNode(a)
        val b = KnowledgeNodeEntity(kind = "fact", topic = "evening-routine", content = "Wind-down with dimmed lights", source = "chat-learned")
        dao.upsertNode(b)
        dao.upsertEdge(KnowledgeEdgeEntity(fromId = a.id, toId = b.id, relation = "part-of", weight = 0.8f))
        val r = act("query", "q" to "porch")
        assertTrue(r.ok)
        assertTrue(r.output.contains("porch-light"))
        assertTrue(r.output.contains("7:15pm"))
        assertTrue(r.output.contains("--part-of"))
        assertEquals("memory", r.citation!!.type)
        assertEquals(a.id, r.citation!!.ref)
    }

    @Test
    fun `query with no match says so honestly`() {
        val r = act("query", "q" to "quantum")
        assertTrue(r.ok)
        assertTrue(r.output.contains("No knowledge-web nodes matched"))
    }

    @Test
    fun `node by short id returns the full node and edges`() = runBlocking {
        val dao = db.knowledgeGraphDao()
        val a = KnowledgeNodeEntity(kind = "preference", topic = "short-answers", content = "I prefer short answers", source = "chat-learned")
        dao.upsertNode(a)
        val r = act("node", "id" to a.id.take(8))
        assertTrue(r.ok)
        assertTrue(r.output.contains("I prefer short answers"))
        assertTrue(r.output.contains("source=chat-learned"))
        assertEquals(a.id, r.citation!!.ref)
    }

    @Test
    fun `node with a bogus id fails closed`() {
        val r = act("node", "id" to "deadbeef")
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("no knowledge-web node"))
    }

    @Test
    fun `query without q fails closed`() {
        val r = act("query")
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("needs a 'q'"))
    }
}
