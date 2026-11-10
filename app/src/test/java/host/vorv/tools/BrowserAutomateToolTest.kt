package host.vorv.tools

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BrowserAutomateToolTest {
    // A public numeric address passes the host guard without external DNS.
    // The test interceptor supplies responses without making network requests.
    private val original = "https://8.8.8.8/start"
    private val payload = "<script>localStorage.clear()</script>"

    private fun intercept(
        finalUrl: String,
        startUrl: String = original,
        fail: Boolean = false,
        onClose: () -> Unit = {},
    ): Pair<BrowserAutomateTool, WebResourceResponse?> {
        val tool = BrowserAutomateTool(ApplicationProvider.getApplicationContext<Context>())
        val client = OkHttpClient.Builder().addInterceptor {
            if (fail) throw IOException("simulated redirect failure")
            val source = object : ForwardingSource(Buffer().writeUtf8(payload)) {
                override fun close() {
                    onClose()
                    super.close()
                }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = payload.toByteArray().size.toLong()
                override fun source() = source
            }
            Response.Builder().request(Request.Builder().url(finalUrl).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/html; charset=utf-8").body(body).build()
        }.build()
        tool.javaClass.getDeclaredField("proxyClient\$delegate").apply {
            isAccessible = true
            set(tool, lazy { client })
        }
        val guard = tool.javaClass.getDeclaredField("guardClient").run {
            isAccessible = true
            get(tool) as WebViewClient
        }
        val request = object : WebResourceRequest {
            override fun getUrl() = Uri.parse(startUrl)
            override fun isForMainFrame() = true
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        return tool to guard.shouldInterceptRequest(null, request)
    }

    @Test fun `different host scheme or effective port is blocked and body closed`() {
        for (destination in listOf("https://1.1.1.1/evil", "http://8.8.8.8/evil", "https://8.8.8.8:444/evil")) {
            var closed = false
            val (tool, result) = intercept(destination, onClose = { closed = true })
            assertNotNull(result)
            assertEquals(403, result!!.statusCode)
            assertFalse(result.data.bufferedReader().use { it.readText() }.contains(payload))
            assertTrue("Rejected body must be closed", closed)
            val reason = tool.javaClass.getDeclaredField("lastBlockReason").run {
                isAccessible = true
                get(tool) as String
            }
            assertTrue(reason.contains("cross-origin redirect"))
        }
    }

    @Test fun `unchanged origin and same-origin redirect preserve body`() {
        for (destination in listOf(original, "https://8.8.8.8:443/next?query=1")) {
            var closed = false
            val (_, result) = intercept(destination, onClose = { closed = true })
            assertEquals(200, result!!.statusCode)
            assertFalse(closed)
            assertEquals(payload, result.data.bufferedReader().use { it.readText() })
            assertTrue(closed)
        }
    }

    @Test fun `http default port is treated as the same origin`() {
        val (_, result) = intercept("http://8.8.8.8:80/next", startUrl = "http://8.8.8.8/start")
        assertEquals(200, result!!.statusCode)
        result.data.close()
    }

    @Test fun `proxy failure cannot fall back to unguarded WebView request`() {
        val (_, result) = intercept(original, fail = true)
        assertNotNull(result)
        assertEquals(403, result!!.statusCode)
        result.data.close()
    }

    @Test fun `response close failure cannot fall back to unguarded WebView request`() {
        val (_, result) = intercept("https://1.1.1.1/evil", onClose = { throw IOException("close failed") })
        assertEquals(403, result!!.statusCode)
        result.data.close()
    }
}
