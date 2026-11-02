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
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * browser_automate — drive a real in-app WebView via JS injection
 * (audit finding C-4 hardening; per-navigation guard added for H-3/M-1).
 *
 * v0.6.6 security posture:
 *  - URL scheme allowlist: only http/https loads, ever (about:blank is
 *    allowed internally for teardown).
 *  - allowFileAccess / allowContentAccess / allowFileAccessFromFileURLs /
 *    allowUniversalAccessFromFileURLs all explicitly FALSE — no file:// or
 *    content:// reads.
 *  - Mixed content blocked (MIXED_CONTENT_NEVER_ALLOW).
 *  - EVERY navigation is host-checked (audit H-3): a [GuardWebClient] is
 *    installed on the WebView and validates the host in
 *    shouldOverrideUrlLoading AND doUpdateVisitedHistory — redirects and
 *    JS location changes can no longer walk around the pre-load check the
 *    way they did in v0.6.5.
 *  - DNS-rebinding TOCTOU closed for GET traffic (audit M-1): all GET
 *    subresources and top-frame loads are PROXIED through OkHttp wired to
 *    [NetworkGuard.PublicDns], which re-resolves every hop (including
 *    redirects) and refuses private addresses at connect time. Non-GET
 *    requests cannot be proxied (Android does not expose the request body
 *    to shouldInterceptRequest) and fall back to navigation-level checks —
 *    the residual window is documented in SECURITY.md.
 *  - JS snippets are built with Gson JSON string interpolation, not
 *    hand-rolled escaping (the old escape() missed backticks and ${}).
 *  - destroy() releases the WebView when the ViewModel clears.
 */
class BrowserAutomateTool(private val appContext: Context) : Tool {

    override val name = "browser_automate"
    override val description =
        "Automate a real in-app browser (http/https pages only). Actions: 'load' a URL, " +
            "'click' a CSS selector, 'type' text into a CSS selector, 'extract' text from the " +
            "page or a selector, 'run' JavaScript. Local file and private-network addresses " +
            "are blocked on every navigation, redirect included."

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewRef = AtomicReference<WebView?>(null)
    private val gson = Gson()

    /** Set by the guard client when a navigation was refused; cleared per action. */
    private val lastBlockReason = AtomicReference<String?>(null)

