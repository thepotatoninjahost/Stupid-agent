package host.vorv.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner sees these sentences, so each one must name a next step. */
class LlmFailureTest {

    @Test
    fun `a refused key points at the key`() {
        val s = explainLlmFailure(LlmHttpException(401, "unauthorized"))
        assertTrue(s, s.contains("refused the key"))
    }

    @Test
    fun `a wrong model name points at Refresh list`() {
        assertTrue(explainLlmFailure(LlmHttpException(404, "not found")).contains("Refresh list"))
        assertTrue(explainLlmFailure(LlmHttpException(400, "model not found")).contains("Refresh list"))
    }

    @Test
    fun `no credit says pick a free model`() {
        assertTrue(explainLlmFailure(LlmHttpException(402, "insufficient balance")).contains("Pick a free one"))
    }

    @Test
    fun `rate limiting says it is not the key`() {
        assertTrue(explainLlmFailure(LlmHttpException(429, "slow down")).contains("not your key"))
    }

    @Test
    fun `an outage is not blamed on the owner`() {
        assertTrue(explainLlmFailure(LlmHttpException(503, "maintenance")).contains("Not your configuration"))
    }

    @Test
    fun `a plain exception keeps its own message`() {
        assertEquals("disk full", explainLlmFailure(IllegalStateException("disk full")))
        assertEquals("IllegalStateException", explainLlmFailure(IllegalStateException()))
    }
}
