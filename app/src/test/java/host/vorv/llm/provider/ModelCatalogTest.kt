package host.vorv.llm.provider

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
    fun `l2 the default client goes through the network guard`() = runBlocking {
        // localhost resolves through OkHttp's Dns; with the guard as the
        // default it throws before any connection is attempted.
        val p = Provider.custom("http://localhost:1")
        val listing = ModelCatalog().fetch(p, apiKey = "")
        val error = listing.error.orEmpty()
        assertTrue("error was: $error", error.contains("Vorv network guard"))
    }

    @Test
    fun `l2 the default client refuses a redirect and names it`() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                return if (request.path == "/models") {
                    okhttp3.mockwebserver.MockResponse()
                        .setResponseCode(307)
                        .addHeader("Location", "/target")
                } else {
                    okhttp3.mockwebserver.MockResponse().setBody("""{"data":[{"id":"target"}]}""")
                }
            }
        }
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val p = Provider.custom(base)
            // The default client's guard refuses loopback (proved above), so
            // this test injects a loopback-capable client to reach the wire
            // and prove the catalog refuses the redirect on ANY client.
            val localClient = OkHttpClient.Builder()
                .dns { listOf(java.net.InetAddress.getLoopbackAddress()) }
                .build()
            val listing = ModelCatalog(localClient).fetch(p, apiKey = "")
            val error = listing.error.orEmpty()
            assertTrue("error was: $error", error.contains("/target") && error.contains("redirects are refused"))
            // The redirect must not have been followed.
            var paths = mutableListOf<String>()
            while (true) {
                val r = server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                paths.add(r.path!!)
            }
            assertEquals(listOf("/models"), paths)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `reads the openai models shape`() {
        val body = """
            {"object":"list","data":[
              {"id":"bloom-7b","context_length":200000,"pricing":{"prompt":"0.0001"}},
              {"id":"acorn-1b"},
              {"id":"cedar-3b"}
            ]}
        """.trimIndent()
        val ids = catalog.parse(body).map { it.id }
        // Sorted by id: the chips are easier to scan, and order is not the API's.
        assertEquals(listOf("acorn-1b", "bloom-7b", "cedar-3b"), ids)
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
        val entries = listOf("coder-large", "coder-small", "qwen3-coder-480b", "deepseek-chat")
            .map { ModelCatalog.Entry(it) }
        assertEquals("coder-large", catalog.closest("coder-larg", entries).first())
        assertEquals("qwen3-coder-480b", catalog.closest("qwen3-coder", entries).first())
        assertEquals("deepseek-chat", catalog.closest("deepseak-chat", entries).first())
    }

    @Test
    fun `nothing similar returns nothing rather than a wrong guess`() {
        val entries = listOf(ModelCatalog.Entry("coder-large"))
        assertTrue(catalog.closest("totally-unrelated-xyz", entries).isEmpty())
    }

    @Test
    fun `cache is per provider and reports its age`() {
        var now = 1_000_000L
        val c = ModelCatalog(clock = { now })
        val p = ProviderRegistry.byId(ProviderRegistry.DEFAULT_ID)!!
        c.rememberForTest(ModelCatalog.Listing(p.id, listOf(ModelCatalog.Entry("m")), now))
        assertEquals(1, c.cached(p.id)?.entries?.size)
        assertTrue(c.isFresh(p.id))
        now += 25 * 60 * 60_000L
        assertTrue("a day-old list must not be treated as current", !c.isFresh(p.id))
        assertEquals(null, c.cached("other"))
    }
}
