package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrainerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tiny = VorvCore.CoreConfig(vocab = 320, d = 16, layers = 1, heads = 2, kvHeads = 1, ff = 32, ctx = 12, name = "TINY")

    private fun rows(n: Int = 14): List<LearningLedger.Row> = (0 until n).map { i ->
        LearningLedger.Row(
            ts = 1_700_000_000_000 + i,
            kind = LearningLedger.Kind.CHAT_ASSISTANT,
            sessionId = "s$i",
            inputText = "the owner asks question number $i about the build",
            outputText = "the answer explains step $i and names the test that proves it",
        )
    }

    private fun settings(steps: Int = 30) = Trainer.Settings(
        maxSteps = steps,
        lr = 5e-3f,
        batchSequences = 2,
        evalEvery = 10,
        earlyStopPatience = 3,
        seed = 11,
        tokenizerTarget = 300,
        rungOverride = tiny,
    )

    @Test
    fun anEmptyLedgerIsRefusedAndSavesNothing() {
        val dir = tmp.newFolder("brain")
        val out = Trainer(dir).run(emptyList(), settings())
        assertFalse(out.ran)
        assertTrue(out.message.contains("ledger is empty"))
        assertFalse("nothing may be written for a refused run", File(dir, "generations.json").exists())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun aRealRunWritesAGenerationAndTheGateAcceptsTheFirstOne() {
        val dir = tmp.newFolder("brain")
        val out = Trainer(dir).run(rows(), settings())
        assertTrue(out.message, out.ran)
        assertEquals(1, out.generation)
        assertTrue(out.promoted)
        assertTrue("validation loss must be measured: ${out.valLoss}", out.valLoss.isFinite())

        val store = GenerationStore(dir)
        val champion = store.champion()
        assertNotNull("a promoted first generation must be the champion", champion)
        assertEquals(1, champion!!.id)
        assertEquals(1, WeightsStore.active(dir))
        assertTrue(File(dir, "tokenizer.txt").isFile)
        assertTrue(File(dir, "gen-1/eval.json").isFile)

        // the artifact on disk is the model the trainer measured, and it loads
        val loaded = WeightsStore.load(dir, 1)
        assertEquals(out.config!!.vocab, loaded.config.vocab)
        assertTrue(out.steps > 0)
        assertTrue(out.seconds >= 0.0)
    }

    @Test
    fun trainingMakesTheModelBetterThanItsFirstStep() {
        val dir = tmp.newFolder("brain")
        val out = Trainer(dir).run(rows(20), settings(steps = 60))
        assertTrue(out.message, out.ran)
        assertTrue("train loss must fall: ${out.trainLoss}", out.trainLoss < 4.0)
    }

    @Test
    fun aSecondRunThatIsNotBetterIsKeptButNotPromoted() {
        val dir = tmp.newFolder("brain")
        val first = Trainer(dir).run(rows(), settings(steps = 40))
        assertTrue(first.promoted)

        // A champion that already scores perfectly cannot be beaten by a tiny
        // model trained for ten steps, so this candidate must be refused.
        val championDir = File(dir, "gen-1")
        val perfect = buildString {
            append("{\"suiteVersion\": ${EvalSuite.VERSION}, \"valLoss\": 0.0001, ")
            append("\"passed\": {\"INSTRUCTION\": 99, \"TOOL_CHOICE\": 99, \"PROJECT\": 99, \"REFUSAL\": 99, \"STILL_KNOWS\": 99}, ")
            append("\"total\": {\"INSTRUCTION\": 99, \"TOOL_CHOICE\": 99, \"PROJECT\": 99, \"REFUSAL\": 99, \"STILL_KNOWS\": 99}}\n")
        }
        File(championDir, "eval.json").writeText(perfect)

        val second = Trainer(dir).run(rows(), settings(steps = 10))
        assertTrue(second.message, second.ran)
        assertEquals(2, second.generation)
        assertFalse("a candidate that does not win must not be promoted", second.promoted)
        assertEquals("generation 1 stays the champion", 1, GenerationStore(dir).champion()!!.id)
        assertEquals(1, WeightsStore.active(dir))
        assertTrue(File(dir, "gen-2/eval.json").isFile)
        assertEquals(2, GenerationStore(dir).generations().size)
    }

    @Test
    fun oneTapRollsBackToTheOlderGeneration() {
        val dir = tmp.newFolder("brain")
        assertTrue(Trainer(dir).run(rows(), settings(steps = 20)).promoted)
        // force a second accepted generation through the store so rollback has a target
        val core = WeightsStore.load(dir, 1)
        val saved = WeightsStore.save(dir, core, WeightsStore.Meta(2, 1L, 1.0, 0.5, 10, 10, 1))
        val store = GenerationStore(dir)
        store.add(
            GenerationStore.Generation(
                id = 2, createdAt = 1L, origin = "phone", artifactPath = "gen-2/weights.bin",
                artifactSha256 = saved.sha256, sizeBytes = saved.bytes, valLoss = 0.5,
                accepted = true, decision = "test", corpusSha256 = "", config = "TINY",
            ),
            accept = true,
        )
        assertEquals(2, store.champion()!!.id)
        val back = store.rollback()
        assertTrue(back is GenerationStore.Result.Ok)
        assertEquals(1, store.champion()!!.id)
    }

    @Test
    fun aStepTooSlowForThePhoneStopsTheRunAndSavesNothing() {
        val dir = tmp.newFolder("brain")
        val out = Trainer(dir).run(rows(), settings(steps = 200).copy(maxSecondsPerStep = 1e-9))
        assertFalse(out.ran)
        assertTrue(out.message, out.message.contains("per step"))
        assertNull(GenerationStore(dir).champion())
        assertTrue("no half-trained generation may be left behind", !File(dir, "gen-1").exists())
    }

    @Test
    fun tooLittleTextIsRefused() {
        val dir = tmp.newFolder("brain")
        val out = Trainer(dir).run(rows(1), settings())
        assertFalse(out.ran)
        assertTrue(out.message, out.message.contains("too little"))
    }

    @Test
    fun theReportNamesWhatTheBrainKnows() {
        val dir = tmp.newFolder("brain")
        val out = Trainer(dir).run(rows(), settings(steps = 20))
        val text = Trainer(dir).describeScores(out.eval!!)
        assertTrue(text, text.contains("language"))
        assertTrue(text, text.contains("perplexity"))
        assertEquals(Math.exp(0.5), Trainer.perplexityOf(0.5), 1e-9)
    }
}
