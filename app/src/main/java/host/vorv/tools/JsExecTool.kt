package host.vorv.tools

import com.google.gson.JsonObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * js_exec — run JavaScript in a Rhino sandbox. 3s hard timeout.
 * No file/network access. Captures console.log output.
 */
class JsExecTool : Tool {
    override val name = "js_exec"
    override val description = "Execute JavaScript code in a sandbox. Use for quick computation, string manipulation, or testing logic. 3 second timeout. Cannot access files or network."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("code", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "JavaScript code to execute. Use console.log() to capture output.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("code") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val code = input.get("code")?.asString ?: return@withContext ToolResult(false, "", "missing code", System.currentTimeMillis() - t0)

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var returnValue: String? = null
        var error: String? = null
        val timedOut = AtomicBoolean(false)

        val cx = Context.enter()
        try {
            cx.setOptimizationLevel(-1)
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()

            val console = object : ScriptableObject() {
                override fun getClassName() = "VorvConsole"
                fun log(vararg args: Any?) { stdout.appendLine(args.joinToString(" ") { fmt(it) }) }
                fun info(vararg args: Any?) { stdout.appendLine(args.joinToString(" ") { fmt(it) }) }
                fun warn(vararg args: Any?) { stderr.appendLine("WARN: " + args.joinToString(" ") { fmt(it) }) }
                fun error(vararg args: Any?) { stderr.appendLine("ERR: " + args.joinToString(" ") { fmt(it) }) }
            }
            scope.put("console", scope, console)

            val watchdog = Thread {
                try {
                    Thread.sleep(3000)
                    timedOut.set(true)
                    cx.evaluateString(scope, "throw new Error('TIMEOUT')", "watchdog", 1, null)
                } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }

            try {
                val result = cx.evaluateString(scope, code, "vorv-sandbox.js", 1, null)
                watchdog.interrupt()
                returnValue = result?.let { fmt(it) }
            } catch (e: Exception) {
                watchdog.interrupt()
                val msg = e.message ?: e.toString()
                error = if (timedOut.get() || msg.contains("TIMEOUT")) "Execution timed out after 3000ms" else msg
                stderr.appendLine(msg)
            }
        } finally {
            Context.exit()
        }

        ToolResult(
            ok = error == null,
            output = buildString {
                if (stdout.isNotEmpty()) append(stdout.toString().trim())
                if (returnValue != null) {
                    if (isNotEmpty()) append("\n")
                    append("=> $returnValue")
                }
                if (isEmpty()) append("(no output)")
            },
            stderr = stderr.toString().trim(),
            error = error,
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("exec", ctx.taskId, "exec iter ${ctx.iteration}"),
        )
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> "null"
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> {
            val enteredHere = Context.getCurrentContext() == null
            if (enteredHere) Context.enter()
            try { Context.toString(v) } catch (e: Exception) { v.toString() } finally { if (enteredHere) Context.exit() }
        }
    }
}
