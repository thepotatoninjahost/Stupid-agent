package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 9: a generation ships only when it is better, and never by forgetting. */
class PromotionGateTest {

    private fun report(
        id: Int,
        loss: Double? = 1.0,
        instruction: Pair<Int, Int> = 9 to 10,
        tools: Pair<Int, Int> = 5 to 5,
        project: Pair<Int, Int> = 4 to 5,
        refusal: Pair<Int, Int> = 2 to 2,
        stillKnows: Pair<Int, Int> = 10 to 10,
    ) = PromotionGate.Report(
        generationId = id,
        passed = mapOf(
            EvalSuite.Kind.INSTRUCTION to instruction.first,
            EvalSuite.Kind.TOOL_CHOICE to tools.first,
            EvalSuite.Kind.PROJECT to project.first,
            EvalSuite.Kind.REFUSAL to refusal.first,
            EvalSuite.Kind.STILL_KNOWS to stillKnows.first,
        ),
        total = mapOf(
            EvalSuite.Kind.INSTRUCTION to instruction.second,
            EvalSuite.Kind.TOOL_CHOICE to tools.second,
            EvalSuite.Kind.PROJECT to project.second,
            EvalSuite.Kind.REFUSAL to refusal.second,
            EvalSuite.Kind.STILL_KNOWS to stillKnows.second,
        ),
        valLoss = loss,
    )

    @Test
    fun `a strictly better candidate is promoted and says how`() {
        val decision = PromotionGate.decide(
            report(2, loss = 0.8, instruction = 10 to 10),
            report(1),
        )
        assertTrue(PromotionGate.explain(decision), decision.isPromote)
        val text = PromotionGate.explain(decision)
        assertTrue(text.contains("validation loss"))
        assertTrue(text.contains("instruction probes"))
    }

    @Test
    fun `a flat run keeps the champion because nothing won`() {
        val decision = PromotionGate.decide(report(2), report(1))
        assertFalse(PromotionGate.explain(decision), decision.isPromote)
        assertTrue(PromotionGate.explain(decision).contains("no axis improved"))
    }

    @Test
    fun `one better axis is a win even when everything else is level`() {
        val decision = PromotionGate.decide(report(2, tools = 5 to 5, loss = 0.9), report(1))
        assertTrue(PromotionGate.explain(decision), decision.isPromote)
    }

    @Test
    fun `worse validation loss alone is enough to refuse`() {
        val decision = PromotionGate.decide(report(2, loss = 1.2), report(1))
        assertFalse(decision.isPromote)
        assertTrue(PromotionGate.explain(decision).contains("validation loss"))
    }

    @Test
    fun `better loss cannot buy a worse instruction score`() {
        val decision = PromotionGate.decide(
            report(2, loss = 0.5, instruction = 7 to 10),
            report(1),
        )
        assertFalse(PromotionGate.explain(decision), decision.isPromote)
        assertTrue(PromotionGate.explain(decision).contains("instruction probes"))
    }

    @Test
    fun `a still-knows drop of exactly five percent is allowed`() {
        // champion 10/10, candidate 95/100 is a 5% drop, which the plan allows.
        val decision = PromotionGate.decide(
            report(2, loss = 0.9, stillKnows = 95 to 100, instruction = 100 to 100),
            report(1, stillKnows = 10 to 10, instruction = 100 to 100),
        )
        assertTrue(PromotionGate.explain(decision), decision.isPromote)
    }

    @Test
    fun `a still-knows drop beyond five percent is refused`() {
        val decision = PromotionGate.decide(
            report(2, stillKnows = 94 to 100),
            report(1, stillKnows = 10 to 10),
        )
        assertFalse(decision.isPromote)
        assertTrue(PromotionGate.explain(decision).contains("still-knows"))
    }

    @Test
    fun `an unmeasured axis the champion measured is refused, not assumed equal`() {
        val champion = report(1)
        val candidate = PromotionGate.Report(
            generationId = 2,
            passed = mapOf(EvalSuite.Kind.INSTRUCTION to 10),
            total = mapOf(EvalSuite.Kind.INSTRUCTION to 10),
            valLoss = 1.0,
        )
        val decision = PromotionGate.decide(candidate, champion)
        assertFalse(PromotionGate.explain(decision), decision.isPromote)
        assertTrue(PromotionGate.explain(decision).contains("no probes ran"))
    }

    @Test
    fun `the first generation is promoted only if it actually ran the suite`() {
        assertTrue(PromotionGate.decide(report(1), null).isPromote)
        val empty = PromotionGate.Report(1, emptyMap(), emptyMap(), valLoss = null)
        assertFalse(PromotionGate.decide(empty, null).isPromote)
    }

    @Test
    fun `a candidate that lost validation measurement cannot silently win`() {
        val decision = PromotionGate.decide(report(2, loss = null), report(1, loss = 1.0))
        assertFalse(PromotionGate.explain(decision), decision.isPromote)
        assertEquals(true, PromotionGate.explain(decision).contains("not measured"))
    }

    @Test
    fun `the refusal sentence names both numbers so the owner can see the trade`() {
        val decision = PromotionGate.decide(
            report(2, project = 3 to 5),
            report(1, project = 4 to 5),
        )
        val text = PromotionGate.explain(decision)
        assertTrue(text, text.contains("project probes was 80%, now 60%"))
    }
}
