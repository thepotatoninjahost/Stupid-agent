package host.vorv.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * browser_automate — drive a real in-app WebView via JS injection
 * (audit finding C-4 hardening).
 *
 * v0.6.0 security posture:
 *  - URL scheme allowlist: only http/https loads, ever.
 *  - allowFileAccess / allowContentAccess / allowFileAccessFromFileURLs /
 *    allowUniversalAccessFromFileURLs all explicitly FALSE — no file:// or
 *    content:// reads.
 *  - Mixed content blocked (MIXED_CONTENT_NEVER_ALLOW).
 *  - JS snippets are built with Gson JSON string interpolation, not
 *    hand-rolled escaping (the old escape() missed backticks and ${}).
 *  - destroy() releases the WebView when the ViewModel clears (it previously
 *    leaked on activity recreation — audit performance section).
 *
 * v0.6.9 (audits H-3 + M-1) — per-navigation egress guard:
 *  - The OLD single pre-check on the initial URL left redirects and
 *    JS-initiated navigations completely unguarded. Now a GuardWebViewClient
 *    runs NetworkGuard.checkHostAllowed on EVERY navigation
 *    (shouldOverrideUrlLoading) and EVERY sub-resource request
 *    (shouldInterceptRequest — iframes, fetches, XHRs).
 *  - shouldInterceptRequest proxies the request through OkHttp wired to
 *    NetworkGuard.PublicDns — ONE resolution, checked against the private-
 *    network blocklist, used for the actual transfer. This closes the
 *    DNS-rebinding TOCTOU (M-1): the WebView can no longer connect to a
 *    different address than the one that was checked.
 *  - A blocked navigation fails the tool call with an honest error.
 */
class BrowserAutomateTool(private val appContext: Context) : Tool {

    override val name = "browser_automate"
    override val description =
        "Automate a real in-app browser (http/https pages only). Actions: 'load' a URL, " +
        "'click' a CSS selector, 'type' text into a CSS selector, 'extract' text from the " +
        "page or a selector, 'run' JavaScript. Local file and private-network addresses are blocked."

