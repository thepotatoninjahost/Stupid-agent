package host.vorv.learn

import host.vorv.data.dao.LearningDao
import host.vorv.data.entity.LearningRowEntity

/**
 * Step 6: storage for the ledger. Rules stay in [LearningLedger]; this class
 * only applies them to the table, so the same sentences are produced whether
 * they are tested in memory or read back from the phone.
 */
class LearningStore(private val dao: LearningDao) {

    /**
     * Records one interaction. A duplicate strengthens the existing row (its
     * weight rises, its timestamp moves) rather than adding a second copy.
     * Returns true when the row is new, so a caller can say so if it wants to.
     */
    suspend fun record(row: LearningLedger.Row): Boolean {
        val key = row.dedupeKey.ifBlank {
            LearningLedger.dedupeKey(row.kind, row.inputText, row.outputText)
        }
        val existing = dao.byKey(key)
        val stored: LearningRowEntity = if (existing == null) {
            row.copy(
                dedupeKey = key,
                weight = LearningLedger.weightFor(row.kind),
            ).toEntity()
        } else {
            existing.copy(
                ts = maxOf(existing.ts, row.ts),
                weight = (existing.weight + 0.1).coerceAtMost(LearningLedger.MAX_WEIGHT),
                ok = row.ok ?: existing.ok,
                errorClass = row.errorClass ?: existing.errorClass,
            )
        }
        dao.upsert(stored)
        if (dao.count() > LearningLedger.MAX_ROWS) {
            dao.deleteLowestWeight(dao.count() - LearningLedger.MAX_ROWS)
        }
        return existing == null
    }

    /** Everything, in the order it was learned. */
    suspend fun rows(): List<LearningLedger.Row> = dao.all().map { it.toRow() }

    suspend fun count(): Int = dao.count()

    suspend fun answerableCount(): Int = dao.answerableCount()

    /** The corpus the trainer and the GPU job read. */
    suspend fun exportJsonl(): String = LearningLedger.exportJsonl(rows())

    /** Forget one topic, everywhere. */
    suspend fun forget(topic: String): Int {
        val all = rows()
        val keep = LearningLedger.forget(all, topic).map { it.dedupeKey }.toSet()
        val drop = all.filterNot { it.dedupeKey in keep }
        drop.forEach { dao.delete(it.dedupeKey) }
        return drop.size
    }

    private fun LearningLedger.Row.toEntity() = LearningRowEntity(
        id = id,
        ts = ts,
        kind = kind.name,
        sessionId = sessionId,
        inputText = inputText,
        outputText = outputText,
        toolCsv = toolCsv,
        ok = ok,
        errorClass = errorClass,
        engineLabel = engineLabel,
        sourceUrl = sourceUrl,
        quote = quote,
        weight = weight,
        dedupeKey = dedupeKey,
        split = split.name,
        tokenCount = tokenCount,
    )

    private fun LearningRowEntity.toRow() = LearningLedger.Row(
        id = id,
        ts = ts,
        kind = runCatching { LearningLedger.Kind.valueOf(kind) }
            .getOrDefault(LearningLedger.Kind.CHAT_ASSISTANT),
        sessionId = sessionId,
        inputText = inputText,
        outputText = outputText,
        toolCsv = toolCsv,
        ok = ok,
        errorClass = errorClass,
        engineLabel = engineLabel,
        sourceUrl = sourceUrl,
        quote = quote,
        weight = weight,
        dedupeKey = dedupeKey,
        split = runCatching { LearningLedger.Split.valueOf(split) }
            .getOrDefault(LearningLedger.Split.TRAIN),
        tokenCount = tokenCount,
    )
}
