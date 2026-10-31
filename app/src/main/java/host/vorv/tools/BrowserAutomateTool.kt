package host.vorv.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * browser_automate — drive a real in-app WebView via JS injection.
 * Can: load a URL, click an element, type into a field, extract text, run arbitrary JS.
 * The WebView lives on the main thread; tool calls suspend until the page/JS completes.
 */
class BrowserAutomateTool(private val appContext: Context) : Tool {

    override val name = "browser_automate"
    override val description = "Automate a real in-app browser. Actions: 'load' a URL, 'click' a CSS selector, 'type' text into a CSS selector, 'extract' text from the page or a selector, 'run' arbitrary JavaScript. Each call returns the page's current text or the JS result."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "One of: load | click | type | extract | run")
            })
            add("url", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Required for action=load. Full URL including https://")
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

    /** Lazily create the WebView on the MAIN thread — WebViews must never be touched off-main. */
    private fun obtainWebView(): WebView {
        webViewRef.get()?.let { return it }
        return mainHandler.let { h ->
            // We're always called from Dispatchers.Main below, so we're already on main.
            @SuppressLint("SetJavaScriptEnabled")
            val wv = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.blockNetworkImage = true
                webViewClient = WebViewClient()
            }
            webViewRef.set(wv)
            wv
        }
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.Main) {
        val t0 = System.currentTimeMillis()
        val action = input.get("action")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing action", durationMs = System.currentTimeMillis() - t0)

        try {
            val output: String = when (action.lowercase()) {
                "load" -> {
                    val url = input.get("url")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "", error = "missing url for load", durationMs = System.currentTimeMillis() - t0)
                    val wv = obtainWebView()
                    loadPage(wv, url)
                    "Loaded $url. Page title: ${wv.title ?: "(none)"}"
                }
                "click" -> {
                    val selector = input.get("selector")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "", error = "missing selector for click", durationMs = System.currentTimeMillis() - t0)
                    val wv = obtainWebView()
                    evalJs(wv, "(function(){var el=document.querySelector(${escape(selector)});if(!el){return 'NOT_FOUND'};el.click();return 'CLICKED';})()")
                }
                "type" -> {
                    val selector = input.get("selector")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "", error = "missing selector for type", durationMs = System.currentTimeMillis() - t0)
                    val text = input.get("text")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "", error = "missing text for type", durationMs = System.currentTimeMillis() - t0)
                    val wv = obtainWebView()
                    evalJs(wv, "(function(){var el=document.querySelector(${escape(selector)});if(!el){return 'NOT_FOUND'};el.value=${escape(text)};el.dispatchEvent(new Event('input',{bubbles:true}));return 'TYPED';})()")
                }
                "extract" -> {
                    val wv = obtainWebView()
                    val selector = input.get("selector")?.takeIf { !it.isJsonNull }?.asString
                    val js = if (selector.isNullOrBlank()) {
                        "document.body.innerText"
                    } else {
                        "(function(){var el=document.querySelector(${escape(selector)});return el?el.innerText:'NOT_FOUND';})()"
                    }
                    evalJs(wv, js).take(6000)
                }
                "run" -> {
                    val js = input.get("js")?.asString
                        ?: return@withContext ToolResult(ok = false, output = "", error = "missing js for run", durationMs = System.currentTimeMillis() - t0)
                    val wv = obtainWebView()
                    evalJs(wv, js).take(6000)
                }
                else -> return@withContext ToolResult(ok = false, output = "", error = "unknown action: $action", durationMs = System.currentTimeMillis() - t0)
            }

            ToolResult(
                ok = true,
                output = output,
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "browser iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "browser_automate failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
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
            cont.invokeOnCancellation { }
        }
    }

    /** Suspend until JS evaluation returns. 15s timeout. */
    private suspend fun evalJs(wv: WebView, js: String): String = withTimeout(15_000) {
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result ?: "null")
            }
            cont.invokeOnCancellation { }
        }
    }

    private fun escape(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
