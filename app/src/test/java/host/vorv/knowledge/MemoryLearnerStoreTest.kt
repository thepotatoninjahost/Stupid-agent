package host.vorv.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import host.vorv.data.VorvDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.8.0 — pins the STORE contracts of the learning half (Robolectric +
 * in-memory Room): the operator profile is learned once and kept; a
 * re-learned fact STRENGTHENS its node instead of stacking a copy; new
 * nodes wire `related` edges to genuinely overlapping neighbors; salience
 * follows use. These are the properties that make it a WEB that grows,
 * not a list that piles up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MemoryLearnerStoreTest {

    private lateinit var db: VorvDatabase
    private lateinit var learner: MemoryLearner

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VorvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        learner = MemoryLearner(db.knowledgeGraphDao())
    }

    @After fun tearDown() {
        db.close()
    }

    @Test
    fun `operator identity lands in the profile and stays single`() = runBlocking {
        learner.ingestUserMessage("My name is Ashley")
        learner.ingestUserMessage("Also remember that the porch light timer is 7:15pm")
        val op = db.knowledgeGraphDao().operator()
        assertNotNull(op)
        assertEquals("Ashley", op!!.name)
        // Re-learning the same name updates the single node, never stacks.
        learner.ingestUserMessage("My name is Ashley")
        assertEquals(
            1,
            db.knowledgeGraphDao().countsByKind().first { it.kind == "operator" }.c,
        )
    }

    @Test
    fun `re-learning strengthens instead of stacking`() = runBlocking {
        learner.ingestUserMessage("Remember that the porch light timer is set to 7:15pm")
        val firstId = db.knowledgeGraphDao().search("porch", 5).first().id
        learner.ingestUserMessage("Remember that the porch light timer is set to 7:15pm")
        val facts = db.knowledgeGraphDao().countsByKind().first { it.kind == "fact" }.c
        assertEquals("no duplicate fact node", 1, facts)
        val node = db.knowledgeGraphDao().nodeById(firstId)!!
        assertEquals("second learning must strengthen", 1, node.accessCount)
        assertTrue("salience rises with re-learning", node.salience > 0.9f)
    }

    @Test
    fun `related nodes get wired with edges`() = runBlocking {
        learner.ingestUserMessage("Remember that the porch light timer is set to 7:15pm")
        learner.ingestUserMessage("Remember that the porch light stays off during movies")
        // Both facts share porch/light tokens — an edge must exist.
        assertTrue(
            "overlapping facts must be associated",
            db.knowledgeGraphDao().edgeCount() >= 1,
        )
    }

    @Test
    fun `task outcomes land as lessons`() = runBlocking {
        learner.ingestOutcome("Count the files in my workspace", true, listOf("file_list"), 3)
        val lessons = db.knowledgeGraphDao().countsByKind().first { it.kind == "lesson" }
        assertEquals(1, lessons.c)
        val node = db.knowledgeGraphDao().search("workspace", 5).first()
        assertTrue(node.content.contains("completed after 3 iteration(s)"))
        assertTrue(node.content.contains("file_list"))
    }

    @Test
    fun `activation wakes stored nodes through their edges`() = runBlocking {
        learner.ingestUserMessage("Remember that the porch light timer is set to 7:15pm")
        learner.ingestUserMessage("Remember that the porch light stays off during movies")
        val dao = db.knowledgeGraphDao()
        val nodes = dao.recentNodes(100).map {
            KnowledgeGraph.Node(it.id, it.kind, it.topic, it.content, it.salience, it.accessCount)
        }
        val edges = dao.recentEdges(100).map {
            KnowledgeGraph.Edge(it.fromId, it.toId, it.relation, it.weight)
        }
        // A message about ONE fact wakes it, and via the edge its neighbor.
        val act = KnowledgeGraph.activate("what about the porch light timer", nodes, edges)
        assertTrue(act.nodes.isNotEmpty())
        assertTrue(act.nodes.any { it.content.contains("7:15pm") })
        assertTrue("neighbor wakes through the web", act.nodes.any { it.content.contains("movies") })
    }
}
