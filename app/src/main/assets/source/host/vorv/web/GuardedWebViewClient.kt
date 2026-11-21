package host.vorv.web

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.util.Collections

/**
 * H-3 (code review, 2026-11): one guarded WebView client for every browser
 * surface. `browser_automate` checked sub-resources; the web harness that runs
 * a project's own app did not, so a page under test could fetch any host.
 *
 * The policy belongs to the caller: [allow] is asked about EVERY request and
 * decides about non-http schemes too. This class only enforces the answer, and
 * proxies allowed requests through [client] (DNS-pinned by the caller) so the
 * connection uses exactly the address the policy vetted.
 *
 * [open] because a WebView holds one client and callers still need their own
 * lifecycle callbacks (`onPageFinished`, `onReceivedError`).
 */
open class GuardedWebViewClient(
    private val allow: (Uri) -> Boolean,
    private val onBlocked: (String) -> Unit,
    private val client: OkHttpClient,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val url = request?.url ?: return false
        if (allow(url)) return false
        onBlocked(url.toString())
        return true
    }

    /**
     * Runs on a WebView IO thread, not the main thread: [onBlocked] must be
     * thread-safe at the call site.
     */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        if (!allow(url)) {
            // A blocked sub-resource (iframe/fetch/XHR): serve an honest 403
            // instead of letting the WebView fetch it. Returning null here
            // would let the request through.
            onBlocked(url.toString())
            return WebResourceResponse(
                "text/plain", "utf-8", 403, "Blocked",
                Collections.emptyMap(),
                ByteArrayInputStream("blocked by Vorv network guard".toByteArray()),
            )
        }
        // Proxy through the caller's DNS-pinned client so the connection uses
        // EXACTLY the addresses that were checked.
        return try {
            val req = okhttp3.Request.Builder().url(url.toString()).build()
            val resp = client.newCall(req).execute()
            val contentType = resp.header("Content-Type") ?: "application/octet-stream"
            val (mime, charset) = contentType.split(";", limit = 2)
                .let { it[0].trim() to it.getOrNull(1)?.trim()?.removePrefix("charset=") }
            val headers = mutableMapOf<String, String>()
            for ((k, v) in resp.headers) headers[k] = v
            val body = resp.body?.byteStream()
                ?: ByteArrayInputStream(ByteArray(0))
            WebResourceResponse(mime, charset, resp.code, resp.message, headers, body)
        } catch (e: Exception) {
            null // network failure: let the WebView surface its own error
        }
    }
}
