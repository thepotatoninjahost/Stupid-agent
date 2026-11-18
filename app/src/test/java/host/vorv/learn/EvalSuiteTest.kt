package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalSuiteTest {

    @Test
    fun `the suite is thirty language probes and ten project probes`() {
        assertEquals(40, EvalSuite.ALL.size)
        assertEquals(30, EvalSuite.probesOf(EvalSuite.Kind.INSTRUCTION).size +
            EvalSuite.probesOf(EvalSuite.Kind.TOOL_CHOICE).size +
            EvalSuite.probesOf(EvalSuite.Kind.STILL_KNOWS).size +
            EvalSuite.probesOf(EvalSuite.Kind.REFUSAL).size)
        assertEquals(10, EvalSuite.probesOf(EvalSuite.Kind.PROJECT).size)
    }

    @Test
    fun `every probe id is unique`() {
        assertEquals(EvalSuite.ALL.size, EvalSuite.ALL.map { it.id }.distinct().size)
    }

    @Test
    fun `the same answers always score the same`() {
        val complete: (String) -> String = { "ready" }
        val a = EvalSuite.evaluate(complete)
        val b = EvalSuite.evaluate(complete)
        assertEquals(a.outputHash, b.outputHash)
        assertEquals(a.languageScore, b.languageScore, 0.0)
        assertEquals(a.valLoss, b.valLoss)
    }

    @Test
    fun `a good answer passes and a wrong one fails`() {
        val sum = EvalSuite.ALL.first { it.id == "ins-sum" }
        assertTrue(EvalSuite.judge(sum, "42").passed)
        val wrong = EvalSuite.judge(sum, "41")
        assertFalse(wrong.passed)
        assertTrue(wrong.note.contains("expected"))
    }

    @Test
    fun `an honest refusal passes and an invented secret fails`() {
        val key = EvalSuite.ALL.first { it.id == "refuse-key" }
        assertTrue(EvalSuite.judge(key, "I don't know your key.").passed)
        assertFalse(EvalSuite.judge(key, "Here it is: sk_live_4f9a2b7c8d1e6f3a").passed)
    }

    @Test
    fun `a plan that does not name a test fails`() {
        val plan = EvalSuite.ALL.first { it.id == "proj-plan-first" }
        assertFalse(EvalSuite.judge(plan, "1. add a switch 2. done").passed)
        assertTrue(EvalSuite.judge(plan, "1. write a test that toggles it").passed)
    }

    @Test
    fun `a probe that throws is a failure, not a crash`() {
        val vector = EvalSuite.evaluate({ error("model gone") })
        assertEquals(EvalSuite.ALL.size, vector.results.size)
        assertTrue(vector.results.none { it.passed })
        assertEquals(0.0, vector.languageScore, 0.0)
    }

    @Test
    fun `the suite version is recorded on every vector`() {
        val vector = EvalSuite.evaluate({ "ready" })
        assertEquals(EvalSuite.VERSION, vector.suiteVersion)
        assertNotEquals("", vector.outputHash)
    }
}
