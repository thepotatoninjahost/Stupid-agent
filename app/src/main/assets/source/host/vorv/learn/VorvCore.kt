package host.vorv.learn

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Step 8: VorvCore-S — the model whose weights this phone changes.
 *
 * Decoder-only transformer: token embedding (tied with the output projection),
 * RoPE, per block RMSNorm → grouped-query attention → residual, then RMSNorm →
 * SwiGLU → residual. Gradients come from [Tensor]'s reverse pass, so training
 * is real backpropagation on this device and nothing is downloaded.
 *
 * The rungs are ordered by quality and the default is the largest that still
 * trains and generates at a usable rate on the S25; a lower rung is only ever
 * reached by a measurement, never by preference.
 */
class VorvCore(val config: CoreConfig, seed: Long = 1234) {

    data class CoreConfig(
        val vocab: Int,
        val d: Int,
        val layers: Int,
        val heads: Int,
        val kvHeads: Int,
        val ff: Int,
        val ctx: Int,
        val name: String,
    ) {
        init {
            require(d % heads == 0) { "d=$d is not divisible by heads=$heads" }
            require(heads % kvHeads == 0) { "heads=$heads is not divisible by kvHeads=$kvHeads" }
            require((d / heads) % 2 == 0) { "head width ${d / heads} must be even for RoPE" }
        }

        val headDim: Int get() = d / heads
        val kvDim: Int get() = headDim * kvHeads
        val headGroup: Int get() = heads / kvHeads
    }

    /**
     * A rung of the model ladder together with the memory it needs and the
     * sentence that explains why it was chosen.
     */
    data class Rung(val config: CoreConfig, val requiredBytes: Long, val reason: String)

    companion object {
        /** 4M — low-RAM devices and the engine's own tests. */
        val SMALL = CoreConfig(vocab = 8192, d = 256, layers = 4, heads = 4, kvHeads = 2, ff = 683, ctx = 256, name = "SMALL")
        /** 33M — the guaranteed floor on the S25. */
        val MEDIUM = CoreConfig(vocab = 8192, d = 512, layers = 8, heads = 8, kvHeads = 2, ff = 1365, ctx = 512, name = "MEDIUM")
        /** 80M class — the default on the S25. */
        val LARGE = CoreConfig(vocab = 8192, d = 768, layers = 9, heads = 12, kvHeads = 4, ff = 2048, ctx = 768, name = "LARGE")
        /** 150M — explicit opt-in, never chosen automatically. */
        val XLARGE = CoreConfig(vocab = 8192, d = 1024, layers = 10, heads = 16, kvHeads = 4, ff = 2731, ctx = 1024, name = "XLARGE")

        /**
         * Training memory in bytes: weights + Adam moments + gradients, plus
         * activations. Asserted against the device budget by a test so a rung
         * cannot be chosen that the phone cannot hold.
         */
        fun trainingBytes(c: CoreConfig, activationFactor: Double = 3.0): Long {
            val p = paramCount(c).toDouble()
            val base = p * (4.0 + 4.0 + 4.0 + 4.0)
            val activations = c.ctx.toDouble() * c.d * c.layers * activationFactor * 8.0
            return (base + activations).toLong()
        }

        fun randomVec(n: Int, rng: Random, scale: Float): Tensor =
            Tensor(FloatArray(n) { (rng.nextDouble() * 2 - 1).toFloat() * scale }, intArrayOf(n))

        fun randomMat(rows: Int, cols: Int, rng: Random, scale: Float): Tensor =
            Tensor(FloatArray(rows * cols) { (rng.nextDouble() * 2 - 1).toFloat() * scale }, intArrayOf(rows, cols))

        /**
         * The highest rung that fits [budgetBytes], with the measurement that
         * chose it. A demotion always names its number; there is no silent
         * fallback, and no rung is chosen because it is cheaper to run.
         */
        fun chooseTrainingRung(budgetBytes: Long): Rung {
            val skipped = ArrayList<String>()
            for (c in listOf(XLARGE, LARGE, MEDIUM, SMALL)) {
                val need = trainingBytes(c)
                if (need <= budgetBytes) {
                    val why = (skipped + "${c.name} needs $need bytes of $budgetBytes available").joinToString("; ")
                    return Rung(c, need, why)
                }
                skipped += "${c.name} needs $need bytes, over the $budgetBytes allowed"
            }
            val need = trainingBytes(SMALL)
            return Rung(SMALL, need, "SMALL needs $need bytes and only $budgetBytes are allowed: it cannot train here")
        }

        fun paramCount(c: CoreConfig): Long {
            val embed = c.vocab.toLong() * c.d
            val perBlock = 2L * c.d + // two RMSNorm gains
                c.d.toLong() * c.d + // wq
                c.d.toLong() * c.kvDim + // wk
                c.d.toLong() * c.kvDim + // wv
                c.d.toLong() * c.d + // wo
                c.d.toLong() * c.ff * 2 + // gate, up
                c.ff.toLong() * c.d // down
            return embed + c.layers * perBlock + c.d
        }
    }

