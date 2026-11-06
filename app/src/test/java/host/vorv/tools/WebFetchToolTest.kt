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
        // A REAL oversized body (not a spoofed header): the server sends a
        // truthful 3 MB Content-Length, and the tool must refuse on the
        // precheck before buffering anything.
        val big = "a".repeat(3_000_000)
        server.enqueue(MockResponse().setBody(big).setHeader("Content-Type", "text/html"))
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

    // ---------------------------------------------- M4: byte-true cap

    @Test fun `byte budget caps bytes, not chars — multi-byte content`() {
        // M4: the old loop counted decoded CHARS against maxBytes, so 3-byte
        // CJK text pulled up to 3x the nominal byte cap through. readCappedUtf8
        // counts actual bytes and its marker names BYTES.
        val cjk = "語".repeat(1_000_000)          // 3 MB of UTF-8 bytes
        val out = readCappedUtf8(
            java.io.ByteArrayInputStream(cjk.toByteArray(Charsets.UTF_8)), 2_000_000L)
        assertTrue(
            "truncation marker must name bytes",
            out.contains("truncated at 2000000 bytes"),
        )
        // 2,000,000 bytes ÷ 3 bytes/char ≈ 666,666 chars + marker line.
        assertTrue("decoded body stays under the byte cap", out.length < 700_000)
        // The cap must have cut in the MIDDLE of the byte stream: the last
        // decoded char boundary is clean (no U+FFFD from a split sequence).
        assertFalse("no replacement chars from a split multi-byte tail", out.contains("\uFFFD"))
    }

    @Test fun `readCappedUtf8 reads small streams whole`() {
        val out = readCappedUtf8(
            java.io.ByteArrayInputStream("hello".toByteArray(Charsets.UTF_8)), 100L)
        assertEquals("hello", out)
    }

    @Test fun `trimPartialUtf8Tail drops a split multi-byte character`() {
        val text = "日本語"                       // three 3-byte chars
        val whole = text.toByteArray(Charsets.UTF_8)
        // Cut one byte into the last character's 3-byte sequence.
        val split = whole.copyOf(whole.size - 1)

        val trimmed = trimPartialUtf8Tail(split)
        assertEquals("the partial last char is cut, not replaced", "日本", trimmed.toString(Charsets.UTF_8))
    }

    @Test fun `trimPartialUtf8Tail keeps complete sequences`() {
        val text = "abc日本語"
        assertEquals(text, trimPartialUtf8Tail(text.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))
    }

    @Test fun `trimPartialUtf8Tail handles ascii-only and empty`() {
        assertEquals("abc", trimPartialUtf8Tail("abc".toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))
        assertTrue(trimPartialUtf8Tail(ByteArray(0)).isEmpty())
    }
}
