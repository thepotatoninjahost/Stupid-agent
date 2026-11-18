package host.vorv.learn

import java.security.MessageDigest

/**
 * The learning ledger, as rules rather than as storage.
 *
 * Every interaction becomes one row; a row is either training material or
 * evidence. The rules here are pure so they can be tested without Android:
 * when a turn is a correction, how a repeat strengthens a fact, which rows may
 * ever be a training target, and which slice is validation.
 *
 * Storage (Room) and the caller (AgentViewModel, CognitiveKernel) are thin and
 * sit on top of [ingest] and [dataset].
 */
object LearningLedger {

    const val MAX_ROWS = 200_000
    const val MAX_WEIGHT = 3.0
    const val VAL_FRACTION = 0.10

    /** Below this many answerable rows, holding out a slice would hold out all of it. */
    const val MIN_FOR_VAL = 10

    const val CORRECTION_WEIGHT = 2.0
    const val RECOVERY_WEIGHT = 1.5
    const val DISTIL_WEIGHT = 1.0
    const val CHAT_WEIGHT = 0.6
    const val RESEARCH_WEIGHT = 0.5

    enum class Kind {
        CHAT_USER, CHAT_ASSISTANT, TASK, TOOL_CALL, OUTCOME,
        CORRECTION, RESEARCH_NOTE, TEACHER_DISTIL, PLAN, PATCH, BUILD_RESULT,
        ;

        /** Only these may appear as an answer the model is trained to produce. */
        val isTrainingTarget: Boolean
            get() = this == CHAT_ASSISTANT || this == CORRECTION ||
                this == TEACHER_DISTIL || this == PLAN || this == PATCH
    }

    enum class Split { TRAIN, VAL }

    data class Row(
        val id: Long = 0,
        val ts: Long,
        val kind: Kind,
        val sessionId: String = "",
        val inputText: String = "",
        val outputText: String = "",
        val toolCsv: String = "",
        val ok: Boolean? = null,
        val errorClass: String? = null,
        val engineLabel: String = "",
        val sourceUrl: String? = null,
        val quote: String? = null,
        val weight: Double = 1.0,
        val dedupeKey: String = "",
        val split: Split = Split.TRAIN,
        val tokenCount: Int = 0,
    )

    data class Example(val kind: Kind, val input: String, val output: String, val weight: Double)

    data class DatasetStats(
        val rows: Int,
        val examples: Int,
        val trainRows: Int,
        val valRows: Int,
        val dedupeRatio: Double,
    )

    private val negativeOpeners = listOf(
        "no", "nope", "wrong", "that's wrong", "thats wrong", "incorrect",
        "not right", "instead", "use ", "don't", "do not", "stop",
    )

    /**
     * A correction is a user turn straight after an assistant turn that either
     * starts with a negation or repeats the same work with different wording.
     * Deterministic on purpose: a guess here becomes a wrong weight forever.
     */
    fun isCorrection(userText: String, previousAssistant: String?): Boolean {
        if (previousAssistant.isNullOrBlank()) return false
        val t = userText.trim().lowercase()
        if (t.isEmpty()) return false
        if (negativeOpeners.any { opener ->
                val o = opener.trim()
                t == o || t.startsWith("$o,") || t.startsWith("$o ")
            }
        ) return true
        // Same intent, different parameters: share at least 60% of the meaningful words.
        val a = words(t)
        val b = words(previousAssistant.lowercase())
        if (a.isEmpty() || b.isEmpty()) return false
        return a.intersect(b).size.toDouble() / a.size >= 0.6
    }

    fun weightFor(kind: Kind, dedupeRepeats: Int = 0): Double {
        val base = when (kind) {
            Kind.CORRECTION -> CORRECTION_WEIGHT
            Kind.OUTCOME -> RECOVERY_WEIGHT
            Kind.TEACHER_DISTIL, Kind.TASK -> DISTIL_WEIGHT
            Kind.RESEARCH_NOTE -> RESEARCH_WEIGHT
            else -> CHAT_WEIGHT
        }
        return (base + 0.1 * dedupeRepeats).coerceAtMost(MAX_WEIGHT)
    }

    fun dedupeKey(kind: Kind, input: String, output: String): String =
        sha256("$kind|${normalise(input)}|${normalise(output)}")

