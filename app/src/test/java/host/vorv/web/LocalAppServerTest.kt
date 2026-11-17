package host.vorv.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class LocalAppServerTest {

    private fun project(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "webapp-${System.nanoTime()}")
        dir.mkdirs()
        File(dir, "index.html").writeText("<h1>hi</h1>")
        File(dir, "app.js").writeText("console.log('x')")
        File(dir, "assets").mkdirs()
        File(dir, "assets/style.css").writeText("body{}")
        return dir
    }

    private fun fetch(url: String): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 3000
        c.readTimeout = 3000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        c.disconnect()
        return code to body
    }

    @Test
    fun `serves the entry file on loopback`() {
        LocalAppServer(project()).start().use { server ->
            assertTrue(server.baseUrl.startsWith("http://127.0.0.1:"))
            val (code, body) = fetch(server.baseUrl + "index.html")
            assertEquals(200, code)
            assertEquals("<h1>hi</h1>", body)
        }
    }

    @Test
    fun `serves nested files with correct content type`() {
        LocalAppServer(project()).start().use { server ->
            val c = URI(server.baseUrl + "assets/style.css").toURL().openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertTrue(c.getHeaderField("Content-Type").startsWith("text/css"))
            assertEquals("no-store", c.getHeaderField("Cache-Control"))
            c.disconnect()
        }
    }

    @Test
    fun `directory requests serve index html`() {
        LocalAppServer(project()).start().use { server ->
            val (code, body) = fetch(server.baseUrl)
            assertEquals(200, code)
            assertEquals("<h1>hi</h1>", body)
        }
    }

    @Test
    fun `traversal outside the project is refused`() {
        LocalAppServer(project()).start().use { server ->
            val (code, _) = fetch(server.baseUrl + "../../../../etc/passwd")
            assertTrue("expected refusal, got $code", code == 403 || code == 404)
        }
    }

    @Test
    fun `encoded traversal is refused too`() {
        LocalAppServer(project()).start().use { server ->
            val (code, _) = fetch(server.baseUrl + "assets/%2e%2e/%2e%2e/%2e%2e/etc/passwd")
            assertTrue("expected refusal, got $code", code == 403 || code == 404)
        }
    }

    @Test
    fun `missing file is a 404 that names the path`() {
        LocalAppServer(project()).start().use { server ->
            val (code, body) = fetch(server.baseUrl + "nope.html")
            assertEquals(404, code)
            assertTrue(body.contains("nope.html"))
        }
    }

    @Test
    fun `only loopback is bound and closing stops the server`() {
        val server = LocalAppServer(project()).start()
        val port = server.port
        server.close()
        val failed = try {
            fetch("http://127.0.0.1:$port/index.html"); false
        } catch (e: Exception) {
            true
        }
        assertTrue("server should refuse connections after close", failed)
    }

    @Test
    fun `unknown extensions are not guessed as html`() {
        assertEquals("application/octet-stream", LocalAppServer.contentType("data.bin"))
        assertFalse(LocalAppServer.contentType("x.bin").startsWith("text/html"))
    }

    /** Sends a request line exactly as written — URI parsing would reject a bad escape. */
    private fun rawRequest(port: Int, target: String): String {
        java.net.Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 3000
            s.getOutputStream().apply {
                write("GET $target HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                flush()
            }
            return s.getInputStream().readBytes().decodeToString()
        }
    }

    @Test
    fun `a plus in a file name is a plus, not a space`() {
        // R-5: URLDecoder turned '+' into ' ', so every such file was a 404.
        val dir = project()
        File(dir, "a+b.txt").writeText("plus")
        LocalAppServer(dir).start().use { server ->
            assertEquals("plus", fetch(server.baseUrl + "a+b.txt").second)
            assertEquals("plus", fetch(server.baseUrl + "a%2Bb.txt").second)
        }
    }

    @Test
    fun `a malformed escape is a 400, not a dropped connection`() {
        // R-5: this used to throw out of the worker thread and close the socket.
        LocalAppServer(project()).start().use { server ->
            val response = rawRequest(server.port, "/a%zz")
            assertTrue("expected a 400, got: ${response.take(40)}", response.startsWith("HTTP/1.1 400"))
        }
    }

    @Test
    fun `a file above the cap is refused instead of loaded into the heap`() {
        val dir = project()
        java.io.RandomAccessFile(File(dir, "huge.bin"), "rw").use { it.setLength(65L * 1024 * 1024) }
        LocalAppServer(dir).start().use { server ->
            assertEquals(413, fetch(server.baseUrl + "huge.bin").first)
        }
    }
}