    /** Guarded OkHttp client: PublicDns re-resolves and refuses private addresses on every hop. */
    private val proxyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(NetworkGuard.PublicDns)
            .build()
    }

    /**
     * The WebViewClient installed ONCE for the lifetime of the WebView.
     * It performs the per-navigation host checks (H-3) and proxies GET
     * traffic through the guarded OkHttp stack (M-1). Page-finish
     * notification for loadPage() is delivered through a listener slot
     * instead of replacing the client (the v0.6.5 loadPage REPLACED
     * webViewClient on every load, which would have thrown this guard away).
     */
    private inner class GuardWebClient : WebViewClient() {
        var onPageFinishedListener: ((String?) -> Unit)? = null

        private fun hostBlockedReason(url: android.net.Uri): String? {
            val scheme = url.scheme?.lowercase()
            if (scheme == "about" && url.toString() == "about:blank") return null
            if (scheme != "http" && scheme != "https") {
                return "blocked navigation to non-http(s) URL: $url"
            }
            val host = url.host ?: return "blocked navigation with no host: $url"
            return try {
                NetworkGuard.checkHostAllowed(host)
                null
            } catch (e: UnknownHostException) {
                "blocked navigation to private/loopback host '$host': ${e.message}"
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            request ?: return false
            val reason = hostBlockedReason(request.url)
            if (reason != null) {
                lastBlockReason.set(reason)
                return true // refuse to load; the tool reports the block honestly
            }
            return false // allowed: the fetch itself is proxied/checked in shouldInterceptRequest
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            // Catches JS-driven navigations (location.href) that do not
            // always route through shouldOverrideUrlLoading.
            if (url == null) return
            val reason = hostBlockedReason(android.net.Uri.parse(url))
            if (reason != null) {
                lastBlockReason.set(reason)
                view?.stopLoading()
            }
            super.doUpdateVisitedHistory(view, url, isReload)
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            request ?: return null
            val url = request.url
            val scheme = url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return blockedResponse("non-http(s) request refused")

            // Fast host-level refusal (blocked names/literals) …
            try {
                NetworkGuard.checkHostAllowed(url.host ?: return blockedResponse("no host"))
            } catch (e: UnknownHostException) {
                return blockedResponse(e.message ?: "host not allowed")
            }

            // … and the real TOCTOU killer: proxy the fetch through OkHttp +
            // PublicDns so the address is validated AT CONNECT TIME, for
            // every hop including redirects. Only GET can be proxied —
            // shouldInterceptRequest does not expose request bodies.
            if (!request.method.equals("GET", ignoreCase = true)) return null

            return try {
                val builder = okhttp3.Request.Builder().url(url.toString())
                request.requestHeaders?.forEach { (k, v) ->
                    // Let OkHttp own compression negotiation.
                    if (!k.equals("Accept-Encoding", ignoreCase = true)) builder.addHeader(k, v)
                }
                val resp = proxyClient.newCall(builder.build()).execute()
                val contentType = resp.header("Content-Type")
                val mime = contentType?.substringBefore(';')?.trim() ?: "application/octet-stream"
                val charset = contentType?.let {
                    Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
                }
                WebResourceResponse(mime, charset, resp.body?.byteStream()).apply {
                    setStatusCodeAndReasonPhrase(resp.code, resp.message)
                    responseHeaders = resp.headers.toMultimap()
                        .mapValues { (_, v) -> v.joinToString(", ") }
                        .toMutableMap()
                }
            } catch (e: Exception) {
                blockedResponse("guarded fetch failed: ${e.message}")
            }
        }

        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
            onPageFinishedListener?.let { cb ->
                onPageFinishedListener = null
                cb(finishedUrl)
            }
        }

        private fun blockedResponse(reason: String): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8",
                java.io.ByteArrayInputStream(reason.toByteArray())).apply {
                setStatusCodeAndReasonPhrase(403, "Blocked by Vorv network guard")
            }
    }

    /** Lazily create the WebView on the MAIN thread — WebViews must never be touched off-main. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(): WebView {
        webViewRef.get()?.let { return it }
        val client = GuardWebClient()
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
            // ---- H-3: per-navigation guard, installed for the WebView's lifetime ----
            webViewClient = client
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
            lastBlockReason.set(null)
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
                    val host = uri.host ?: ""
                    // Fast pre-check for an immediate, honest refusal. The
                    // GuardWebClient re-checks every subsequent navigation
                    // (redirects, JS moves) — this is not the only gate.
                    try {
                        NetworkGuard.checkHostAllowed(host)
                    } catch (e: java.net.UnknownHostException) {
                        return@withContext ToolResult(ok = false, output = "",
                            error = e.message, durationMs = System.currentTimeMillis() - t0)
                    }
                    val wv = obtainWebView()
                    loadPage(wv, url)
                    val block = lastBlockReason.getAndSet(null)
                    if (block != null) {
                        "Load of $url was stopped mid-navigation: $block. " +
                            "The page may be partially rendered; do not extract data from it."
                    } else {
                        "Loaded $url. Page title: ${wv.title ?: "(none)"}"
                    }
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
                    evalJs(wv, js).take(6000)
                }
                "run" -> {
                    val js = input.get("js")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "",
                            error = "missing js for run", durationMs = System.currentTimeMillis() - t0)
                    val wv = obtainWebView()
                    evalJs(wv, js).take(6000)
                }
                else -> return@withContext ToolResult(ok = false, output = "",
                    error = "unknown action: $action", durationMs = System.currentTimeMillis() - t0)
            }

            val block = lastBlockReason.getAndSet(null)
            val finalOutput = if (block != null) "$output\n[network guard: $block]" else output

            ToolResult(
                ok = true,
                output = finalOutput,
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "browser iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "browser_automate failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }

    /**
     * Suspend until the page finishes loading. 30s hard timeout. The guard
     * client is NOT replaced here (that would uninstall the H-3 checks) —
     * page completion is observed through its listener slot.
     */
    private suspend fun loadPage(wv: WebView, url: String) = withTimeout(30_000) {
        suspendCancellableCoroutine { cont ->
            val client = wv.webViewClient as? GuardWebClient
            client?.onPageFinishedListener = { cont.resume(Unit) }
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
}
