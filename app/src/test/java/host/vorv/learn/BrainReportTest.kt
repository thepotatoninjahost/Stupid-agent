package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BrainReportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gen(id: Int, loss: Double?, accepted: Boolean, decision: String = "") =
        GenerationStore.Generation(
            id = id, createdAt = id.toLong(), origin = "phone", artifactPath = "gen-$id/weights.bin",
            artifactSha256 = "", sizeBytes = 0, valLoss = loss, accepted = accepted,
            decision = decision, corpusSha256 = "", config = "TINY",
        )

    private fun storeWith(vararg gs: GenerationStore.Generation): File {
        val dir = tmp.newFolder("brain")
        val store = GenerationStore(dir)
        for (g in gs) {
            // a real file with the recorded hash, so promote/verify behave
            val f = File(dir, g.artifactPath)
            f.parentFile.mkdirs()
            f.writeText("weights ${g.id}")
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            store.add(g.copy(artifactSha256 = sha), accept = g.accepted)
        }
        return dir
    }

    @Test
    fun anEmptyBrainSaysSoInsteadOfLookingHealthy() {
        val view = BrainReport.build(tmp.newFolder("empty"), emptyList())
        assertEquals("No generation has been trained yet.", view.headline)
        assertNull(view.servingId)
        assertTrue(BrainReport.chartLine(view.chart).isEmpty())
        assertTrue(view.rollbackNote.contains("Nothing is serving"))
    }

    @Test
    fun onlyAcceptedGenerationsEnterTheChart() {
        val dir = storeWith(gen(1, 1.4, true), gen(2, 1.6, false, "Not promoted: validation loss was 1.400, now 1.600"))
        val view = BrainReport.build(dir, emptyList())
        assertEquals(listOf(1 to 1.4), view.chart)
        assertEquals(2, view.generations.size)
        assertEquals(1, view.servingId)
        val refused = view.generations.first { it.id == 2 }
        assertTrue(BrainReport.generationLine(refused).contains("refused"))
        assertTrue(BrainReport.generationLine(refused).contains("loss 1.600"))
        assertTrue("a refusal keeps its reason", refused.decision.contains("Not promoted"))
    }

    @Test
    fun theServingGenerationIsNamed() {
        val dir = storeWith(gen(1, 1.4, true), gen(2, 1.2, true))
        val view = BrainReport.build(dir, emptyList())
        assertEquals(2, view.servingId)
        assertEquals(1, view.rollbackTarget)
        assertTrue(view.rollbackNote.contains("goes back to generation 1"))
        assertTrue(BrainReport.generationLine(view.generations.first()).contains("serving now"))
    }

    @Test
    fun theChartSaysWhichWayLossWent() {
        val dir = storeWith(gen(1, 2.0, true), gen(2, 1.5, true), gen(3, 1.2, true))
        val line = BrainReport.chartLine(BrainReport.build(dir, emptyList()).chart)
        assertTrue(line, line.contains("2.000 → 1.200"))
        assertTrue(line, line.contains("down"))
    }

    @Test
    fun anOlderOnlyBrainHasNothingToRollBackTo() {
        val dir = storeWith(gen(1, 1.4, true))
        val view = BrainReport.build(dir, emptyList())
        assertNull(view.rollbackTarget)
        assertTrue(view.rollbackNote.contains("nothing to go back to"))
    }

    @Test
    fun runsAreNewestFirstAndKeepTheirSentence() {
        val dir = storeWith(gen(1, 1.4, true))
        val runs = listOf(
            BrainReport.Run(1, 100, "promoted", 1, "LARGE", 40, 1.6, 1.4, 12.0, "Generation 1 trained."),
            BrainReport.Run(2, 200, "stopped", 0, "", 0, null, null, null, "The ledger is empty."),
        )
        val view = BrainReport.build(dir, runs)
        assertEquals(2L, view.runs.first().id)
        assertTrue(view.runs.first().message.contains("ledger is empty"))
    }
}
