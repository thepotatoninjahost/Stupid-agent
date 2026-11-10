package host.vorv.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class WebFetchDestinationTest {
    private class RecordingSockets : SocketFactory() {
        var attempts = 0
        override fun createSocket(): Socket {
            attempts++
            throw IOException("Unexpected connection attempt")
        }
        override fun createSocket(h: String, p: Int) = createSocket()
        override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int) = createSocket()
        override fun createSocket(h: InetAddress, p: Int) = createSocket()
        override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int) = createSocket()
    }

    private val blocked = listOf(
        "localhost", "metadata.google.internal", "printer.local",
        "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1",
        "169.254.169.254", "0.0.0.0", "100.64.0.1", "224.0.0.1",
        "[::1]", "[::]", "[fe80::1]", "[fd00::1]", "[::ffff:127.0.0.1]",
    )

    private fun fetch(client: okhttp3.OkHttpClient, url: String) = runBlocking {
        WebFetchTool(client).run(JsonObject().apply { addProperty("url", url) }, ToolContext("t", 1, null))
    }

    @Test fun `production client blocks private literals before opening sockets`() {
        val sockets = RecordingSockets()
        val client = publicWebFetchClient().newBuilder().socketFactory(sockets).build()
        for (scheme in listOf("http", "https")) for (host in blocked) {
            val result = fetch(client, "$scheme://$host/")
            assertFalse("$scheme://$host", result.ok)
            assertTrue(result.error.orEmpty(), result.error.orEmpty().contains("network guard"))
        }
        assertEquals(0, sockets.attempts)
    }

    @Test fun `every redirect target is guarded before connection`() {
        for (code in listOf(300, 301, 302, 303, 307, 308)) for (host in blocked) {
            val sockets = RecordingSockets()
            val client = publicWebFetchClient().newBuilder().socketFactory(sockets)
                .addInterceptor { chain ->
                    if (chain.request().url.host == "example.com") {
                        response(chain, code, "https://$host/")
                    } else chain.proceed(chain.request())
                }.build()
            val result = fetch(client, "https://example.com/")
            assertFalse(result.ok)
            assertTrue(result.error.orEmpty(), result.error.orEmpty().contains("network guard"))
            assertEquals(0, sockets.attempts)
        }
    }

    @Test fun `public literals relative redirects and public redirects still work`() {
        val visited = mutableListOf<String>()
        val client = publicWebFetchClient().newBuilder().addInterceptor { chain ->
            visited += chain.request().url.toString()
            when (visited.size) {
                1 -> response(chain, 302, "/next")
                2 -> response(chain, 307, "https://[2606:4700:4700::1111]/")
                else -> response(chain, 200)
            }
        }.build()
        assertTrue(fetch(client, "https://8.8.8.8/").ok)
        assertEquals(listOf("https://8.8.8.8/", "https://8.8.8.8/next",
            "https://[2606:4700:4700::1111]/"), visited)
    }

    @Test fun `hostname DNS guard remains enabled`() {
        assertSame(NetworkGuard.PublicDns, publicWebFetchClient().dns)
    }

    @Test fun `redirect loops are bounded`() {
        var requests = 0
        val client = publicWebFetchClient().newBuilder().addInterceptor { chain ->
            requests++
            response(chain, 302, "/loop")
        }.build()
        val result = fetch(client, "https://example.com/")
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("Too many redirects"))
        assertEquals(21, requests)
    }

    private fun response(chain: Interceptor.Chain, code: Int, location: String? = null): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body("ok".toResponseBody()).apply {
                if (location != null) header("Location", location)
            }.build()
}