    private class Block(c: CoreConfig, rng: Random, scale: Float) {
        val n1 = randomVec(c.d, rng, 1f)
        val wq = randomMat(c.d, c.d, rng, scale)
        val wk = randomMat(c.d, c.kvDim, rng, scale)
        val wv = randomMat(c.d, c.kvDim, rng, scale)
        val wo = randomMat(c.d, c.d, rng, scale)
        val n2 = randomVec(c.d, rng, 1f)
        val wGate = randomMat(c.d, c.ff, rng, scale)
        val wUp = randomMat(c.d, c.ff, rng, scale)
        val wDown = randomMat(c.ff, c.d, rng, scale)
        val params: List<Tensor> get() = listOf(n1, wq, wk, wv, wo, n2, wGate, wUp, wDown)
    }

    val tokenEmbed: Tensor = randomMat(config.vocab, config.d, Random(seed), 0.02f)
    private val blocks: List<Block> =
        (0 until config.layers).map { Block(config, Random(seed + it), 0.02f / sqrt(2f * config.layers)) }
    val finalNorm: Tensor = randomVec(config.d, Random(seed + 99), 1f)

    val params: List<Tensor> = buildList {
        add(tokenEmbed)
        blocks.forEach { addAll(it.params) }
        add(finalNorm)
    }

    val parameterCount: Long = params.sumOf { it.size.toLong() }

    /** Logits for the whole sequence: rows × vocab, causal. */
    fun forward(ids: IntArray): Tensor {
        require(ids.isNotEmpty()) { "forward needs at least one token" }
        require(ids.size <= config.ctx) { "sequence of ${ids.size} exceeds context ${config.ctx}" }
        val t = ids.size
        val positions = IntArray(t) { it }
        var x = Tensor.embeddingGather(tokenEmbed, ids)
        for (block in blocks) {
            val a = Tensor.rmsNorm(x, block.n1)
            val q = Tensor.rope(Tensor.matmul(a, block.wq), positions, config.heads)
            val k = Tensor.rope(Tensor.matmul(a, block.wk), positions, config.kvHeads)
            val v = Tensor.matmul(a, block.wv)
            val headOuts = ArrayList<Tensor>(config.heads)
            for (h in 0 until config.heads) {
                val kv = h / config.headGroup
                val qh = Tensor.sliceCols(q, h * config.headDim, config.headDim)
                val kh = Tensor.sliceCols(k, kv * config.headDim, config.headDim)
                val vh = Tensor.sliceCols(v, kv * config.headDim, config.headDim)
                val scores = Tensor.matmul(qh, transpose(kh))
                val probs = causalSoftmax(Tensor.mul(scores, 1f / sqrt(config.headDim.toFloat())))
                headOuts += Tensor.matmul(probs, vh)
            }
            val merged = Tensor.mergeCols(headOuts)
            x = Tensor.add(x, Tensor.matmul(merged, block.wo))

            val m = Tensor.rmsNorm(x, block.n2)
            val gate = Tensor.matmul(m, block.wGate)
            val up = Tensor.matmul(m, block.wUp)
            val hidden = Tensor.swiGlu(gate, up)
            x = Tensor.add(x, Tensor.matmul(hidden, block.wDown))
        }
        val normed = Tensor.rmsNorm(x, finalNorm)
        return Tensor.matmulTransposedB(normed, tokenEmbed)
    }

