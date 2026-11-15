package host.vorv.brain

/**
 * A scalar with a gradient, so a network built out of these can be trained by
 * backpropagation on the phone.
 *
 * This is deliberately the smallest thing that is still a real neural network
 * trainer: every operation records what it was made from, and [backward]
 * walks that record and accumulates dLoss/dValue. No library, no native code,
 * no server — the gradients are computed on this device, which is what makes
 * "it learns from my data" true instead of decorative.
 */
class Value(var data: Double, val label: String = "") {

    var grad: Double = 0.0

    /** Children in the expression graph and how strongly the output depends on each. */
    var parents: List<Value> = emptyList()
    var localGrads: List<Double> = emptyList()

    operator fun plus(other: Value): Value {
        val out = Value(data + other.data, "+")
        out.parents = listOf(this, other)
        out.localGrads = listOf(1.0, 1.0)
        return out
    }

    operator fun plus(other: Double): Value = this + Value(other)

    operator fun times(other: Value): Value {
        val out = Value(data * other.data, "*")
        out.parents = listOf(this, other)
        out.localGrads = listOf(other.data, data)
        return out
    }

    operator fun times(other: Double): Value = this * Value(other)

    operator fun minus(other: Value): Value = this + (other * -1.0)

    fun tanh(): Value {
        val t = Math.tanh(data)
        val out = Value(t, "tanh")
        out.parents = listOf(this)
        out.localGrads = listOf(1.0 - t * t)
        return out
    }

    /** exp for the softmax denominator; the max is subtracted by the caller. */
    fun exp(): Value {
        val e = Math.exp(data)
        val out = Value(e, "exp")
        out.parents = listOf(this)
        out.localGrads = listOf(e)
        return out
    }

    fun pow(n: Double): Value {
        val out = Value(Math.pow(data, n), "pow")
        out.parents = listOf(this)
        out.localGrads = listOf(n * Math.pow(data, n - 1))
        return out
    }

    /** Replaces this value's own gradient with [g] mid-graph (used by softmax). */
    fun withGrad(g: Double): Value {
        grad = g
        return this
    }

    /**
     * Depth-first post-order walk, then one pass backwards in reverse order.
     * Values are visited once each, so a shared subexpression is not
     * double-counted.
     */
    fun backward() {
        grad = 1.0
        val order = ArrayList<Value>()
        val seen = HashSet<Value>()
        fun visit(v: Value) {
            if (!seen.add(v)) return
            v.parents.forEach { visit(it) }
            order.add(v)
        }
        visit(this)
        for (v in order.asReversed()) {
            if (v.grad == 0.0 && v !== this) continue
            v.parents.forEachIndexed { i, p -> p.grad += v.grad * v.localGrads[i] }
        }
    }

    override fun toString(): String = "Value(data=$data grad=$grad${if (label.isNotEmpty()) " $label" else ""})"
}

/** Dot product of two rows of doubles, as a Value. */
internal fun dot(a: DoubleArray, b: List<Value>): Value {
    var acc = Value(0.0, "bias")
    for (i in a.indices) acc = acc + b[i] * a[i]
    return acc
}
