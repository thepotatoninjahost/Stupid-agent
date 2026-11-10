package host.vorv.tools

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class BrowserProxyTest {
    private fun client(serve: (Request) -> Response) = OkHttpClient.Builder()
        .dns(NetworkGuard.PublicDns)
        .followRedirects(false)
        .followSslRedirects(false)
        // Synthetic public responses: no external network or private connection.
        .addInterceptor { serve(it.request()) }
        .build()

    private fun response(request: Request, code: Int, location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body("test".toResponseBody())
            .apply { location?.let { header("Location", it) } }.build()

    @Test fun `redirects to private destinations are rejected before another call`() {
        val targets = listOf("127.0.0.1", "10.0.0.1", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "[::1]", "[fd00::1]", "localhost")
        for (code in listOf(300, 301, 302, 303, 307, 308)) {
            for (target in targets) {
                var calls = 0
                val client = client { request ->
                    calls++
                    response(request, code, "https://$target:8443/private")
                }
                val result = runCatching {
                    executeBrowserRequest(client, Request.Builder().url("https://8.8.8.8/").build())
                }
                assertTrue("$code $target", result.exceptionOrNull() is UnknownHostException)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun `relative and cross origin public redirects reach final response`() {
        val seen = mutableListOf<String>()
        val client = client { request ->
            seen += request.url.toString()
            when (seen.size) {
                1 -> response(request, 302, "/next")
                2 -> response(request, 307, "http://1.1.1.1/final")
                else -> response(request, 200)
            }
        }
        executeBrowserRequest(client, Request.Builder().url("https://8.8.8.8/start").build()).use {
            assertEquals(200, it.code)
        }
        assertEquals(listOf("https://8.8.8.8/start", "https://8.8.8.8/next", "http://1.1.1.1/final"), seen)
    }

    @Test fun `redirect loop is bounded`() {
        var calls = 0
        val client = client { request -> calls++; response(request, 302, "/loop") }
        val result = runCatching {
            executeBrowserRequest(client, Request.Builder().url("https://8.8.8.8/").build())
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(21, calls)
    }

    @Test fun `unsupported redirect scheme fails closed`() {
        var calls = 0
        val client = client { request -> calls++; response(request, 302, "file:///etc/passwd") }
        val result = runCatching {
            executeBrowserRequest(client, Request.Builder().url("https://8.8.8.8/").build())
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, calls)
    }
}
