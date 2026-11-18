package host.vorv.llm.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider's own model list is the only authority on which model ids exist
 * there. These tests pin the parsing and the "did you mean" that turns a
 * retired id into a usable one instead of "LLM error: HTTP 404".
 */
class ModelCatalogTest {

    private val catalog = ModelCatalog()

    @Test
    fun `reads the openai models shape`() {
        val body = """
            {"object":"list","data":[
              {"id":"glm-4.7","context_length":200000,"pricing":{"prompt":"0.0001"}},
              {"id":"glm-4.7-flash"},
              {"id":"glm-4.6"}
            ]}
        """.trimIndent()
        val ids = catalog.parse(body).map { it.id }
        // Sorted by id: the chips are easier to scan, and order is not the API's.
        assertEquals(listOf("glm-4.6", "glm-4.7", "glm-4.7-flash"), ids)
    }

    @Test
    fun `missing or malformed lists are empty, never a crash`() {
        assertEquals(emptyList<ModelCatalog.Entry>(), catalog.parse("""{"data":null}"""))
        assertEquals(emptyList<ModelCatalog.Entry>(), catalog.parse("{}"))
        assertEquals(emptyList<ModelCatalog.Entry>(), catalog.parse("<html>gateway</html>"))
        assertEquals(emptyList<ModelCatalog.Entry>(), catalog.parse(""))
        // An entry with no id is useless and must not become an empty chip.
        assertEquals(listOf("a"), catalog.parse("""{"data":[{"id":"a"},{"context_length":1}]}""").map { it.id })
    }

    @Test
    fun `a mistyped model finds the real one`() {
        val entries = listOf("glm-4.7", "glm-4.7-flash", "qwen3-coder-480b", "deepseek-chat")
            .map { ModelCatalog.Entry(it) }
        assertEquals("glm-4.7-flash", catalog.closest("glm-4.7-flsh", entries).first())
        assertEquals("qwen3-coder-480b", catalog.closest("qwen3-coder", entries).first())
        assertEquals("deepseek-chat", catalog.closest("deepseak-chat", entries).first())
    }

    @Test
    fun `nothing similar returns nothing rather than a wrong guess`() {
        val entries = listOf(ModelCatalog.Entry("glm-4.7"))
        assertTrue(catalog.closest("totally-unrelated-xyz", entries).isEmpty())
    }

    @Test
    fun `cache is per provider and reports its age`() {
        var now = 1_000_000L
        val c = ModelCatalog(clock = { now })
        val p = ProviderRegistry.byId(ProviderRegistry.ZAI_ID)!!
        c.rememberForTest(ModelCatalog.Listing(p.id, listOf(ModelCatalog.Entry("m")), now))
        assertEquals(1, c.cached(p.id)?.entries?.size)
        assertTrue(c.isFresh(p.id))
        now += 25 * 60 * 60_000L
        assertTrue("a day-old list must not be treated as current", !c.isFresh(p.id))
        assertEquals(null, c.cached("other"))
    }
}
