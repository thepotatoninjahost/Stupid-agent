package host.vorv.learn

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import host.vorv.data.VorvDatabase
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
 * C-3 (code review, 2026-11-20): the split column has to be written, not just
 * computed. Without that, every row read back from Room is TRAIN and the
 * "never trained on" promise is only true inside a single call. This test uses
 * the real table so the UPDATE statement is the one the phone runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LearningStoreSplitTest {

    private lateinit var db: VorvDatabase
    private lateinit var store: LearningStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VorvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = LearningStore(db.learningDao())
    }

    @After
    fun tearDown() = db.close()

    private fun record(range: IntRange) = runBlocking {
        for (i in range) {
            store.record(
                LearningLedger.Row(
                    ts = i.toLong(),
                    kind = LearningLedger.Kind.CHAT_ASSISTANT,
                    inputText = "q$i",
                    outputText = "a$i",
                ),
            )
        }
    }

    private fun storedVal(): Set<String> = runBlocking {
        db.learningDao().all()
            .filter { it.split == LearningLedger.Split.VAL.name }
            .map { it.dedupeKey }
            .toSet()
    }

    @Test
    fun `the hold-out set is persisted and only grows`() {
        record(1..20)
        val firstStats = runBlocking { store.stats() }
        assertEquals(2, firstStats.valRows)
        val firstHeldOut = storedVal()
        assertEquals(2, firstHeldOut.size)

        record(21..30)
        val secondStats = runBlocking { store.stats() }
        assertEquals(5, secondStats.valRows)
        val secondHeldOut = storedVal()
        assertTrue(
            "generation-1 validation rows were relabelled TRAIN on disk",
            secondHeldOut.containsAll(firstHeldOut),
        )
        assertEquals(5, secondHeldOut.size)
    }

    @Test
    fun `export keeps the rows it held out, and stats agrees with the table`() {
        record(1..20)
        val jsonl = runBlocking { store.exportJsonl() }
        assertEquals(20, jsonl.lines().count { it.isNotBlank() })
        assertEquals(2, runBlocking { store.stats() }.valRows)
        // Reading twice must not move the boundary.
        val again = runBlocking { store.stats() }
        assertEquals(2, again.valRows)
        assertEquals(storedVal().size, again.valRows)
    }
}
