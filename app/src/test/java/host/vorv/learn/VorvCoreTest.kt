package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VorvCoreTest {

    private val tiny = VorvCore.CoreConfig(vocab = 16, d = 16, layers = 2, heads = 2, kvHeads = 1, ff = 32, ctx = 16, name = "TINY")
    private val ids = intArrayOf(3, 9, 1, 4, 3, 9, 1, 4)

    @Test
    fun declaredParamCountsMatchTheRungs() {
        assertEquals(VorvCore.paramCount(VorvCore.SMALL), VorvCore(VorvCore.SMALL).parameterCount)
        assertEquals(VorvCore.paramCount(VorvCore.MEDIUM), VorvCore(VorvCore.MEDIUM).parameterCount)
    }

    @Test
    fun largeRungIsAbout80MillionParameters() {
        val count = VorvCore.paramCount(VorvCore.LARGE)
        assertTrue("LARGE is $count params", count in 60_000_000..95_000_000)
        assertTrue("XLARGE must be bigger", VorvCore.paramCount(VorvCore.XLARGE) > count)
    }

    @Test
    fun trainingMemoryOfEveryRungFitsThePhone() {
        // Weights, gradients and both Adam moments are fp32: 16 bytes per
        // parameter. The S25 has 12 GB of RAM, so this is the ceiling the
        // trainer is allowed to ask for on the default rung.
        val large = VorvCore.trainingBytes(VorvCore.LARGE)
        assertTrue("LARGE training needs $large bytes", large < 1_500L * 1024 * 1024)
        assertTrue(VorvCore.trainingBytes(VorvCore.MEDIUM) < large)
        assertTrue(VorvCore.trainingBytes(VorvCore.SMALL) < VorvCore.trainingBytes(VorvCore.MEDIUM))

        val roomy = VorvCore.chooseTrainingRung(2L * 1024 * 1024 * 1024)
        assertEquals(VorvCore.LARGE, roomy.config)
        assertTrue(roomy.reason.contains("LARGE"))
        val tight = VorvCore.chooseTrainingRung(700L * 1024 * 1024)
        assertEquals(VorvCore.MEDIUM, tight.config)
        assertTrue("a demotion must name its measurement: ${tight.reason}", tight.reason.contains("LARGE needs"))
        val tinyBudget = VorvCore.chooseTrainingRung(64L * 1024 * 1024)
        assertEquals(VorvCore.SMALL, tinyBudget.config)
        assertTrue(tinyBudget.reason.contains("cannot train here"))
    }

    @Test
    fun gradientsMatchFiniteDifferencesThroughTheWholeBlock() {
        val core = VorvCore(tiny, seed = 42)
        val loss = core.loss(ids)
        loss.backward()
        val analytic = core.params.map { it.grad?.copyOf() ?: FloatArray(it.size) }
        core.params.forEach { it.grad?.fill(0f) }
        val probes = listOf(0, 1, core.params.size - 1)
        var worst = 0.0
        for (pi in probes) {
            val p = core.params[pi]
            for (j in 0 until minOf(3, p.size)) {
                val original = p.data[j]
                p.data[j] = original + 1e-2f
                val up = core.lossValue(ids)
                p.data[j] = original - 1e-2f
                val down = core.lossValue(ids)
                p.data[j] = original
                val numeric = ((up - down) / 2e-2).toDouble()
                val back = analytic[pi][j].toDouble()
                worst = maxOf(worst, abs(numeric - back) / maxOf(1e-2, abs(numeric) + abs(back)))
            }
        }
        assertTrue("worst relative gradient error was $worst", worst < 0.05)
    }

    @Test
    fun aTinyModelCanOverfitAShortSequence() {
        val core = VorvCore(tiny, seed = 7)
        val opt = AdamW(core.params, lr = 0.01f)
        val before = core.lossValue(ids)
        repeat(80) { core.trainStep(ids, opt) }
        val after = core.lossValue(ids)
        assertTrue("loss did not fall: $before -> $after", after < 0.2f)
        assertTrue("loss must be finite", after.isFinite())
        val greedy = core.generate(intArrayOf(3), 3)
        assertEquals(listOf(9, 1, 4), greedy.toList())
    }

    @Test
    fun theFutureCannotChangeThePast() {
        val core = VorvCore(tiny, seed = 5)
        val a = core.forward(intArrayOf(3, 9, 1, 4))
        val b = core.forward(intArrayOf(3, 9, 7, 12))
        for (row in 0 until 2) {
            for (j in 0 until tiny.vocab) {
                assertEquals("row $row must not see later tokens", a.data[row * tiny.vocab + j], b.data[row * tiny.vocab + j], 1e-5f)
            }
        }
    }

    @Test
    fun theSameSeedGivesTheSameModelAndAnotherSeedDoesNot() {
        val a = VorvCore(tiny, seed = 11)
        val b = VorvCore(tiny, seed = 11)
        val c = VorvCore(tiny, seed = 12)
        assertEquals(a.lossValue(ids), b.lossValue(ids), 0f)
        assertTrue("a different seed must give a different model", abs(a.lossValue(ids) - c.lossValue(ids)) > 1e-4f)
    }

    @Test
    fun generationStopsAtTheStopToken() {
        val core = VorvCore(tiny, seed = 3)
        val out = core.generate(intArrayOf(1, 2), 10, stopAt = -1)
        assertEquals(10, out.size)
        val stopped = core.generate(intArrayOf(1, 2), 10, stopAt = out[0])
        assertEquals(0, stopped.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTooLongSequenceIsRefused() {
        VorvCore(tiny, seed = 1).forward(IntArray(tiny.ctx + 1) { it % tiny.vocab })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBadConfigIsRefused() {
        VorvCore.CoreConfig(vocab = 16, d = 10, layers = 1, heads = 4, kvHeads = 2, ff = 8, ctx = 8, name = "BAD")
    }
}