    /**
     * Adds the row to [existing], or strengthens the row it duplicates.
     * Returns the ledger that should be stored.
     */
    fun ingest(existing: List<Row>, row: Row): List<Row> {
        val key = row.dedupeKey.ifBlank { dedupeKey(row.kind, row.inputText, row.outputText) }
        val keyed = existing.map {
            if (it.dedupeKey.isBlank()) it.copy(dedupeKey = dedupeKey(it.kind, it.inputText, it.outputText)) else it
        }
        val match = keyed.indexOfFirst { it.dedupeKey == key }
        val updated = when {
            match >= 0 -> keyed.toMutableList().also { list ->
                val prev = list[match]
                list[match] = prev.copy(
                    weight = (prev.weight + 0.1).coerceAtMost(MAX_WEIGHT),
                    ts = maxOf(prev.ts, row.ts),
                )
            }
            keyed.size >= MAX_ROWS -> prune(
                keyed + row.copy(
                    dedupeKey = key,
                    weight = weightFor(row.kind),
                )
            )
            else -> keyed + row.copy(
                dedupeKey = key,
                weight = weightFor(row.kind),
            )
        }
        return updated
    }

    /** Oldest, lowest-weight rows go first; corrections are the last to be dropped. */
    fun prune(rows: List<Row>, keep: Int = MAX_ROWS): List<Row> {
        if (rows.size <= keep) return rows
        val protected = rows.filter { it.kind == Kind.CORRECTION }
        val dropCount = (rows.size - keep).coerceAtLeast(0)
        val dropIndexes = rows.indices
            .filter { rows[it].kind != Kind.CORRECTION }
            .sortedWith(compareBy({ rows[it].weight }, { rows[it].ts }))
            .take(dropCount)
            .toSet()
        return rows.filterIndexed { index, _ -> index !in dropIndexes }
    }

    /**
     * Validation is the newest tenth of the answerable rows, so a validation
     * score always tests the future. The boundary is recomputed when the corpus
     * is built (never per insert) so the instrument stays stable while rows
     * arrive; a row that was used for validation is never also trained on.
     */
    fun assignSplits(rows: List<Row>): List<Row> {
        val answerable = rows.filter { it.kind.isTrainingTarget }.sortedWith(compareBy({ it.ts }, { it.dedupeKey }))
        if (answerable.isEmpty()) return rows
        // Fewer than ten answerable rows means there is no "newest tenth" worth
        // holding out: everything trains, so the first generation can exist.
        val newestCount = if (answerable.size < MIN_FOR_VAL) 0 else
            kotlin.math.max(1, kotlin.math.round(answerable.size * VAL_FRACTION).toInt())
        val valKeys = answerable.takeLast(newestCount).map { it.dedupeKey }.toSet()
        return rows.map { if (it.dedupeKey in valKeys) it.copy(split = Split.VAL) else it.copy(split = Split.TRAIN) }
    }

    /**
     * Training material: only answerable rows with an output, never a tool
     * result or web HTML as the target.
     */
    fun dataset(rows: List<Row>, includeVal: Boolean = false): List<Example> = assignSplits(rows)
        .asSequence()
        .filter { it.kind.isTrainingTarget && it.outputText.isNotBlank() && it.inputText.isNotBlank() }
        .filter { includeVal || it.split == Split.TRAIN }
        .map { Example(it.kind, it.inputText, it.outputText, it.weight) }
        .toList()

    fun stats(rows: List<Row>): DatasetStats {
        val split = assignSplits(rows)
        val examples = dataset(split, includeVal = false)
        val answerable = rows.count { it.kind.isTrainingTarget }
        val distinct = rows.map { it.dedupeKey }.distinct().size
        return DatasetStats(
            rows = rows.size,
            examples = examples.size,
            trainRows = split.count { it.kind.isTrainingTarget && it.split == Split.TRAIN },
            valRows = split.count { it.kind.isTrainingTarget && it.split == Split.VAL },
            dedupeRatio = if (rows.isEmpty()) 0.0 else 1.0 - distinct.toDouble() / rows.size,
        )
    }

    /**
     * The export the GPU pipeline reads; one line per example, no credentials.
     *
     * Every answerable row goes in, including the phone's validation tenth,
     * because the split is made once, by tools/train/prepare_corpus.py, with
     * the same rule. Exporting only the training side here would leave the
     * trainer's manifest and the phone's numbers disagreeing about how much
     * data exists.
     */
    fun exportJsonl(rows: List<Row>): String = dataset(rows, includeVal = true).joinToString("\n") { e ->
        """{"kind":"${e.kind}","input":${json(e.input)},"output":${json(e.output)},"weight":${e.weight}}"""
    }

    fun forget(rows: List<Row>, topic: String): List<Row> {
        val needle = topic.trim().lowercase()
        if (needle.isEmpty()) return rows
        return rows.filterNot {
            it.inputText.lowercase().contains(needle) ||
                it.outputText.lowercase().contains(needle) ||
                (it.quote?.lowercase()?.contains(needle) == true)
        }
    }

    private fun words(s: String): Set<String> = s
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in stopWords }
        .toSet()

    private val stopWords = setOf("the", "and", "for", "that", "this", "with", "you", "your", "not", "please")

    private fun normalise(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun json(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
