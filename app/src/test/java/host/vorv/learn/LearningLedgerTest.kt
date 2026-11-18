package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningLedgerTest {

    private fun row(
        ts: Long,
        kind: LearningLedger.Kind,
        input: String,
        output: String,
    ) = LearningLedger.Row(ts = ts, kind = kind, inputText = input, outputText = output)

    @Test
    fun `a plain answer to a plain question is training material`() {
        val ledger = listOf(row(1, LearningLedger.Kind.CHAT_ASSISTANT, "how do I build", "run ./gradlew assembleDebug"))
        val dataset = LearningLedger.dataset(ledger, includeVal = true)
        assertEquals(1, dataset.size)
        assertEquals("run ./gradlew assembleDebug", dataset.first().output)
    }

    @Test
    fun `a tool result is never a training target`() {
        val ledger = listOf(
            row(1, LearningLedger.Kind.TOOL_CALL, "file_read", "fun main() {}"),
            row(2, LearningLedger.Kind.RESEARCH_NOTE, "what is qlora", "from the page: ..."),
        )
        assertTrue(LearningLedger.dataset(ledger, includeVal = true).isEmpty())
        assertFalse(LearningLedger.Kind.TOOL_CALL.isTrainingTarget)
    }

    @Test
    fun `a short no after an answer is a correction and weighs the most`() {
        assertTrue(LearningLedger.isCorrection("no, use the other model", "I chose GLM"))
        assertTrue(LearningLedger.isCorrection("wrong", "here is the plan"))
        assertTrue(LearningLedger.isCorrection("use ktlint instead", "run detekt"))
        assertFalse(LearningLedger.isCorrection("no", null))
        assertTrue(
            LearningLedger.weightFor(LearningLedger.Kind.CORRECTION) >
                LearningLedger.weightFor(LearningLedger.Kind.CHAT_ASSISTANT)
        )
    }

    @Test
    fun `a repeated fact strengthens instead of adding a row`() {
        val first = row(10, LearningLedger.Kind.CHAT_ASSISTANT, "which distro", "debian 12 bookworm")
        var ledger = LearningLedger.ingest(emptyList(), first)
        val weightAfterFirst = ledger.single().weight
        ledger = LearningLedger.ingest(ledger, first.copy(ts = 20))
        assertEquals(1, ledger.size)
        assertTrue(ledger.single().weight > weightAfterFirst)
        assertTrue(ledger.single().weight <= LearningLedger.MAX_WEIGHT)
    }

    @Test
    fun `the weights never exceed the cap however often a fact repeats`() {
        var ledger = listOf(row(1, LearningLedger.Kind.CORRECTION, "q", "a"))
        repeat(40) { ledger = LearningLedger.ingest(ledger, row(1, LearningLedger.Kind.CORRECTION, "q", "a")) }
        assertTrue(ledger.single().weight <= LearningLedger.MAX_WEIGHT)
    }

    @Test
    fun `validation is the newest tenth and training never sees it`() {
        var ledger = emptyList<LearningLedger.Row>()
        for (i in 1..20) {
            ledger = LearningLedger.ingest(ledger, row(i.toLong(), LearningLedger.Kind.CHAT_ASSISTANT, "q$i", "a$i"))
        }
        val split = LearningLedger.assignSplits(ledger)
        val valRows = split.filter { it.split == LearningLedger.Split.VAL }
        assertEquals(2, valRows.size)
        assertTrue(valRows.all { it.ts >= 19 })
        assertEquals(18, LearningLedger.dataset(ledger, includeVal = false).size)
    }

    @Test
    fun `pruning drops low weight oldest rows first and protects corrections`() {
        val rows = (1..10).map { row(it.toLong(), LearningLedger.Kind.CHAT_ASSISTANT, "q$it", "a$it") } +
            LearningLedger.Row(ts = 1, kind = LearningLedger.Kind.CORRECTION, inputText = "q0", outputText = "a0", weight = 2.0)
        val kept = LearningLedger.prune(rows, keep = 4)
        assertEquals(4, kept.size)
        assertTrue(kept.any { it.kind == LearningLedger.Kind.CORRECTION })
        assertFalse(kept.any { it.inputText == "q1" })
    }

    @Test
    fun `forgetting a topic removes it from the corpus`() {
        val rows = listOf(
            row(1, LearningLedger.Kind.CHAT_ASSISTANT, "what is rust", "rust is a language"),
            row(2, LearningLedger.Kind.CHAT_ASSISTANT, "how do I build", "run gradlew"),
        )
        val kept = LearningLedger.forget(rows, "rust")
        assertEquals(1, kept.size)
        assertTrue(LearningLedger.dataset(kept, includeVal = true).none { it.output.contains("rust") })
    }

    @Test
    fun `the export is one valid json line per example and escapes quotes`() {
        val rows = listOf(row(1, LearningLedger.Kind.CHAT_ASSISTANT, "say \"hi\"", "line1\nline2"))
        val jsonl = LearningLedger.exportJsonl(rows)
        assertEquals(1, jsonl.lines().size)
        assertTrue(jsonl.contains("\\\""))
        assertTrue(jsonl.contains("\\n"))
    }

    @Test
    fun `the export carries the validation rows too, so the trainer sees every example`() {
        val rows = (1..20).map { row(it.toLong(), LearningLedger.Kind.CHAT_ASSISTANT, "q$it", "a$it") }
        val jsonl = LearningLedger.exportJsonl(rows)
        assertEquals(20, jsonl.lines().size)
        assertTrue(jsonl.contains("\"q20\""))
    }

    @Test
    fun `stats count only answerable rows and report dedupe`() {
        val rows = listOf(
            row(1, LearningLedger.Kind.CHAT_ASSISTANT, "q", "a"),
            row(2, LearningLedger.Kind.TOOL_CALL, "ls", "out"),
        )
        val stats = LearningLedger.stats(rows)
        assertEquals(2, stats.rows)
        assertEquals(1, stats.examples)
        assertTrue(stats.dedupeRatio >= 0.0)
    }
}
