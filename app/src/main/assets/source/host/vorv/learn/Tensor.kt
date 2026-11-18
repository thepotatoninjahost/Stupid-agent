package host.vorv.learn

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Step 8: the tensor engine the on-phone brain trains with.
 *
 * `brain/Value.kt` is a scalar graph: correct, and about a thousand times too
 * slow for ten million parameters. This is the same idea over dense
 * `FloatArray`s — every op records its parents and a backward pass, so the
 * gradients are still computed here, on this phone, with no library.
 *
 * Shapes are strict and checked: a wrong shape throws with both shapes named,
 * because a silently broadcast matmul is a silent wrong answer.
 */
class Tensor(val data: FloatArray, val shape: IntArray) {

    var grad: FloatArray? = null
        private set

    internal var parents: Array<Tensor> = emptyArray()
    internal var backwardFn: ((FloatArray) -> Unit)? = null

    val size: Int get() = data.size

    fun ensureGrad(): FloatArray {
        val g = grad ?: FloatArray(data.size).also { grad = it }
        return g
    }

    fun zeroGrad() {
        grad?.fill(0f)
    }

    /** Reverse-mode pass over the graph that produced this tensor. */
    fun backward() {
        val order = ArrayList<Tensor>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Tensor, Boolean>())
        fun visit(t: Tensor) {
            if (!seen.add(t)) return
            for (p in t.parents) visit(p)
            order.add(t)
        }
        visit(this)
        ensureGrad()[0] = 1f
        for (i in order.indices.reversed()) {
            val t = order[i]
            val g = t.grad ?: continue
            t.backwardFn?.invoke(g)
        }
    }

    override fun toString(): String = "Tensor${shape.toList()}"

    companion object {

        fun of(vararg values: Float): Tensor = Tensor(values, intArrayOf(values.size))

        fun zeros(shape: IntArray): Tensor = Tensor(FloatArray(shape.fold(1) { a, b -> a * b }), shape)

        private fun check2d(t: Tensor, name: String): Pair<Int, Int> {
            require(t.shape.size == 2) { "$name must be 2-D, was ${t.shape.toList()}" }
            return t.shape[0] to t.shape[1]
        }

        private fun out(shape: IntArray, parents: List<Tensor>, fn: (FloatArray) -> Unit): Tensor {
            val t = Tensor(FloatArray(shape.fold(1) { a, b -> a * b }), shape)
            t.parents = parents.toTypedArray()
            t.backwardFn = fn
            return t
        }

        /** [m,k] × [k,n] → [m,n]. */
        fun matmul(a: Tensor, b: Tensor): Tensor {
            val (m, k) = check2d(a, "matmul left")
            val (k2, n) = check2d(b, "matmul right")
            require(k == k2) { "matmul shape mismatch: ${a.shape.toList()} × ${b.shape.toList()}" }
            val out = FloatArray(m * n)
            for (i in 0 until m) {
                val aRow = i * k
                val oRow = i * n
                for (p in 0 until k) {
                    val av = a.data[aRow + p]
                    if (av == 0f) continue
                    val bRow = p * n
                    for (j in 0 until n) out[oRow + j] += av * b.data[bRow + j]
                }
            }
            return of2d(out, m, n, listOf(a, b)) { g ->
                val ga = a.ensureGrad()
                val gb = b.ensureGrad()
                for (i in 0 until m) {
                    val aRow = i * k
                    val oRow = i * n
                    for (p in 0 until k) {
                        val av = a.data[aRow + p]
                        var sum = 0f
                        val bRow = p * n
                        for (j in 0 until n) {
                            val gv = g[oRow + j]
                            if (gv == 0f) continue
                            sum += gv * b.data[bRow + j]
                            gb[bRow + j] += av * gv
                        }
                        ga[aRow + p] += sum
                    }
                }
            }
        }

        /** [m,k] × [n,k]ᵀ → [m,n]. Used for the tied output projection. */
        fun matmulTransposedB(a: Tensor, b: Tensor): Tensor {
            val (m, k) = check2d(a, "matmulT left")
            val (n, k2) = check2d(b, "matmulT right")
            require(k == k2) { "matmulT shape mismatch: ${a.shape.toList()} × ${b.shape.toList()}" }
            val out = FloatArray(m * n)
            for (i in 0 until m) {
                val aRow = i * k
                for (j in 0 until n) {
                    val bRow = j * k
                    var sum = 0f
                    for (p in 0 until k) sum += a.data[aRow + p] * b.data[bRow + p]
                    out[i * n + j] = sum
                }
            }
            return of2d(out, m, n, listOf(a, b)) { g ->
                val ga = a.ensureGrad()
                val gb = b.ensureGrad()
                for (i in 0 until m) {
                    val aRow = i * k
                    for (j in 0 until n) {
                        val gv = g[i * n + j]
                        if (gv == 0f) continue
                        val bRow = j * k
                        for (p in 0 until k) {
                            ga[aRow + p] += gv * b.data[bRow + p]
                            gb[bRow + p] += gv * a.data[aRow + p]
                        }
                    }
                }
            }
        }

        fun of2d(data: FloatArray, m: Int, n: Int, parents: List<Tensor> = emptyList(), fn: (FloatArray) -> Unit): Tensor {
            require(data.size == m * n) { "declared ${m}x$n but got ${data.size} values" }
            val t = Tensor(data, intArrayOf(m, n))
            t.parents = parents.toTypedArray()
            t.backwardFn = fn
            return t
        }

        fun add(a: Tensor, b: Tensor): Tensor = elementwise(a, b, 1f)

        fun sub(a: Tensor, b: Tensor): Tensor = elementwise(a, b, -1f)

        private fun elementwise(a: Tensor, b: Tensor, sign: Float): Tensor {
            require(a.shape.contentEquals(b.shape)) {
                "elementwise shape mismatch: ${a.shape.toList()} vs ${b.shape.toList()}"
            }
            val out = FloatArray(a.size) { a.data[it] + sign * b.data[it] }
            val t = Tensor(out, a.shape.copyOf())
            t.parents = arrayOf(a, b)
            t.backwardFn = { g ->
                val ga = a.ensureGrad()
                val gb = b.ensureGrad()
                for (i in g.indices) {
                    ga[i] += g[i]
                    gb[i] += sign * g[i]
                }
            }
            return t
        }

        fun mul(a: Tensor, scalar: Float): Tensor {
            val out = FloatArray(a.size) { a.data[it] * scalar }
            val t = Tensor(out, a.shape.copyOf())
            t.parents = arrayOf(a)
            t.backwardFn = { g ->
                val ga = a.ensureGrad()
                for (i in g.indices) ga[i] += g[i] * scalar
            }
            return t
        }

        fun mul(a: Tensor, b: Tensor): Tensor {
            require(a.shape.contentEquals(b.shape)) { "mul shape mismatch" }
            val out = FloatArray(a.size) { a.data[it] * b.data[it] }
            val t = Tensor(out, a.shape.copyOf())
            t.parents = arrayOf(a, b)
            t.backwardFn = { g ->
                val ga = a.ensureGrad()
                val gb = b.ensureGrad()
                for (i in g.indices) {
                    ga[i] += g[i] * b.data[i]
                    gb[i] += g[i] * a.data[i]
                }
            }
            return t
        }

        /** Adds a per-column bias [n] to every row of [m,n]. */
        fun addBias(a: Tensor, bias: Tensor): Tensor {
            val (m, n) = check2d(a, "addBias input")
            require(bias.size == n) { "bias of ${bias.size} cannot fit ${a.shape.toList()}" }
            val out = FloatArray(a.size) { a.data[it] + bias.data[it % n] }
            val t = of2d(out, m, n, listOf(a, bias)) { g ->
                val ga = a.ensureGrad()
                val gb = bias.ensureGrad()
                for (i in 0 until m) for (j in 0 until n) {
                    val gv = g[i * n + j]
                    ga[i * n + j] += gv
                    gb[j] += gv
                }
            }
            return t
        }

        /** Row-wise RMSNorm over the last axis, scaled by a learned [weight] of length n. */
        fun rmsNorm(a: Tensor, weight: Tensor, eps: Float = 1e-6f): Tensor {
            val (m, n) = check2d(a, "rmsNorm input")
            require(weight.size == n) { "rmsNorm weight of ${weight.size} cannot fit ${a.shape.toList()}" }
            val scale = FloatArray(m)
            val out = FloatArray(m * n)
            for (i in 0 until m) {
                var sum = 0f
                for (j in 0 until n) {
                    val v = a.data[i * n + j]
                    sum += v * v
                }
                val s = 1f / sqrt(sum / n + eps)
                scale[i] = s
                for (j in 0 until n) out[i * n + j] = a.data[i * n + j] * s * weight.data[j]
            }
            return of2d(out, m, n, listOf(a, weight)) { g ->
                val ga = a.ensureGrad()
                val gw = weight.ensureGrad()
                for (i in 0 until m) {
                    val s = scale[i]
                    var dr = 0f
                    for (j in 0 until n) {
                        val dz = g[i * n + j] * weight.data[j]
                        dr += dz * a.data[i * n + j]
                        gw[j] += g[i * n + j] * a.data[i * n + j] * s
                    }
                    val dms = -0.5f * dr * s * s * s / n
                    for (j in 0 until n) {
                        val dz = g[i * n + j] * weight.data[j]
                        ga[i * n + j] += dz * s + dms * 2f * a.data[i * n + j]
                    }
                }
            }
        }

        fun silu(a: Tensor): Tensor {
            val out = FloatArray(a.size)
            val sig = FloatArray(a.size)
            for (i in a.data.indices) {
                val s = 1f / (1f + exp(-a.data[i]))
                sig[i] = s
                out[i] = a.data[i] * s
            }
            val t = Tensor(out, a.shape.copyOf())
            t.parents = arrayOf(a)
            t.backwardFn = { g ->
                val ga = a.ensureGrad()
                for (i in g.indices) ga[i] += g[i] * (sig[i] * (1f + a.data[i] * (1f - sig[i])))
            }
            return t
        }

        /** SwiGLU: silu(gate) × up, elementwise. */
        fun swiGlu(gate: Tensor, up: Tensor): Tensor {
            require(gate.shape.contentEquals(up.shape)) { "swiGlu shape mismatch" }
            val out = FloatArray(gate.size)
            val sil = FloatArray(gate.size)
            val sig = FloatArray(gate.size)
            for (i in gate.data.indices) {
                val s = 1f / (1f + exp(-gate.data[i]))
                sig[i] = s
                sil[i] = gate.data[i] * s
                out[i] = sil[i] * up.data[i]
            }
            val t = Tensor(out, gate.shape.copyOf())
            t.parents = arrayOf(gate, up)
            t.backwardFn = { g ->
                val gg = gate.ensureGrad()
                val gu = up.ensureGrad()
                for (i in g.indices) {
                    gu[i] += g[i] * sil[i]
                    gg[i] += g[i] * up.data[i] * (sig[i] * (1f + gate.data[i] * (1f - sig[i])))
                }
            }
            return t
        }

        fun softmaxRows(a: Tensor): Tensor {
            val (m, n) = check2d(a, "softmax input")
            val out = FloatArray(a.size)
            for (i in 0 until m) {
                var max = Float.NEGATIVE_INFINITY
                for (j in 0 until n) max = maxOf(max, a.data[i * n + j])
                var sum = 0f
                for (j in 0 until n) {
                    val e = exp(a.data[i * n + j] - max)
                    out[i * n + j] = e
                    sum += e
                }
                for (j in 0 until n) out[i * n + j] /= sum
            }
            return of2d(out, m, n, listOf(a)) { g ->
                val ga = a.ensureGrad()
                for (i in 0 until m) {
                    var dot = 0f
                    for (j in 0 until n) dot += g[i * n + j] * out[i * n + j]
                    for (j in 0 until n) {
                        ga[i * n + j] += out[i * n + j] * (g[i * n + j] - dot)
                    }
                }
            }
        }

        /** Mean cross-entropy of [logits] (m × vocab) against [targets] (m). */
        fun crossEntropy(logits: Tensor, targets: IntArray): Tensor {
            val (m, v) = check2d(logits, "crossEntropy logits")
            require(targets.size == m) { "expected $m targets, got ${targets.size}" }
            val loss = FloatArray(1)
            for (i in 0 until m) {
                require(targets[i] in 0 until v) { "target ${targets[i]} outside 0..${v - 1}" }
                var max = Float.NEGATIVE_INFINITY
                for (j in 0 until v) max = maxOf(max, logits.data[i * v + j])
                var sum = 0f
                for (j in 0 until v) sum += exp(logits.data[i * v + j] - max)
                loss[0] -= (logits.data[i * v + targets[i]] - max - kotlin.math.ln(sum))
            }
            loss[0] /= m
            val t = Tensor(loss, intArrayOf(1))
            t.parents = arrayOf(logits)
            t.backwardFn = { g ->
                val gl = logits.ensureGrad()
                val scale = g[0] / m
                for (i in 0 until m) {
                    var max = Float.NEGATIVE_INFINITY
                    for (j in 0 until v) max = maxOf(max, logits.data[i * v + j])
                    var sum = 0f
                    for (j in 0 until v) sum += exp(logits.data[i * v + j] - max)
                    for (j in 0 until v) {
                        val p = exp(logits.data[i * v + j] - max) / sum
                        gl[i * v + j] += scale * (p - if (j == targets[i]) 1f else 0f)
                    }
                }
            }
            return t
        }

        /** Looks up rows [ids] of [table] (vocab × d) → (m × d). */
        fun embeddingGather(table: Tensor, ids: IntArray): Tensor {
            val (v, d) = check2d(table, "embedding table")
            val out = FloatArray(ids.size * d)
            for (i in ids.indices) {
                require(ids[i] in 0 until v) { "id ${ids[i]} outside 0..${v - 1}" }
                System.arraycopy(table.data, ids[i] * d, out, i * d, d)
            }
            return of2d(out, ids.size, d, listOf(table)) { g ->
                val gt = table.ensureGrad()
                for (i in ids.indices) for (j in 0 until d) gt[ids[i] * d + j] += g[i * d + j]
            }
        }

        /** Rotary position embedding over contiguous head pairs; [x] is m × (heads × headDim). */
        fun rope(x: Tensor, positions: IntArray, heads: Int, base: Float = 10_000f): Tensor {
            val (m, width) = check2d(x, "rope input")
            require(width % heads == 0) { "rope width $width is not divisible by $heads heads" }
            val dh = width / heads
            require(dh % 2 == 0) { "rope head width $dh must be even" }
            val out = FloatArray(x.size)
            for (i in 0 until m) {
                for (h in 0 until heads) {
                    val off = i * width + h * dh
                    for (pair in 0 until dh / 2) {
                        val thetaPos = positions[i].toDouble()
                        val freq = Math.pow(base.toDouble(), -2.0 * pair / dh)
                        val ang = thetaPos * freq
                        val c = Math.cos(ang).toFloat()
                        val s = Math.sin(ang).toFloat()
                        val a = x.data[off + 2 * pair]
                        val b = x.data[off + 2 * pair + 1]
                        out[off + 2 * pair] = a * c - b * s
                        out[off + 2 * pair + 1] = a * s + b * c
                    }
                }
            }
            return of2d(out, m, width, listOf(x)) { g ->
                val gx = x.ensureGrad()
                for (i in 0 until m) {
                    for (h in 0 until heads) {
                        val off = i * width + h * dh
                        for (pair in 0 until dh / 2) {
                            val freq = Math.pow(base.toDouble(), -2.0 * pair / dh)
                            val ang = positions[i].toDouble() * freq
                            val c = Math.cos(ang).toFloat()
                            val s = Math.sin(ang).toFloat()
                            val ga = g[off + 2 * pair]
                            val gb = g[off + 2 * pair + 1]
                            gx[off + 2 * pair] += ga * c + gb * s
                            gx[off + 2 * pair + 1] += -ga * s + gb * c
                        }
                    }
                }
            }
        }

        fun sliceCols(x: Tensor, start: Int, count: Int): Tensor {
            val (m, n) = check2d(x, "sliceCols input")
            require(start >= 0 && count > 0 && start + count <= n) { "slice $start+$count outside $n columns" }
            val out = FloatArray(m * count)
            for (i in 0 until m) System.arraycopy(x.data, i * n + start, out, i * count, count)
            return of2d(out, m, count, listOf(x)) { g ->
                val gx = x.ensureGrad()
                for (i in 0 until m) for (j in 0 until count) gx[i * n + start + j] += g[i * count + j]
            }
        }

        fun mergeCols(parts: List<Tensor>): Tensor {
            require(parts.isNotEmpty()) { "mergeCols needs at least one part" }
            val m = parts[0].shape[0]
            val width = parts.sumOf { it.shape[1] }
            for (p in parts) require(p.shape[0] == m) { "mergeCols rows differ" }
            val out = FloatArray(m * width)
            for (i in 0 until m) {
                var at = 0
                for (p in parts) {
                    System.arraycopy(p.data, i * p.shape[1], out, i * width + at, p.shape[1])
                    at += p.shape[1]
                }
            }
            return of2d(out, m, width, parts) { g ->
                for (p in parts) {
                    val gp = p.ensureGrad()
                    for (i in 0 until m) {
                        var at = 0
                        for (q in parts) {
                            if (q === p) {
                                for (j in 0 until p.shape[1]) gp[i * p.shape[1] + j] += g[i * width + at + j]
                            }
                            at += q.shape[1]
                        }
                    }
                }
            }
        }
    }
}

