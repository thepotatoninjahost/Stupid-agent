package host.vorv.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import host.vorv.computer.Computer
import host.vorv.computer.ComputerHome
import host.vorv.computer.ComputerProbe
import host.vorv.computer.JsEngine
import host.vorv.computer.PythonEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * computer_run — run a program on the app's own computer.
 *
 * This is the single entry point the model uses for "write code and execute
 * it" that does not involve Termux, a shell, or any external app. It picks a
 * runtime the APK actually ships (see [ComputerProbe]), runs the program with
 * a hard deadline, and reports real output — including the reason when a
 * runtime is unavailable, so the model cannot mistake refusal for success.
 */
class ComputerTool(private val appContext: Context) : Tool {

    override val name = "computer_run"
    override val description =
        "Run a program on the app's own computer (no Termux, no shell, no external app). " +
            "kind=js runs JavaScript (computation, algorithms, JSON/string work; no file or " +
            "network access); kind=python runs Python 3.11 in the sandboxed interpreter. " +
            "Use it to actually execute code you wrote and see the real output."

    override val timeoutMs: Long get() = 65_000L
    override val maxCallsPerTask: Int get() = 25

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("kind", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { add("js"); add("python") })
                addProperty("description", "Which runtime to use.")
            })
            add("code", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Program source. Use console.log()/print() to produce output.")
            })
            add("timeout_ms", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Optional budget, default 10000, max 60000.")
            })
        })
        add("required", JsonArray().apply { add("kind"); add("code") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val kind = input.get("kind")?.asString?.trim()?.lowercase()
                ?: return@withContext fail("kind is required (js or python)")
            val code = input.get("code")?.asString
                ?: return@withContext fail("code is required")
            val budget = (input.get("timeout_ms")?.asLong ?: 10_000L).coerceIn(1_000L, 60_000L)
            val started = System.currentTimeMillis()

            when (kind) {
                "js" -> runJs(code, budget, started)
                "python" -> runPython(input, ctx, budget, started)
                else -> fail("unknown kind '$kind' — use js or python")
            }
        }

    private suspend fun runJs(code: String, budget: Long, started: Long): ToolResult {
        val home = ComputerHome(ComputerProbe.home(appContext).root)
        val computer = Computer(home, listOf(JsEngine()), ComputerHome.DEFAULT_QUOTA)
        return when (val outcome = computer.run("js", code, budget)) {
            is Computer.Outcome.Ok -> ToolResult(
                ok = true,
                output = outcome.output.joinToString("\n").ifBlank { "(no output)" },
                durationMs = System.currentTimeMillis() - started,
                citation = Citation("exec", "computer:js", "js on device"),
            )
            is Computer.Outcome.TimedOut -> fail(
                "timed out after ${outcome.budgetMs} ms — output so far:\n" +
                    outcome.output.joinToString("\n"),
            )
            is Computer.Outcome.Failed -> fail("js failed: ${outcome.detail}")
            is Computer.Outcome.Refused -> fail(outcome.reason)
        }
    }

    /**
     * Python goes through the existing hardened `python_exec` path rather than
     * a second sandbox: one interpreter, one set of guards, one place to fix.
     */
    private suspend fun runPython(input: JsonObject, ctx: ToolContext, budget: Long, started: Long): ToolResult {
        val ready = ComputerProbe.capabilities(appContext).firstOrNull { it.id == "python" }
        if (ready?.ready != true) return fail(ready?.detail ?: "python is not available on this device")
        val delegated = JsonObject().apply {
            addProperty("code", input.get("code").asString)
            addProperty("timeout_ms", budget)
        }
        val result = PythonExecTool(appContext).run(delegated, ctx)
        return result.copy(durationMs = System.currentTimeMillis() - started)
    }

    private fun fail(message: String) = ToolResult(
        ok = false,
        output = "",
        durationMs = 0,
        error = message,
        stderr = message,
    )
}