    /** Mean next-token loss over [ids] (predicting each token from the ones before it). */
    fun loss(ids: IntArray): Tensor {
        require(ids.size >= 2) { "loss needs at least two tokens" }
        val inputs = ids.copyOfRange(0, ids.size - 1)
        val targets = ids.copyOfRange(1, ids.size)
        return Tensor.crossEntropy(forward(inputs), targets)
    }

    /** Loss in nats/token on a fixed sequence, with no graph kept. */
    fun lossValue(ids: IntArray): Float {
        val l = loss(ids)
        return l.data[0]
    }

    /** One optimiser step on [ids]; returns the loss before the update. */
    fun trainStep(ids: IntArray, optimiser: AdamW): Float {
        val l = loss(ids)
        val value = l.data[0]
        l.backward()
        optimiser.step()
        return value
    }

    /**
     * Greedy or sampled continuation. No graph is built for the caller and no
     * weights change: this is inference only.
     */
    fun generate(
        prompt: IntArray,
        count: Int,
        temperature: Float = 0f,
        topK: Int = 0,
        seed: Long = 7,
        stopAt: Int = -1,
    ): IntArray {
        require(prompt.isNotEmpty()) { "generate needs a prompt" }
        val window = ArrayList<Int>(prompt.size + count)
        prompt.forEach { window.add(it) }
        val produced = ArrayList<Int>(count)
        val rng = Random(seed)
        for (step in 0 until count) {
            val start = maxOf(0, window.size - config.ctx)
            val ids = IntArray(window.size - start) { window[start + it] }
            val logits = forward(ids)
            val v = config.vocab
            val row = logits.size - v
            val next = if (temperature <= 0f) {
                var best = 0
                for (j in 1 until v) if (logits.data[row + j] > logits.data[row + best]) best = j
                best
            } else {
                val scaled = FloatArray(v) { logits.data[row + it] / temperature }
                val limit = if (topK in 1 until v) topK else v
                val order = (0 until v).sortedByDescending { scaled[it] }.take(limit)
                var max = Float.NEGATIVE_INFINITY
                for (j in order) max = maxOf(max, scaled[j])
                var sum = 0.0
                val probs = DoubleArray(order.size)
                for ((i, j) in order.withIndex()) {
                    probs[i] = Math.exp((scaled[j] - max).toDouble())
                    sum += probs[i]
                }
                var r = rng.nextDouble() * sum
                var chosen = order.first()
                for ((i, j) in order.withIndex()) {
                    r -= probs[i]
                    if (r <= 0.0) {
                        chosen = j
                        break
                    }
                }
                chosen
            }
            if (next == stopAt) break
            window.add(next)
            produced.add(next)
        }
        return produced.toIntArray()
    }

    /** Transpose of a 2-D tensor, with its own backward. */
    internal fun transpose(x: Tensor): Tensor {
        val m = x.shape[0]
        val n = x.shape[1]
        val out = FloatArray(m * n)
        for (i in 0 until m) for (j in 0 until n) out[j * m + i] = x.data[i * n + j]
        return Tensor.of2d(out, n, m, listOf(x)) { g ->
            val gx = x.ensureGrad()
            for (i in 0 until m) for (j in 0 until n) gx[i * n + j] += g[j * m + i]
        }
    }

    /** Softmax over each row after masking future positions. */
    private fun causalSoftmax(scores: Tensor): Tensor {
        val m = scores.shape[0]
        val n = scores.shape[1]
        val out = FloatArray(m * n)
        for (i in 0 until m) {
            var max = Float.NEGATIVE_INFINITY
            for (j in 0..i) max = maxOf(max, scores.data[i * n + j])
            var sum = 0f
            for (j in 0..i) {
                val e = Math.exp((scores.data[i * n + j] - max).toDouble()).toFloat()
                out[i * n + j] = e
                sum += e
            }
            for (j in 0..i) out[i * n + j] /= sum
        }
        return Tensor.of2d(out, m, n, listOf(scores)) { g ->
            val gs = scores.ensureGrad()
            for (i in 0 until m) {
                var dot = 0f
                for (j in 0..i) dot += g[i * n + j] * out[i * n + j]
                for (j in 0..i) gs[i * n + j] += out[i * n + j] * (g[i * n + j] - dot)
            }
        }
    }

}
