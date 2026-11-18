package host.vorv.learn

import host.vorv.tools.Attempt
import java.io.File

/**
 * Step 28/29: the speed record. Every build that trains a generation writes one
 * row, so a slower build is visible as a number on this phone rather than as a
 * feeling.
 *
 * The rule is the plan's: a drop above 20% against the previous record is a
 * regression and is refused, not accepted quietly. [Verdict.sentence] is what
 * the Brain screen shows, so "no regression" is a measurement, never a claim.
 */
object SpeedRecord {

    const val FILE = "speed.tsv"
    const val HEADER = "generation\tconfig\ttokens_per_second\tat"

    /** One measured build: this generation, this architecture, this speed. */
    data class Row(
        val generation: Int,
        val config: String,
        val tokensPerSecond: Double,
        val at: Long,
    ) {
        fun line(): String = "$generation\t$config\t${"%.3f".format(tokensPerSecond)}\t$at"
    }

    /**
     * The comparison, in the owner's words. [accepted] is false only for a drop
     * above [allowedDrop]; every case still returns a sentence, because a
     * speed check that says nothing is the failure this project keeps hitting.
     */
    data class Verdict(
        val accepted: Boolean,
        val percentChange: Double?,
        val previous: Row?,
        val current: Row,
        val sentence: String,
    )

    /** Every readable row, oldest first. A malformed line is reported, not hidden. */
    fun read(file: File): List<Row> {
        if (!file.isFile) return emptyList()
        val rows = ArrayList<Row>()
        file.forEachLine { line ->
            val text = line.trim()
            if (text.isBlank() || text.startsWith("generation")) return@forEachLine
            val parts = text.split('\t')
            val row = if (parts.size < 4) null else {
                val gen = parts[0].toIntOrNull()
                val tps = parts[2].toDoubleOrNull()
                val at = parts[3].toLongOrNull()
                if (gen == null || tps == null || at == null) null else Row(gen, parts[1], tps, at)
            }
            if (row == null) {
                Attempt.note("a speed record line the app cannot read, kept for the owner to see", IllegalArgumentException(text))
            } else {
                rows += row
            }
        }
        return rows.sortedBy { it.at }
    }

    /** Appends [row] and returns it; the file keeps its header on a fresh start. */
    fun append(file: File, row: Row): Row {
        file.parentFile?.mkdirs()
        if (!file.isFile) file.writeText(HEADER + "\n")
        file.appendText(row.line() + "\n")
        return row
    }

    /** The newest row for [config], or null when this build is the first one. */
    fun previousFor(rows: List<Row>, config: String, beforeGeneration: Int): Row? =
        rows.filter { it.config == config && it.generation < beforeGeneration }.maxByOrNull { it.at }

    fun verdict(previous: Row?, current: Row, allowedDrop: Double = 0.20): Verdict {
        val speed = "%.1f".format(current.tokensPerSecond)
        if (previous == null || previous.tokensPerSecond <= 0.0) {
            return Verdict(
                accepted = true,
                percentChange = null,
                previous = null,
                current = current,
                sentence = "Measured $speed tokens per second. First record for ${current.config}, so there is nothing to compare yet.",
            )
        }
        val change = (current.tokensPerSecond - previous.tokensPerSecond) / previous.tokensPerSecond
        val percent = Math.abs(change) * 100.0
        val was = "%.1f".format(previous.tokensPerSecond)
        return when {
            change >= 0.0 -> Verdict(
                accepted = true,
                percentChange = change,
                previous = previous,
                current = current,
                sentence = "Measured $speed tokens per second — up ${"%.0f".format(percent)}% on the last record ($was).",
            )
            -change <= allowedDrop -> Verdict(
                accepted = true,
                percentChange = change,
                previous = previous,
                current = current,
                sentence = "Measured $speed tokens per second — ${"%.0f".format(percent)}% below the last record ($was), " +
                    "inside the 20% allowed.",
            )
            else -> Verdict(
                accepted = false,
                percentChange = change,
                previous = previous,
                current = current,
                sentence = "REFUSED: $speed tokens per second is ${"%.0f".format(percent)}% below the last record ($was). " +
                    "A drop above 20% is a regression, so this build is not the one to keep.",
            )
        }
    }
}
