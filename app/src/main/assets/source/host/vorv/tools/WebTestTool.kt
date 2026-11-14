package host.vorv.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import host.vorv.computer.ComputerProbe
import host.vorv.web.WebAppHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * web_test — open a built web app inside Vorv and prove it works.
 *
 * The app under test is served over loopback, loaded in a real WebView and
 * driven by JavaScript the model wrote. The result is a verdict, not a
 * suggestion: anything other than every check passing with a clean console is
 * a failure, and a screenshot is kept so the user can see what was on screen.
 */
class WebTestTool(private val appContext: Context) : Tool {

    override val name = "web_test"
    override val description =
        "Open a web app you built on the app's own computer and test it for real. The project " +
            "is served over loopback and loaded in a real browser view on the phone; your script " +
            "runs inside the page and must return {ok:true, checks:[{name,pass,detail}]}. " +
            "Reports every failed check and every JavaScript error, and saves a screenshot. " +
            "Run this before telling the user a web app is finished — unit tests do not prove an app opens."

    override val timeoutMs: Long get() = 45_000L
    override val maxCallsPerTask: Int get() = 8

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("project", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Project folder on the app's computer, e.g. 'todo-app'.")
            })
            add("entry", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Entry file, default index.html.")
            })
            add("script", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description",
                    "JavaScript body evaluated in the page. Click things, read the DOM, assert. " +
                        "It must return {ok:true, checks:[{name:'...',pass:true,detail:'...'}]}.")
            })
            add("timeout_ms", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Optional budget, default 20000, max 40000.")
            })
        })
        add("required", JsonArray().apply { add("project"); add("script") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val name = input.get("project")?.asString?.trim()?.trim('/')
                ?: return@withContext fail("project is required (folder name on the computer)")
            val script = input.get("script")?.asString
                ?: return@withContext fail("script is required")
            val entry = input.get("entry")?.asString?.takeIf { it.isNotBlank() } ?: "index.html"
            val budget = (input.get("timeout_ms")?.asLong ?: 20_000L).coerceIn(3_000L, 40_000L)

            val home = ComputerProbe.home(appContext)
            val dir = File(home.projectsDir, name).canonicalFile
            if (!dir.path.startsWith(home.projectsDir.canonicalFile.path + File.separator)) {
                return@withContext fail("'$name' is not a project on the app's computer")
            }
            if (!dir.isDirectory) return@withContext fail("project '$name' does not exist — build it first")

            val result = try {
                WebAppHarness(appContext).verify(dir, entry, script, budget)
            } catch (e: Exception) {
                return@withContext fail("could not open the app: ${e.message ?: e.javaClass.simpleName}")
            }
            val shot = result.screenshot?.let { "screenshot saved: ${it.path}" } ?: "no screenshot"
            ToolResult(
                ok = result.verdict.ok,
                output = "${result.verdict.report()}\nurl: ${result.url}\n$shot",
                durationMs = System.currentTimeMillis() - started,
                error = result.verdict.reason.takeIf { !result.verdict.ok },
                citation = Citation("exec", "web_test:$name", "app opened + exercised"),
            )
        }

    private fun fail(message: String) = ToolResult(
        ok = false,
        output = "",
        durationMs = 0,
        error = message,
        stderr = message,
    )
}
