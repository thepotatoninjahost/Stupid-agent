package host.vorv.learn

/**
 * Step 9: the promotion gate. The only way a candidate generation may become
 * the champion is that it is not worse than the champion anywhere.
 *
 * The four clauses the plan fixed, in code:
 *   1. validation loss must go down (never up);
 *   2. instruction, tool-choice, project and refusal probes must not drop;
 *   3. the "still knows" probes may not drop by more than 5% — a model that
 *      got better at today's work by forgetting last week's is a downgrade;
 *   4. a candidate with no comparable evidence is refused, not assumed good.
 *
 * This class is pure: no model, no files, no coroutines, so the decision can be
 * tested on a laptop and replayed from a stored report.
 */
object PromotionGate {

    const val STILL_KNOWS_TOLERANCE = 0.05

    enum class Axis(val label: String) {
        VAL_LOSS("validation loss"),
        INSTRUCTION("instruction probes"),
        TOOL_CHOICE("tool-choice probes"),
        PROJECT("project probes"),
        REFUSAL("refusal probes"),
        STILL_KNOWS("still-knows probes"),
    }

    /** Pass counts per probe kind, plus the loss measured on the held-out slice. */
    data class Report(
        val generationId: Int,
        val passed: Map<EvalSuite.Kind, Int>,
        val total: Map<EvalSuite.Kind, Int>,
        val valLoss: Double?,
    ) {
        fun rate(kind: EvalSuite.Kind): Double? {
            val t = total[kind] ?: 0
            if (t <= 0) return null
            return (passed[kind] ?: 0).toDouble() / t
        }
    }

    data class Reason(val axis: Axis, val champion: String, val candidate: String)

    sealed class Decision {
        data class Promote(val improvements: List<String>) : Decision()
        data class Reject(val reasons: List<Reason>) : Decision()

        val isPromote: Boolean get() = this is Promote
    }

    fun decide(candidate: Report, champion: Report?): Decision {
        if (champion == null) {
            return if ((candidate.total.values.sum()) > 0) {
                Decision.Promote(listOf("first generation: nothing to compare against yet"))
            } else {
                Decision.Reject(listOf(Reason(Axis.INSTRUCTION, "no evidence", "no probes ran")))
            }
        }

        val reasons = mutableListOf<Reason>()
        val improvements = mutableListOf<String>()

        val cLoss = candidate.valLoss
        val pLoss = champion.valLoss
        if (cLoss != null && pLoss != null) {
            if (cLoss > pLoss) {
                reasons += Reason(Axis.VAL_LOSS, fmt(pLoss), fmt(cLoss))
            } else if (cLoss < pLoss) {
                improvements += "validation loss ${fmt(pLoss)} → ${fmt(cLoss)}"
            }
        } else if (cLoss == null && pLoss != null) {
            reasons += Reason(Axis.VAL_LOSS, fmt(pLoss), "not measured")
        }

        for ((kind, axis) in kindAxes) {
            val c = candidate.rate(kind) ?: continue
            val p = champion.rate(kind) ?: continue
            val floor = if (kind == EvalSuite.Kind.STILL_KNOWS) p * (1.0 - STILL_KNOWS_TOLERANCE) else p
            if (c + 1e-9 < floor) {
                reasons += Reason(axis, pct(p), pct(c))
            } else if (c > p + 1e-9) {
                improvements += "${axis.label} ${pct(p)} → ${pct(c)}"
            }
        }

        // A candidate that reports nothing on an axis the champion measured is
        // not "equal", it is unproven: refuse rather than assume.
        for ((kind, axis) in kindAxes) {
            if ((champion.total[kind] ?: 0) > 0 && (candidate.total[kind] ?: 0) == 0) {
                reasons += Reason(axis, pct(champion.rate(kind)), "no probes ran")
            }
        }

        if (reasons.isNotEmpty()) return Decision.Reject(reasons)
        // Nothing worse, but nothing better either: the plan promotes a
        // candidate only when the suite says it wins, so a flat run keeps the
        // champion it already has.
        if (improvements.isEmpty()) {
            return Decision.Reject(listOf(Reason(Axis.VAL_LOSS, "the champion", "no axis improved")))
        }
        return Decision.Promote(improvements)
    }

    private val kindAxes = listOf(
        EvalSuite.Kind.INSTRUCTION to Axis.INSTRUCTION,
        EvalSuite.Kind.TOOL_CHOICE to Axis.TOOL_CHOICE,
        EvalSuite.Kind.PROJECT to Axis.PROJECT,
        EvalSuite.Kind.REFUSAL to Axis.REFUSAL,
        EvalSuite.Kind.STILL_KNOWS to Axis.STILL_KNOWS,
    )

    /** The sentence the Brain screen shows when a candidate is refused. */
    fun explain(decision: Decision): String = when (decision) {
        is Decision.Promote ->
            "New generation accepted" +
                if (decision.improvements.isEmpty()) "." else ": ${decision.improvements.joinToString("; ")}."
        is Decision.Reject ->
            "Not promoted — ${decision.reasons.joinToString("; ") { "${it.axis.label} was ${it.champion}, now ${it.candidate}" }}."
    }

    private fun fmt(value: Double) = "%.3f".format(value)
    private fun pct(value: Double?) = if (value == null) "not measured" else "%.0f%%".format(value * 100)
}
