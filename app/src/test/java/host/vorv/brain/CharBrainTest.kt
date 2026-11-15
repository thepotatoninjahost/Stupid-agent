package host.vorv.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * These are the tests that make the claim "it learns" checkable. If the loss
 * does not fall, or the weights are not really written and read back, the
 * claim is decoration and these fail.
 */
class CharBrainTest {

    private val text = "vorv writes the code and the code runs. " +
        "vorv writes the code and the code runs. " +
        "vorv fixes the code and the code runs. "

    @Test
    fun `loss falls when it trains on repeated text`() {
        val brain = CharBrain(CharBrain.vocabularyOf(text))
        val report = brain.train(text, steps = 800, learningRate = 0.05, seed = 7)
        println(report.sentence() + " params=" + brain.parameterCount)
        assertTrue("loss went ${report.firstLoss} -> ${report.lastLoss}", report.improved)
        assertTrue("loss must fall meaningfully, not by noise: ${report.firstLoss} -> ${report.lastLoss}",
            report.lastLoss < report.firstLoss * 0.75)
    }

    @Test
    fun `weights are real numbers that change`() {
        val brain = CharBrain(CharBrain.vocabularyOf(text))
        val before = brain.params.map { it.data }.toDoubleArray()
        brain.train(text, steps = 60, seed = 3)
        val after = brain.params.map { it.data }
        val changed = before.indices.count { before[it] != after[it] }
        assertTrue("at least a third of the ${before.size} weights should move, moved $changed", changed > before.size / 3)
        assertTrue("no weight may become NaN", after.none { it.isNaN() || it.isInfinite() })
    }

    @Test
    fun `saved weights reload and predict identically`() {
        val dir = createTempDirectory("brain").toFile()
        val brain = CharBrain(CharBrain.vocabularyOf(text))
        brain.train(text, steps = 40, seed = 5)
        val file = File(dir, "brain.txt")
        brain.save(file)
        val loaded = CharBrain.load(file)
        assertTrue("the file must load", loaded != null)
        assertEquals(brain.params.size, loaded!!.params.size)
        assertEquals(brain.generate("vor", 12), loaded.generate("vor", 12))
    }

    @Test
    fun `generation stays inside the vocabulary it learned`() {
        val brain = CharBrain(CharBrain.vocabularyOf(text))
        brain.train(text, steps = 80, seed = 9)
        val out = brain.generate("vorv ", 40)
        assertTrue("nothing generated", out.length > 5)
        assertTrue("produced a character it never learned: '$out'", out.all { it in brain.vocab })
    }

    @Test
    fun `an unknown character is mapped to padding, never a crash`() {
        val brain = CharBrain(CharBrain.vocabularyOf("abcabcabc"))
        brain.train("abcabcabcabcabcabcabcabc", steps = 10)
        assertTrue(brain.generate("zzz", 5).isNotEmpty())
    }
}
