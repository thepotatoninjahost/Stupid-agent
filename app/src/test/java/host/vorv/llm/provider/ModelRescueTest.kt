package host.vorv.llm.provider

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRescueTest {

    private fun entries(vararg ids: String) = ids.map { ModelCatalog.Entry(it, free = true) }

    @Test
    fun `an empty 200 is about the model, not the key`() {
        assertTrue(
            ModelRescue.isModelFault(
                RuntimeException("HTTP 200, but model \"qwen/qwen3-coder-next\" returned no text (empty content)"),
            ),
        )
    }

    @Test
    fun `a cut off model is also a model fault`() {
        assertTrue(
            ModelRescue.isModelFault(RuntimeException("HTTP 200, but model \"x\" was still cut off at 512 tokens")),
        )
    }

    @Test
    fun `a rejected key is not a model fault - changing model would not help`() {
        assertFalse(ModelRescue.isModelFault(RuntimeException("HTTP 401: invalid api key")))
        assertFalse(ModelRescue.isModelFault(RuntimeException("HTTP 429: rate limited")))
        assertFalse(ModelRescue.isModelFault(null))
    }

    @Test
    fun `coders are tried first and the failed model is never retried`() {
        val list = entries("acme/plain", "acme/coder-pro", "acme/coder-mini", "dead/model")
        val picks = ModelRescue.candidates(list, setOf("dead/model"))
        assertEquals(listOf("acme/coder-pro", "acme/coder-mini", "acme/plain"), picks)
    }

    @Test
    fun `at most four models are tried`() {
        val list = entries("a/1", "a/2", "a/3", "a/4", "a/5", "a/6")
        assertEquals(ModelRescue.MAX_TRIES, ModelRescue.candidates(list, emptySet()).size)
    }

    @Test
    fun `a paid model is never offered as a rescue`() {
        val list = listOf(
            ModelCatalog.Entry("vendor/paid-coder", free = false),
            ModelCatalog.Entry("vendor/free-plain", free = true),
            ModelCatalog.Entry("vendor/unknown-price"),
        )
        assertEquals(listOf("vendor/free-plain"), ModelRescue.candidates(list, emptySet()))
    }

    @Test
    fun `the first model that answers becomes the coder`() = runBlocking {
        val tried = mutableListOf<String>()
        val healed = ModelRescue.heal("dead/model", entries("dead/model", "a/one", "a/two")) { id ->
            tried += id
            if (id == "a/two") Result.success("VORV_KEY_OK") else Result.failure(RuntimeException("empty"))
        }
        assertEquals("a/two", healed?.model)
        assertEquals("dead/model", healed?.failed)
        assertEquals(listOf("a/one", "a/two"), tried)
    }

    @Test
    fun `no model answering leaves the original failure to be reported`() = runBlocking {
        val healed = ModelRescue.heal("dead/model", entries("dead/model", "a/one")) {
            Result.failure(RuntimeException("empty"))
        }
        assertNull(healed)
    }

    @Test
    fun `a model the owner removed from the picker is never offered as a rescue`() {
        val entries = listOf(
            ModelCatalog.Entry(id = "openai/gpt-oss-120b", free = true),
            ModelCatalog.Entry(id = "meta-llama/llama-prompt-guard-2-22m", free = true),
            ModelCatalog.Entry(id = "google/gemma-4-31b-it:free", free = true),
        )
        assertEquals(listOf("google/gemma-4-31b-it:free"), ModelRescue.candidates(entries, emptySet()))
    }
}
