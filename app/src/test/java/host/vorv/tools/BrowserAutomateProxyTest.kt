package host.vorv.tools

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BrowserAutomateProxyTest {
    private fun intercept(client: OkHttpClient, url: String = "https://8.8.8.8/script.js"): WebResourceResponse {
        val tool = BrowserAutomateTool(ApplicationProvider.getApplicationContext<Context>())
        // Replace only the transport; exercise the real interception and error handler.
        BrowserAutomateTool::class.java.getDeclaredField("proxyClient\$delegate").apply {
            isAccessible = true
            set(tool, lazy { client })
        }
        val guard = BrowserAutomateTool::class.java.getDeclaredField("guardClient").run {
            isAccessible = true
            get(tool) as WebViewClient
        }
        val request = object : WebResourceRequest {
            override fun getUrl() = Uri.parse(url)
            override fun isForMainFrame() = false
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        val response = guard.shouldInterceptRequest(null, request)
        assertNotNull("null would permit WebView to retry outside the guarded proxy", response)
        return response!!
    }

    @Test fun `private destination rejection during proxying blocks WebView fallback`() {
        val client = OkHttpClient.Builder().addInterceptor {
            // The same policy exception a redirect to a blocked host raises.
            NetworkGuard.PublicDns.lookup("localhost")
            error("private destination must be rejected")
        }.build()
        val response = intercept(client)
        assertEquals(502, response.statusCode)
        assertEquals("resource proxy failed", response.data.bufferedReader().use { it.readText() })
    }

    @Test fun `ordinary network failure also blocks WebView fallback`() {
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("connection failed") }.build()
        assertEquals(502, intercept(client).statusCode)
    }

    @Test fun `initial private destination remains blocked`() {
        val client = OkHttpClient.Builder().addInterceptor { error("must not send blocked request") }.build()
        assertEquals(403, intercept(client, "https://127.0.0.1/script.js").statusCode)
    }

    @Test fun `successful proxy response is preserved`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", "application/javascript; charset=utf-8")
                .body("script content".toResponseBody()).build()
        }.build()
        val response = intercept(client)
        assertEquals(200, response.statusCode)
        assertEquals("application/javascript", response.mimeType)
        assertEquals("script content", response.data.bufferedReader().use { it.readText() })
    }
}
