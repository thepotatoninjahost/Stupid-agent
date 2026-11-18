package host.vorv.learn

import java.io.File

/**
 * Step 9 (screen side): everything the Brain screen says, worked out once and
 * testable without a phone.
 *
 * Three rules show up here as data, not as prose:
 *   * the serving generation is always named, or the screen says plainly that
 *     nothing serves yet;
 *   * a refused candidate keeps its sentence, so a "no" is a result the owner
 *     can read, never a disappearance;
 *   * rollback names the generation it would go back to, or says why it cannot.
 */
object BrainReport {

    data class Run(
        val id: Long,
        val startedAt: Long,
        val state: String,
        val generation: Int,
        val rung: String,
        val corpusRows: Int,
        val trainLoss: Double?,
        val valLoss: Double?,
        val seconds: Double?,
        val message: String,
    )

    data class Generation(
        val id: Int,
        val origin: String,
        val valLoss: Double?,
        val accepted: Boolean,
        val decision: String,
        val serving: Boolean,
    )

    data class View(
        val headline: String,
        val servingId: Int?,
        val generations: List<Generation>,
        val chart: List<Pair<Int, Double>>,
        val runs: List<Run>,
        val rollbackTarget: Int?,
        val rollbackNote: String,
    )

    fun build(root: File, runs: List<Run>): View {
        val store = GenerationStore(root)
        val all = store.generations()
        val champion = store.champion()
        val generations = all.sortedByDescending { it.id }.map { g ->
            Generation(
                id = g.id,
                origin = g.origin,
                valLoss = g.valLoss,
                accepted = g.accepted,
                decision = g.decision.ifBlank { if (g.accepted) "Accepted." else "Not accepted." },
                serving = champion?.id == g.id,
            )
        }
        val chart = all.filter { it.accepted && it.valLoss != null }
            .sortedBy { it.id }
            .map { it.id to it.valLoss!! }
        val target = champion?.let { c ->
            all.filter { it.accepted && it.id < c.id }.maxByOrNull { it.id }?.id
        }
        val rollbackNote = when {
            champion == null -> "Nothing is serving yet, so there is nothing to go back to."
            target == null -> "Generation ${champion.id} is the oldest one kept, so there is nothing to go back to."
            else -> "One tap goes back to generation $target. Generation ${champion.id} stays on the phone."
        }
        val headline = when {
            champion == null && all.isEmpty() -> "No generation has been trained yet."
            champion == null -> "Nothing is serving: ${all.size} generation(s) trained, none accepted."
            all.size == 1 -> "Generation ${champion.id} is serving. It is the first one."
            else -> "Generation ${champion.id} is serving. ${all.size} generations kept, all reversible."
        }
        return View(
            headline = headline,
            servingId = champion?.id,
            generations = generations,
            chart = chart,
            runs = runs.sortedByDescending { it.startedAt },
            rollbackTarget = target,
            rollbackNote = rollbackNote,
        )
    }

    /** One line per generation for the phone screen: short, numbered, plain. */
    fun generationLine(g: Generation): String {
        val who = when (g.origin) {
            "phone" -> "trained on this phone"
            "gpu" -> "trained on a GPU"
            else -> g.origin
        }
        val loss = g.valLoss?.let { "loss ${"%.3f".format(it)}" } ?: "loss not measured"
        val verdict = if (g.accepted) "kept" else "refused"
        val serving = if (g.serving) " — serving now" else ""
        return "Generation ${g.id}: $who, $loss, $verdict$serving"
    }

    /** The tallest bar is the oldest accepted generation, so a rising chart is visible. */
    fun chartLine(chart: List<Pair<Int, Double>>): String {
        if (chart.size < 2) return ""
        val first = chart.first().second
        val last = chart.last().second
        val direction = when {
            last < first -> "down ${"%.3f".format(first - last)}"
            last > first -> "up ${"%.3f".format(last - first)} (worse)"
            else -> "flat"
        }
        return "Held-out loss across ${chart.size} accepted generations: ${"%.3f".format(first)} → " +
            "${"%.3f".format(last)}, $direction"
    }
}
