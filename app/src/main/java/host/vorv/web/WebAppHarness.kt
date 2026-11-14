package host.vorv.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Opens a built app inside Vorv, the way a user would, and exercises it.
 *
 * This is the difference between "the unit tests pass" and "the app works when
 * you open it". The project directory is served over loopback (so `fetch`,
 * modules and relative URLs behave as they will for the user), loaded into a
 * real Chromium WebView on the phone, driven by a script the model wrote, and
 * judged by [WebVerdict] — including any JavaScript error the page logged.
 *
 * Scope, stated plainly: this proves web apps (HTML/CSS/JS). It cannot launch
 * an Android APK's UI — force-stopping and driving another app needs either a
 * JVM to build the APK or accessibility privileges to puppet its screens.
 */
class WebAppHarness(private val context: Context) {

    data class Result(val verdict: WebVerdict, val screenshot: File?, val url: String)

    /**
     * @param projectDir the built app's directory (must contain index.html)
     * @param entry relative entry file, default index.html
     * @param script JavaScript evaluated in the page; it must *return* an
     *        object `{ok:Boolean, checks:[{name,pass,detail}]}`.
     * @param screenshot when true, a PNG of the rendered page is written next
     *        to the project so the user can see what was on screen.
     */
    suspend fun verify(
        projectDir: File,
        entry: String = "index.html",
        script: String,
        timeoutMs: Long = 20_000,
        screenshot: Boolean = true,
    ): Result {
        require(projectDir.isDirectory) { "no such project directory: $projectDir" }
        val server = LocalAppServer(projectDir).start()
        val url = server.baseUrl + entry.trimStart('/')
        return try {
            withTimeoutOrNull(timeoutMs) { drive(server, url, script, projectDir, screenshot) }
                ?: Result(
                    WebVerdict(
                        ok = false, checks = emptyList(), consoleErrors = emptyList(),
                        reason = "the page did not finish loading and answering within ${timeoutMs}ms",
                    ),
                    null, url,
                )
        } finally {
            server.close()
        }
    }

    private suspend fun drive(
        server: LocalAppServer,
        url: String,
        script: String,
        projectDir: File,
        screenshot: Boolean,
    ): Result = withContext(Dispatchers.Main) {
        val errors = mutableListOf<String>()
        val loaded = AtomicBoolean(false)
        val view = createWebView(errors)
        try {
            val pageLoaded = suspendCancellableCoroutine { cont ->
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView?, u: String?) {
                        if (loaded.compareAndSet(false, true)) cont.resume(true)
                    }

                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                        // The app being tested stays on its own loopback origin;
                        // it may not navigate the harness anywhere else.
                        val target = r?.url?.toString() ?: return true
                        return !target.startsWith(server.baseUrl)
                    }

                    override fun onReceivedError(
                        v: WebView?, r: WebResourceRequest?, e: android.webkit.WebResourceError?,
                    ) {
                        if (r?.isForMainFrame == true) {
                            errors += "main frame failed to load: ${e?.description ?: "unknown"}"
                            if (loaded.compareAndSet(false, true)) cont.resume(false)
                        }
                    }
                }
                view.loadUrl(url)
                cont.invokeOnCancellation { loaded.set(true) }
            }

            val answer = if (!pageLoaded) {
                null
            } else {
                evaluateQuietly(view, wrap(script))
            }
            val shot = if (screenshot) capture(view, projectDir) else null
            val verdict = WebVerdict.fromEvaluation(
                answer,
                errors.toList(),
                shot?.let { "screenshot: ${it.name}" },
            )
            Result(verdict, shot, url)
        } finally {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
    }

    /**
     * Wraps the model's script so a throw becomes data instead of silence, and
     * so the harness always receives a JSON string to parse.
     */
    private fun wrap(script: String): String = """
        (function () {
          try {
            var r = (function () { $script })();
            if (r === undefined) return JSON.stringify({error: 'test script returned nothing'});
            return typeof r === 'string' ? r : JSON.stringify(r);
          } catch (e) {
            return JSON.stringify({error: String((e && e.stack) || (e && e.message) || e)});
          }
        })()
    """.trimIndent()

    private suspend fun evaluateQuietly(view: WebView, js: String): String? =
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(js) { value -> cont.resume(value) }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(errors: MutableList<String>): WebView = WebView(context).apply {
        setBackgroundColor(android.graphics.Color.WHITE)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Only the loopback origin is reachable: no file://, no content://,
            // and no network the project itself did not ask for.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    errors += "${m.message()} (${m.sourceId()}:${m.lineNumber()})"
                }
                return true
            }
        }
        measure(1080, 1920)
        layout(0, 0, 1080, 1920)
    }

    /** Renders the page to a PNG the user can actually look at. */
    private fun capture(view: WebView, projectDir: File): File? = try {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        // Drawn synchronously on the main thread (the caller is already there):
        // posting it would let the bitmap be written before anything was
        // painted, and a blank "proof" screenshot is worse than none.
        view.draw(Canvas(bitmap))
        val out = File(projectDir, "test-screenshot.png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        bitmap.recycle()
        out
    } catch (e: Exception) {
        null
    }
}
