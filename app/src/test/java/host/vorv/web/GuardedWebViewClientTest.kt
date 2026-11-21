package host.vorv.web

import android.net.Uri
import android.webkit.WebResourceRequest
import host.vorv.tools.BrowserAutomateTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * H-3 (code review, 2026-11): the web harness that runs a project's own app
 * never checked sub-resource requests, so a page under test could fetch any
 * host. These tests pin the two predicates — the harness's origin rule and
 * `browser_automate`'s guard — and the shared client that enforces them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuardedWebViewClientTest {

    private val base = Uri.parse("http://127.0.0.1:4321/")

    // ------------------------------------------------- harness origin predicate

    @Test
    fun `same origin sub-resources are allowed`() {
        assertTrue(LocalAppOrigin.allows(Uri.parse("http://127.0.0.1:4321/app.js"), base))
        assertTrue(LocalAppOrigin.allows(Uri.parse("http://127.0.0.1:4321/css/a.css?v=2"), base))
    }

    @Test
    fun `another host or port is refused`() {
        assertFalse(LocalAppOrigin.allows(Uri.parse("http://10.0.0.1/x.png"), base))
        assertFalse(LocalAppOrigin.allows(Uri.parse("http://127.0.0.1:9999/x"), base))
        assertFalse(LocalAppOrigin.allows(Uri.parse("https://127.0.0.1:4322/app.js"), base))
    }

    @Test
    fun `the userinfo trick does not pass for the app origin`() {
        // startsWith("http://127.0.0.1:4321") would pass this and download from evil.example.
        val url = Uri.parse("http://127.0.0.1:4321@evil.example/x")
        assertEquals("evil.example", url.host)
        assertFalse(LocalAppOrigin.allows(url, base))
    }

    @Test
    fun `local schemes a page may use are left to the page`() {
        for (raw in listOf("data:text/plain,hi", "blob:http://127.0.0.1:4321/abc", "about:blank")) {
            assertTrue(raw, LocalAppOrigin.allows(Uri.parse(raw), base))
        }
    }

    // ------------------------------------------- browser_automate's own predicate

    @Test
    fun `browser_automate still refuses non-http schemes and private hosts`() {
        val tool = BrowserAutomateTool(RuntimeEnvironment.getApplication())
        assertFalse(tool.checkAllowed(Uri.parse("data:text/plain,hi")))
        assertFalse(tool.checkAllowed(Uri.parse("blob:http://x/y")))
        // NetworkGuard refuses loopback: unchanged behaviour, checked here so
        // the extraction did not quietly loosen it.
        assertFalse(tool.checkAllowed(Uri.parse("http://127.0.0.1:4321/app.js")))
        assertFalse(tool.checkAllowed(Uri.parse("http://169.254.169.254/latest/meta-data/")))
    }

    // ---------------------------------------------------------- shared client

    @Test
    fun `a blocked sub-resource gets an honest 403 and names the host`() {
        val recorded = mutableListOf<String>()
        val client = GuardedWebViewClient(
            allow = { url -> LocalAppOrigin.allows(url, base) },
            onBlocked = { recorded += it },
            client = okhttp3.OkHttpClient(),
        )
        val response = client.shouldInterceptRequest(v(), request("http://evil.example/x.png"))
        assertNotNull("a blocked request must never return null (that lets it through)", response)
        response!!
        assertEquals(403, response.statusCode)
        assertEquals("Blocked", response.reasonPhrase)
        val body = response.data.bufferedReader().readText()
        assertEquals("blocked by Vorv network guard", body)
        assertEquals(listOf("http://evil.example/x.png"), recorded)
        assertTrue(recorded.first().contains("evil.example"))
    }

    @Test
    fun `a blocked navigation is cancelled`() {
        val toolLike = GuardedWebViewClient(
            allow = { false },
            onBlocked = {},
            client = okhttp3.OkHttpClient(),
        )
        assertTrue(toolLike.shouldOverrideUrlLoading(v(), request("http://10.0.0.1/")))
        val permissive = GuardedWebViewClient(
            allow = { true },
            onBlocked = {},
            client = okhttp3.OkHttpClient(),
        )
        assertFalse(permissive.shouldOverrideUrlLoading(v(), request("http://10.0.0.1/")))
    }

    @Test
    fun `a missing request is not treated as blocked`() {
        val client = GuardedWebViewClient(
            allow = { false },
            onBlocked = {},
            client = okhttp3.OkHttpClient(),
        )
        assertEquals(null, client.shouldInterceptRequest(v(), null as WebResourceRequest?))
        assertFalse(client.shouldOverrideUrlLoading(v(), null as WebResourceRequest?))
    }

    // ---------------------------------------------------------------- plumbing

    private fun v(): android.webkit.WebView? = null

    private fun request(url: String): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = false
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }
}