/**
 * AdamW over a fixed parameter list. One instance per training run; the
 * moments are part of the checkpoint so a resumed run continues the same
 * optimiser state instead of restarting it.
 */
class AdamW(
    private val params: List<Tensor>,
    var lr: Float = 3e-4f,
    private val beta1: Float = 0.9f,
    private val beta2: Float = 0.95f,
    private val eps: Float = 1e-8f,
    private val weightDecay: Float = 0.01f,
    private val clipNorm: Float = 1.0f,
) {
    private val m = params.map { FloatArray(it.size) }
    private val v = params.map { FloatArray(it.size) }
    var step: Int = 0
        private set

    /** Global gradient norm before clipping, for the run log. */
    fun gradientNorm(): Float {
        var sum = 0.0
        for (p in params) {
            val g = p.grad ?: continue
            for (x in g) sum += x.toDouble() * x
        }
        return sqrt(sum).toFloat()
    }

    fun step() {
        step++
        val norm = gradientNorm()
        val scale = if (norm > clipNorm && norm > 0f) clipNorm / norm else 1f
        val b1t = 1f - Math.pow(beta1.toDouble(), step.toDouble()).toFloat()
        val b2t = 1f - Math.pow(beta2.toDouble(), step.toDouble()).toFloat()
        for ((i, p) in params.withIndex()) {
            val g = p.grad ?: continue
            val mi = m[i]
            val vi = v[i]
            for (j in g.indices) {
                val gj = g[j] * scale
                mi[j] = beta1 * mi[j] + (1f - beta1) * gj
                vi[j] = beta2 * vi[j] + (1f - beta2) * gj * gj
                val mHat = mi[j] / b1t
                val vHat = vi[j] / b2t
                p.data[j] -= lr * (mHat / (sqrt(vHat) + eps) + weightDecay * p.data[j])
            }
            p.grad?.fill(0f)
        }
    }

    fun state(): Pair<Array<FloatArray>, Array<FloatArray>> = m.toTypedArray() to v.toTypedArray()

    fun restore(state: Pair<Array<FloatArray>, Array<FloatArray>>, step: Int) {
        state.first.forEachIndexed { i, arr -> arr.copyInto(m[i]) }
        state.second.forEachIndexed { i, arr -> arr.copyInto(v[i]) }
        this.step = step
    }
}
