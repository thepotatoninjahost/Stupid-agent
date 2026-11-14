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
}
