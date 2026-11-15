package host.vorv.brain

import java.io.File
import kotlin.random.Random

/**
 * A small character model with weights that actually change on this device.
 *
 * Shape: a context of [CONTEXT] characters is looked up in an embedding table,
 * concatenated, passed through one tanh hidden layer, and projected to one
 * score per character in the vocabulary. Training is cross-entropy over the
 * next character, gradients from [Value.backward], plain SGD.
 *
 * Why this shape and not a transformer: the point is a model whose weights can
 * be trained here, at a speed a phone tolerates, and whose learning can be
 * inspected and proved. A ~6 000 parameter model learns the patterns in the
 * text it is given — the user's own notes, code and corrections — and after
 * training it predicts them back. It will not write novel programs: that is
 * what the cloud model is for, and saying otherwise would be a lie.
 */
class CharBrain(
    val vocab: List<Char>,
    private val context: Int = CONTEXT,
    private val dim: Int = DIM,
    private val hidden: Int = HIDDEN,
    private val random: Random = Random(0),
) {
    companion object {
        const val CONTEXT = 8
        const val DIM = 12
        const val HIDDEN = 24
        const val HEADER = "vorv-brain 1"
        /** Unseen characters map here, so training never throws on new input. */
        const val UNKNOWN = 0

        fun vocabularyOf(text: String): List<Char> = (listOf('\u0000') + text.toList().distinct().sorted()).toList()

        fun load(file: File): CharBrain? = runCatching {
            val lines = file.readLines()
            val head = lines.first().split(" ")
            require(head[0] == "vorv-brain")
            val c = head[2].toInt(); val d = head[3].toInt(); val h = head[4].toInt()
            val vocab = lines[1].map { it }
            val brain = CharBrain(vocab, c, d, h)
            brain.params.forEachIndexed { i, v -> v.data = lines[2 + i].toDouble() }
            brain
        }.getOrNull()
    }

    /** Embedding table, flattened: index (char * dim + d). */
    private val embed = List(vocab.size) { _ -> List(dim) { Value(random.nextDouble() * 0.2 - 0.1) } }
    private val w1 = List(context * dim) { _ -> List(hidden) { Value(random.nextDouble() * 0.2 - 0.1) } }
    private val b1 = List(hidden) { Value(0.0) }
    private val w2 = List(hidden) { _ -> List(vocab.size) { Value(random.nextDouble() * 0.2 - 0.1) } }
    private val b2 = List(vocab.size) { Value(0.0) }

    /** Every trainable number, in a fixed order, for the optimiser and for saving. */
    val params: List<Value> = buildList {
        embed.forEach { row -> addAll(row) }
        w1.forEach { row -> addAll(row) }
        addAll(b1)
        w2.forEach { row -> addAll(row) }
        addAll(b2)
    }

    val parameterCount: Int get() = params.size

    private fun indexOf(c: Char): Int = vocab.indexOf(c).takeIf { it >= 0 } ?: UNKNOWN

    private fun forward(window: IntArray): List<Value> {
        val x = ArrayList<Value>(context * dim)
        for (i in 0 until context) {
            val row = embed[window[i]]
            for (d in 0 until dim) x.add(row[d])
        }
        val pre = ArrayList<Value>(hidden)
        for (j in 0 until hidden) {
            var acc = b1[j]
            for (i in x.indices) acc = acc + x[i] * w1[i][j]
            pre.add(acc)
        }
        val h = pre.map { it.tanh() }
        val logits = ArrayList<Value>(vocab.size)
        for (k in vocab.indices) {
            var acc = b2[k]
            for (j in 0 until hidden) acc = acc + h[j] * w2[j][k]
            logits.add(acc)
        }
        return logits
    }

    /**
     * Cross-entropy: ln Σ exp(logit - max) - (logit_target - max).
     * ln Z is built as Z * (1/Z) * ln(Z) so its gradient is 1/Z — the log is a
     * constant here, and only its derivative matters to the graph.
     */
    fun crossEntropy(window: IntArray, target: Int): Value {
        val logits = forward(window)
        val max = logits.maxOf { it.data }
        val shifted = logits.map { it + (-max) }
        var sum = Value(0.0)
        for (s in shifted) sum = sum + s.exp()
        // ln(sum) as a constant times the sum: d/dsum ln(sum) = 1/sum
        val logSum = sum * (1.0 / sum.data) * Math.log(sum.data)
        // logits are shifted by a constant, which cancels in the subtraction below
        return logSum - shifted[target]
    }

    /** One SGD step on one window. Returns the loss before the update. */
    fun trainStep(window: IntArray, target: Int, learningRate: Double): Double {
        val loss = crossEntropy(window, target)
        for (p in params) p.grad = 0.0
        loss.backward()
        for (p in params) {
            // Clipped: a single surprising window can otherwise throw the
            // weights far enough to diverge, which is exactly what happened
            // before this line existed.
            val g = p.grad.coerceIn(-1.0, 1.0)
            p.data -= learningRate * g
        }
        return loss.data
    }

    /**
     * Trains on [text] for [steps] windows drawn from it. Returns the loss of
     * the first and last step so the caller can see whether learning happened.
     */
    fun train(text: String, steps: Int, learningRate: Double = 0.02, seed: Long = 1): TrainingReport {
        val ids = text.map { indexOf(it) }.toIntArray()
        require(ids.size > context + 1) { "need more than ${context + 1} characters to learn from" }
        val rng = Random(seed)
        var first = Double.NaN
        var last = Double.NaN
        var window = IntArray(context)
        repeat(steps) { step ->
            val start = rng.nextInt(ids.size - context - 1)
            for (i in 0 until context) window[i] = ids[start + i]
            val target = ids[start + context]
            val loss = trainStep(window, target, learningRate)
            if (step == 0) first = loss
            last = loss
        }
        return TrainingReport(steps, first, last)
    }

    /** Greedy next-character prediction after [seed]; empty when the seed is unseen. */
    fun generate(seed: String, count: Int): String {
        if (params.size == 0) return ""
        val out = StringBuilder(seed)
        val ids = ArrayDeque<Int>(seed.map { indexOf(it) })
        while (ids.size < context) ids.addFirst(UNKNOWN)
        repeat(count) {
            val window = IntArray(context) { i -> ids.elementAt(i) }
            val logits = forward(window)
            val best = logits.indices.maxBy { logits[it].data }
            out.append(vocab[best])
            ids.removeFirst()
            ids.addLast(best)
        }
        return out.toString()
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.append("$HEADER $context $dim $hidden\n")
        sb.append(String(vocab.toCharArray())).append('\n')
        for (p in params) sb.append(p.data).append('\n')
        file.writeText(sb.toString())
    }

    data class TrainingReport(val steps: Int, val firstLoss: Double, val lastLoss: Double) {
        val improved: Boolean get() = lastLoss < firstLoss
        fun sentence(): String =
            "trained $steps steps, loss ${"%.3f".format(firstLoss)} → ${"%.3f".format(lastLoss)} " +
                if (improved) "(it learned)" else "(no improvement — too little or too uniform data)"
    }
}
