package host.vorv.tools

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * python_exec — run Python 3.11 locally via Chaquopy in a layered sandbox
 * (audit finding C-2).
 *
 * Sandbox layers (implemented in vorv_runner.py, self-tested by
 * test_vorv_sandbox.py in CI):
 *   1. CPython audit hook blocking file/process/socket operations
 *   2. Restricted __builtins__ (no open/eval/exec/compile/__import__; dunder
 *      getattr guard)
 *   3. Import whitelist — pure-computation stdlib only (math, json, re,
 *      datetime, statistics, ...)
 *   4. Time budget via a trace watchdog
 *
 * HONEST capability statement (the v0.5.x description falsely claimed
 * "cannot access files outside the agent's workspace" and that the requests
 * package was available):
 *  - The 'requests' package is NOT available (Chaquopy ships stdlib only).
 *  - Network and file access are blocked by the sandbox, not merely by
 *    policy. Legitimate pure-computation code runs fine.
 *  - Residual limit: C-extension compute cannot be interrupted mid-call;
 *    such calls hard-timeout at the Kotlin join and leak a daemon thread.
 */
class PythonExecTool(private val appContext: android.content.Context) : Tool {
    override val name = "python_exec"
    override val description =
        "Execute Python 3.11 in a layered sandbox: pure computation with the standard " +
        "library only (math, json, re, datetime, statistics, collections, ...). " +
        "File access, network, subprocesses and non-whitelisted imports are BLOCKED " +
        "and will raise an error. Use print() to capture output. 30s max timeout."

    override val timeoutMs: Long get() = 35_000L
    override val maxCallsPerTask: Int get() = 30

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

    /**
     * The Python interpreter's sys.stdout is process-global; concurrent
     * executions would interleave their output. Every run is serialized.
     */
    private val runMutex = Mutex()

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val code = input.get("code")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing code",
                durationMs = System.currentTimeMillis() - t0)
        val timeoutMs = (input.get("timeout_ms")?.takeIf { !it.isJsonNull }?.asInt ?: 10_000)
            .coerceIn(500, 30_000)

        if (started.compareAndSet(false, true)) {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            } catch (e: Exception) {
                return@withContext ToolResult(ok = false, output = "",
                    error = "Python init failed: ${e.message}",
                    durationMs = System.currentTimeMillis() - t0)
            }
        }

        runMutex.withLock {
            try {
                val py = Python.getInstance()
                val runner = py.getModule("vorv_runner")
                    ?: return@withContext ToolResult(ok = false, output = "",
                        error = "vorv_runner module not found",
                        durationMs = System.currentTimeMillis() - t0)

                // Run in a separate thread so we can enforce a hard wall-clock cap
                // (covers C-extension stalls the Python-side watchdog can't interrupt).
                val resultRef = java.util.concurrent.atomic.AtomicReference<com.chaquo.python.PyObject?>(null)
                val errRef = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

                val worker = Thread {
                    try {
                        resultRef.set(runner.callAttr("run_code", code, timeoutMs))
                    } catch (e: Throwable) {
                        errRef.set(e)
                    }
                }.apply { isDaemon = true; start() }

                worker.join((timeoutMs + 3_000).toLong())
                if (worker.isAlive) {
                    return@withContext ToolResult(
                        ok = false,
                        output = "",
                        error = "Python execution hard-timed-out after ${timeoutMs}ms " +
                            "(uninterruptible native call; the sandbox trace watchdog also fired).",
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
                    ?: return@withContext ToolResult(ok = false, output = "",
                        error = "no result returned",
                        durationMs = System.currentTimeMillis() - t0)

                val ok = result["ok"]?.toBoolean() ?: false
                val stdout = result["stdout"]?.toString() ?: ""
                val stderr = result["stderr"]?.toString() ?: ""
                val errorStr = result["error"]?.toString()
                val error = errorStr?.takeIf { it != "None" && it.isNotBlank() }

                ToolResult(
                    ok = ok,
                    output = stdout.ifBlank { if (ok) "(no stdout)" else "" },
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
}
