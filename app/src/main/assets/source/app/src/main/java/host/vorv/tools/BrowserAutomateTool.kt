package host.vorv.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 *  - NetworkGuard.checkHostAllowed() pre-checks the host before every load:
 *    no localhost, LAN, link-local or metadata endpoints.
 *  - JS snippets are built with Gson JSON string interpolation, not
 *    hand-rolled escaping (the old escape() missed backticks and ${}).
 *  - destroy() releases the WebView when the ViewModel clears (it previously
 *    leaked on activity recreation — audit performance section).
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewRef = AtomicReference<WebView?>(null)
    private val gson = Gson()

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
            webViewClient = WebViewClient()
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
                    val host = uri.host ?: ""
                    try {
                        NetworkGuard.checkHostAllowed(host)
                    } catch (e: java.net.UnknownHostException) {
                        return@withContext ToolResult(ok = false, output = "",
                            error = e.message, durationMs = System.currentTimeMillis() - t0)
                    }
                    val wv = obtainWebView()
                    loadPage(wv, url)
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

    /** Suspend until the page finishes loading. 30s hard timeout. */
    private suspend fun loadPage(wv: WebView, url: String) = withTimeout(30_000) {
        suspendCancellableCoroutine { cont ->
            wv.webViewClient = object : WebViewClient() {
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
}
