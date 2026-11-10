package host.vorv.tools

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserAutomateToolTest {
    @Test fun `proxy failure returns blocked response instead of unguarded WebView fallback`() {
        val tool = BrowserAutomateTool(ApplicationProvider.getApplicationContext<Context>())
        val client = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor { throw IOException("proxy failed") }.build()
        // Replace the lazy transport without changing the production API.
        tool.javaClass.getDeclaredField("proxyClient\$delegate").apply {
            isAccessible = true
            set(tool, lazyOf(client))
        }
        val guard = tool.javaClass.getDeclaredField("guardClient").let {
            it.isAccessible = true
            it.get(tool) as WebViewClient
        }
        val request = object : WebResourceRequest {
            override fun getUrl() = Uri.parse("https://8.8.8.8/")
            override fun isForMainFrame() = true
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        val response = guard.shouldInterceptRequest(null, request)
        assertNotNull(response)
        assertEquals(403, response!!.statusCode)
        response.data.close()
    }
}
