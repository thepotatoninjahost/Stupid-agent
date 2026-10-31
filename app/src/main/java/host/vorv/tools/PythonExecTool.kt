package host.vorv.tools

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * python_exec — run real Python 3.11 locally via Chaquopy.
 * Uses vorv_runner.py (in src/main/python/) for stdout capture + timeout.
 * Has 'requests' package available for HTTP from within Python.
 */
class PythonExecTool(private val appContext: android.content.Context) : Tool {
    override val name = "python_exec"
    override val description = "Execute Python 3.11 code locally. Use print() to capture output. 10s timeout. The 'requests' package is available. Cannot access files outside the agent's workspace."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("code", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Python code to execute. Use print() to write to stdout.")
            })
            add("timeout_ms", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Optional timeout in milliseconds. Default 10000, max 30000.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("code") })
    }

    private val started = AtomicBoolean(false)

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val code = input.get("code")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing code", durationMs = System.currentTimeMillis() - t0)
        val timeoutMs = (input.get("timeout_ms")?.takeIf { !it.isJsonNull }?.asInt ?: 10000)
            .coerceIn(500, 30000)

        if (started.compareAndSet(false, true)) {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            } catch (e: Exception) {
                return@withContext ToolResult(ok = false, output = "", error = "Python init failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
            }
        }

        try {
            val py = Python.getInstance()
            val runner = py.getModule("vorv_runner")
                ?: return@withContext ToolResult(ok = false, output = "", error = "vorv_runner module not found", durationMs = System.currentTimeMillis() - t0)

            // Run in a separate thread so we can enforce a hard timeout
            val resultRef = java.util.concurrent.atomic.AtomicReference<com.chaquo.python.PyObject?>(null)
            val errRef = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val timedOut = AtomicBoolean(false)

            val worker = Thread {
                try {
                    resultRef.set(runner.callAttr("run_code", code, timeoutMs))
                } catch (e: Throwable) {
                    errRef.set(e)
                }
            }.apply { isDaemon = true; start() }

            worker.join((timeoutMs + 1000).toLong())
            if (worker.isAlive) {
                timedOut.set(true)
                worker.interrupt()
            }

            if (timedOut.get()) {
                return@withContext ToolResult(
                    ok = false,
                    output = "",
                    error = "Python execution hard-timed-out after ${timeoutMs}ms",
                    durationMs = System.currentTimeMillis() - t0,
                )
            }

            errRef.get()?.let { e ->
                return@withContext ToolResult(
                    ok = false,
                    output = "",
                    error = "Python threw: ${e.message}",
                    durationMs = System.currentTimeMillis() - t0,
                )
            }

            val result = resultRef.get()
                ?: return@withContext ToolResult(ok = false, output = "", error = "no result returned", durationMs = System.currentTimeMillis() - t0)

            val ok = result["ok"]?.toBoolean() ?: false
            val stdout = result["stdout"]?.toString() ?: ""
            val stderr = result["stderr"]?.toString() ?: ""
            val errorStr = result["error"]?.toString()
            val error = errorStr?.takeIf { it != "None" && it.isNotBlank() }

            ToolResult(
                ok = ok,
                output = stdout.ifBlank { "(no stdout)" },
                stderr = stderr,
                error = error,
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "python iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(
                ok = false,
                output = "",
                error = "Python execution failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0,
            )
        }
    }
}
