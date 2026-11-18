package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class TensorTest {

    private fun gradCheck(params: List<Tensor>, tol: Double = 0.02, build: () -> Tensor) {
        val loss = build()
        loss.backward()
        val analytic = params.map { p -> p.grad?.copyOf() ?: FloatArray(p.size) }
        params.forEach { it.grad?.fill(0f) }
        var checked = 0
        for ((pi, p) in params.withIndex()) {
            for (j in 0 until minOf(4, p.size)) {
                val original = p.data[j]
                val eps = 1e-2f
                p.data[j] = original + eps
                val up = build().data[0]
                p.data[j] = original - eps
                val down = build().data[0]
                p.data[j] = original
                val numeric = ((up - down) / (2 * eps)).toDouble()
                val back = analytic[pi][j].toDouble()
                val err = abs(numeric - back) / maxOf(1e-2, abs(numeric) + abs(back))
                assertTrue("gradient $pi[$j] numeric=$numeric analytic=$back", err < tol)
                checked++
            }
        }
        assertTrue("nothing was checked", checked > 0)
    }

    private fun randomTensor(rows: Int, cols: Int, scale: Float = 0.5f, seed: Int = 3): Tensor {
        val rng = Random(seed)
        return Tensor(FloatArray(rows * cols) { (rng.nextDouble() * 2 - 1).toFloat() * scale }, intArrayOf(rows, cols))
    }

    @Test
    fun matmulGradientsMatchFiniteDifferences() {
        val a = randomTensor(3, 4)
        val b = randomTensor(4, 2)
        gradCheck(listOf(a, b)) { Tensor.crossEntropy(Tensor.matmul(a, b), intArrayOf(0, 1, 1)) }
    }

    @Test
    fun tiedOutputProjectionGradientsMatch() {
        val x = randomTensor(2, 4)
        val table = randomTensor(5, 4)
        gradCheck(listOf(x, table)) { Tensor.crossEntropy(Tensor.matmulTransposedB(x, table), intArrayOf(4, 2)) }
    }

    @Test
    fun rmsNormGradientsMatchFiniteDifferences() {
        val x = randomTensor(3, 6)
        val w = Tensor(FloatArray(6) { 1f + it * 0.1f }, intArrayOf(6))
        gradCheck(listOf(x, w)) {
            val normed = Tensor.rmsNorm(x, w)
            Tensor.crossEntropy(Tensor.matmul(normed, randomFixed(6, 3)), intArrayOf(0, 2, 1))
        }
    }

    @Test
    fun siluAndSwiGluGradientsMatchFiniteDifferences() {
        val gate = randomTensor(2, 3)
        val up = randomTensor(2, 3, seed = 9)
        val w = randomFixed(3, 2)
        gradCheck(listOf(gate, up)) {
            Tensor.crossEntropy(Tensor.matmul(Tensor.swiGlu(gate, up), w), intArrayOf(1, 0))
        }
    }

    @Test
    fun softmaxAndCrossEntropyGradientsMatchFiniteDifferences() {
        val logits = randomTensor(2, 5)
        gradCheck(listOf(logits)) { Tensor.crossEntropy(logits, intArrayOf(3, 1)) }
        val probs = Tensor.softmaxRows(logits)
        var sum = 0f
        for (i in 0 until 2) {
            var rowSum = 0f
            for (j in 0 until 5) rowSum += probs.data[i * 5 + j]
            assertEquals(1f, rowSum, 1e-5f)
            sum += rowSum
        }
        assertEquals(2f, sum, 1e-5f)
    }

    @Test
    fun uniformLogitsGiveLogOfVocab() {
        val logits = Tensor(FloatArray(12), intArrayOf(3, 4))
        val loss = Tensor.crossEntropy(logits, intArrayOf(0, 1, 2))
        assertEquals(kotlin.math.ln(4f).toDouble(), loss.data[0].toDouble(), 1e-5)
    }

    @Test
    fun embeddingGatherSumsGradientsFromEveryUse() {
        val table = randomTensor(6, 3)
        val ids = intArrayOf(1, 1, 4)
        val gathered = Tensor.embeddingGather(table, ids)
        val w = randomFixed(3, 2)
        val loss = Tensor.crossEntropy(Tensor.matmul(gathered, w), intArrayOf(0, 1, 0))
        loss.backward()
        val row0 = (0 until 3).sumOf { abs(table.grad!![1 * 3 + it]).toDouble() }
        val untouched = (0 until 3).sumOf { abs(table.grad!![2 * 3 + it]).toDouble() }
        assertTrue("the repeated row must receive gradient", row0 > 0.0)
        assertEquals(0.0, untouched, 1e-9)
    }

    @Test
    fun ropeGradientsMatchFiniteDifferences() {
        val x = randomTensor(3, 4)
        val w = randomFixed(4, 3)
        gradCheck(listOf(x)) {
            val rotated = Tensor.rope(x, intArrayOf(0, 1, 2), heads = 2)
            Tensor.crossEntropy(Tensor.matmul(rotated, w), intArrayOf(1, 2, 0))
        }
    }

    @Test
    fun ropeAtPositionZeroChangesNothing() {
        val x = randomTensor(2, 4)
        val rotated = Tensor.rope(x, intArrayOf(0, 0), heads = 2)
        for (i in x.data.indices) assertEquals(x.data[i], rotated.data[i], 1e-6f)
    }

    @Test
    fun sliceAndMergeColumnsRoundTripWithGradients() {
        val x = randomTensor(3, 6)
        val left = Tensor.sliceCols(x, 0, 3)
        val right = Tensor.sliceCols(x, 3, 3)
        val merged = Tensor.mergeCols(listOf(left, right))
        for (i in x.data.indices) assertEquals(x.data[i], merged.data[i], 1e-6f)
        val w = randomFixed(6, 2)
        gradCheck(listOf(x)) {
            Tensor.crossEntropy(Tensor.matmul(Tensor.mergeCols(listOf(Tensor.sliceCols(x, 0, 3), Tensor.sliceCols(x, 3, 3))), w), intArrayOf(0, 1, 1))
        }
    }

    @Test
    fun addBiasBroadcastsAndKeepsItsGradient() {
        val a = randomTensor(4, 3)
        val bias = Tensor(FloatArray(3) { 0.1f * it }, intArrayOf(3))
        gradCheck(listOf(a, bias)) {
            Tensor.crossEntropy(Tensor.addBias(a, bias), intArrayOf(0, 1, 2, 1))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedMatmulThrows() {
        Tensor.matmul(randomTensor(2, 3), randomTensor(4, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeTargetThrows() {
        Tensor.crossEntropy(randomTensor(2, 3), intArrayOf(0, 7))
    }

    @Test
    fun adamWWalksDownhillAndClearsGradients() {
        val p = Tensor(floatArrayOf(3f, -2f), intArrayOf(2))
        val target = floatArrayOf(1f, 1f)
        val opt = AdamW(listOf(p), lr = 0.1f)
        fun loss(): Tensor {
            var sum = Tensor.mul(p, 1f)
            sum = Tensor.sub(sum, Tensor(target, intArrayOf(2)))
            sum = Tensor.mul(sum, sum)
            val data = FloatArray(1) { (sum.data[0] + sum.data[1]) / 2f }
            val t = Tensor(data, intArrayOf(1))
            t.parents = arrayOf(sum)
            t.backwardFn = { g ->
                val gs = sum.ensureGrad()
                gs[0] += g[0] / 2f
                gs[1] += g[0] / 2f
            }
            return t
        }
        val before = loss().data[0]
        repeat(200) {
            loss().backward()
            opt.step()
        }
        val after = loss().data[0]
        assertTrue("loss must fall: $before -> $after", after < before / 100f)
        assertEquals(0f, p.grad!![0], 0f)
    }

    private fun randomFixed(rows: Int, cols: Int): Tensor {
        val rng = Random(11)
        return Tensor(FloatArray(rows * cols) { (rng.nextDouble() * 2 - 1).toFloat() * 0.3f }, intArrayOf(rows, cols))
    }
}