    override val timeoutMs: Long get() = 35_000L
    override val maxCallsPerTask: Int get() = 15

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "One of: load | click | type | extract | run")
            })
            add("url", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Required for action=load. Full http(s) URL.")
            })
            add("selector", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "CSS selector. Required for click/type; optional for extract.")
            })
            add("text", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Text to type. Required for action=type.")
            })
            add("js", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "JavaScript expression to evaluate. Required for action=run. Must return a value.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("action") })
    }

    /** The single guarded client — installed once, never replaced. */
    private val guardClient = GuardWebViewClient()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewRef = AtomicReference<WebView?>(null)
    private val gson = Gson()

    /**
     * OkHttp client used to proxy WebView requests (M-1): DNS resolution
     * goes through NetworkGuard.PublicDns — resolved once, checked against
     * the private-network blocklist, and used for the transfer. The WebView
     * cannot connect to a different address than the one that was checked.
     */
    private val proxyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(NetworkGuard.PublicDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * The per-navigation egress guard (audits H-3 + M-1). Every navigation
     * and every sub-resource request is checked; sub-resources are proxied
     * through the DNS-pinned OkHttp client. The last block reason is
     * surfaced to the tool caller.
     */
    @Volatile private var lastBlockReason: String? = null

    private inner class GuardWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            request?.url ?: return false
            return !checkAllowed(request.url)
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url ?: return null
            if (!checkAllowed(url)) {
                // A blocked sub-resource (iframe/fetch/XHR): serve an honest
                // empty 403 instead of letting the WebView fetch it.
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Vorv",
                    java.util.Collections.emptyMap(),
                    java.io.ByteArrayInputStream("blocked by network guard".toByteArray()))
            }
            // M-1: proxy through the DNS-pinned client so the connection uses
            // EXACTLY the addresses that were checked.
            return try {
                val req = okhttp3.Request.Builder().url(url.toString()).build()
                val resp = proxyClient.newCall(req).execute()
                val contentType = resp.header("Content-Type") ?: "application/octet-stream"
                val (mime, charset) = contentType.split(";", limit = 2)
                    .let { it[0].trim() to it.getOrNull(1)?.trim()?.removePrefix("charset=") }
                val headers = mutableMapOf<String, String>()
                for ((k, v) in resp.headers) headers[k] = v
                val body = resp.body?.byteStream()
                    ?: java.io.ByteArrayInputStream(ByteArray(0))
                WebResourceResponse(mime, charset, resp.code, resp.message, headers, body)
            } catch (e: Exception) {
                // Never fall back to WebView's network stack: a failure may be
                // PublicDns rejecting a redirect to a private destination.
                lastBlockReason = e.message ?: "resource proxy failed"
                WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway",
                    java.util.Collections.emptyMap(),
                    java.io.ByteArrayInputStream("resource proxy failed".toByteArray()))
            }
        }

        /** True when allowed; records the reason when blocked. */
        private fun checkAllowed(url: android.net.Uri): Boolean {
            val scheme = url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https" && scheme != null) {
                lastBlockReason = "navigation to non-http(s) URL blocked: $url"
                return false
            }
            val host = url.host ?: ""
            return try {
                NetworkGuard.checkHostAllowed(host)
                true
            } catch (e: java.net.UnknownHostException) {
                lastBlockReason = e.message ?: "host blocked: $host"
                false
            }
        }
    }

    /** Lazily create the WebView on the MAIN thread — WebViews must never be touched off-main. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(): WebView {
        webViewRef.get()?.let { return it }
        val wv = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkImage = true
            // ---- C-4 hardening: no local file/content access, ever ----
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // ---- H-3: the guarded client — every navigation checked ----
            webViewClient = this@BrowserAutomateTool.guardClient
        }
        webViewRef.set(wv)
        return wv
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.Main) {
        val t0 = System.currentTimeMillis()
        val action = input.get("action")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing action",
                durationMs = System.currentTimeMillis() - t0)

        try {
            val output: String = when (action.lowercase()) {
                "load" -> {
                    val url = input.get("url")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "",
                            error = "missing url for load", durationMs = System.currentTimeMillis() - t0)
                    val uri = android.net.Uri.parse(url)
                    val scheme = uri.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") {
                        return@withContext ToolResult(ok = false, output = "",
                            error = "Refusing to load URL with scheme '$scheme' — only http/https allowed.",
                            durationMs = System.currentTimeMillis() - t0)
                    }
                    // Initial pre-check (fast, honest failure before any load).
                    try {
                        NetworkGuard.checkHostAllowed(uri.host ?: "")
                    } catch (e: java.net.UnknownHostException) {
                        return@withContext ToolResult(ok = false, output = "",
                            error = e.message, durationMs = System.currentTimeMillis() - t0)
                    }
                    val wv = obtainWebView()
                    lastBlockReason = null
                    loadPage(wv, url)
                    // H-3: a redirect or JS navigation during the load may have
                    // been blocked by the guard client — surface it honestly.
                    lastBlockReason?.let { blocked ->
                        return@withContext ToolResult(ok = false, output = "",
                            error = "navigation blocked: $blocked",
                            durationMs = System.currentTimeMillis() - t0)
                    }
                    "Loaded $url. Page title: ${wv.title ?: "(none)"}"
                }
                "click" -> {
                    val selector = input.get("selector")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "",
                            error = "missing selector for click", durationMs = System.currentTimeMillis() - t0)
                    val wv = obtainWebView()
                    val sel = gson.toJson(selector)
                    evalJs(wv, "(function(){var el=document.querySelector($sel);if(!el){return 'NOT_FOUND'};el.click();return 'CLICKED';})()")
                }
                "type" -> {
                    val selector = input.get("selector")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "",
                            error = "missing selector for type", durationMs = System.currentTimeMillis() - t0)
                    val text = input.get("text")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "",
                            error = "missing text for type", durationMs = System.currentTimeMillis() - t0)
                    val wv = obtainWebView()
                    val sel = gson.toJson(selector)
                    val txt = gson.toJson(text)
                    evalJs(wv, "(function(){var el=document.querySelector($sel);if(!el){return 'NOT_FOUND'};el.value=$txt;el.dispatchEvent(new Event('input',{bubbles:true}));return 'TYPED';})()")
                }
                "extract" -> {
                    val wv = obtainWebView()
                    val selector = input.get("selector")?.takeIf { !it.isJsonNull }?.asString
                    val js = if (selector.isNullOrBlank()) {
                        "document.body.innerText"
                    } else {
                        val sel = gson.toJson(selector)
                        "(function(){var el=document.querySelector($sel);return el?el.innerText:'NOT_FOUND';})()"
                    }
                    // M-2 (code review, 2026-11): the cap now announces itself —
                    // a silently cut extraction reads as the COMPLETE page text.
                    capWithMarker(evalJs(wv, js), MAX_OUTPUT_CHARS)
                }
                "run" -> {
                    val js = input.get("js")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "",
                            error = "missing js for run", durationMs = System.currentTimeMillis() - t0)
                    val wv = obtainWebView()
                    capWithMarker(evalJs(wv, js), MAX_OUTPUT_CHARS)
                }
                else -> return@withContext ToolResult(ok = false, output = "",
                    error = "unknown action: $action", durationMs = System.currentTimeMillis() - t0)
            }

            ToolResult(
                ok = true,
                output = output,
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "browser iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "browser_automate failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }

    /**
     * Suspend until the page finishes loading. 30s hard timeout.
     * The GUARDED client stays installed — completion is detected through a
     * temporary onPageFinished hook layered on top (the guard keeps
     * intercepting navigations during and after the load).
     */
    private suspend fun loadPage(wv: WebView, url: String) = withTimeout(30_000) {
        suspendCancellableCoroutine { cont ->
            val original = guardClient
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?,
                ): Boolean = original.shouldOverrideUrlLoading(view, request)

                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?,
                ): WebResourceResponse? = original.shouldInterceptRequest(view, request)

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            wv.loadUrl(url)
            cont.invokeOnCancellation { wv.stopLoading() }
        }
    }

    /** Suspend until JS evaluation returns. 15s timeout. */
    private suspend fun evalJs(wv: WebView, js: String): String = withTimeout(15_000) {
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result ?: "null")
            }
            cont.invokeOnCancellation { /* evaluateJavascript has no cancel; timeout leaves it inert */ }
        }
    }

    /** Release the WebView. Called from ViewModel.onCleared(). */
    fun destroy() {
        mainHandler.post {
            webViewRef.get()?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.destroy()
                }
            }
            webViewRef.set(null)
        }
    }

    private companion object {
        /**
         * M-2 (code review, 2026-11): 6 000 before, same number now — but
         * [capWithMarker] appends the honest truncation marker instead of
         * silently cutting the extraction.
         */
        const val MAX_OUTPUT_CHARS = 6_000
    }
}
