package host.vorv.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebFetchTool tests against a MockWebServer. The tool is given a PLAIN
 * OkHttpClient (no PublicDns) because MockWebServer binds to localhost —
 * which the production guard would correctly refuse. The SSRF tests for
 * the guard itself live in NetworkGuardTest.
 */
class WebFetchToolTest {

    private lateinit var server: MockWebServer
    private lateinit var tool: WebFetchTool

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        // Plain client: test the tool logic, not the DNS guard (localhost here).
        tool = WebFetchTool(client = OkHttpClient())
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun fetch(url: String): ToolResult = runBlocking {
        tool.run(JsonObject().apply { addProperty("url", url) }, ToolContext("t", 1, null))
    }

    @Test fun `fetches html and strips to text`() {
        server.enqueue(MockResponse().setBody(
            "<html><head><title>T</title></head><body><script>evil()</script><h1>Hello</h1><p>World</p></body></html>"
        ).setHeader("Content-Type", "text/html"))
        val r = fetch(server.url("/page").toString())
        assertTrue(r.ok)
        assertTrue(r.output.contains("Hello"))
        assertTrue(r.output.contains("World"))
        assertFalse("script body must be removed", r.output.contains("evil()"))
    }

    @Test fun `http error surfaces honestly`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val r = fetch(server.url("/missing").toString())
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("404"))
    }

    @Test fun `binary content type is refused`() {
        server.enqueue(MockResponse().setBody("PK\u0003\u0004binary")
            .setHeader("Content-Type", "application/octet-stream"))
        val r = fetch(server.url("/file.zip").toString())
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("octet-stream"))
    }

    @Test fun `json content type is allowed`() {
        server.enqueue(MockResponse().setBody("""{"a":1}""").setHeader("Content-Type", "application/json"))
        val r = fetch(server.url("/data.json").toString())
        assertTrue(r.ok)
        assertTrue(r.output.contains("\"a\""))
    }

    @Test fun `non-http scheme is refused`() {
        val r = fetch("file:///data/data/host.vorv/databases/vorv.db")
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("scheme"))
    }

    @Test fun `huge declared content length is refused before download`() {
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Length", "9999999999")
            .setBody("x"))
        val r = fetch(server.url("/huge").toString())
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("too large", ignoreCase = true))
    }

    @Test fun `output is capped at 8000 chars`() {
        val big = "a".repeat(100_000)
        server.enqueue(MockResponse().setBody("<html><body>$big</body></html>")
            .setHeader("Content-Type", "text/html"))
        val r = fetch(server.url("/big").toString())
        assertTrue(r.ok)
        assertTrue(r.output.length <= 8000)
    }

    @Test fun `citation is the fetched url`() {
        server.enqueue(MockResponse().setBody("<html><body>ok</body></html>").setHeader("Content-Type", "text/html"))
        val url = server.url("/cite").toString()
        val r = fetch(url)
        assertEquals("web", r.citation?.type)
        assertEquals(url, r.citation?.ref)
    }
}
