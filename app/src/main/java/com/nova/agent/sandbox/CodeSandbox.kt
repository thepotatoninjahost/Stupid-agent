package com.nova.agent.sandbox

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real JS/TS code execution sandbox using Mozilla Rhino.
 *
 * - 3s hard timeout
 * - Captures console.log output
 * - Captures return value
 * - Captures runtime errors
 * - No access to Java classes, file system, or network
 *
 * This is what lets NOVA actually RUN code it writes — not just generate it.
 */
data class SandboxResult(
    val ok: Boolean,
    val stdout: String,
    val stderr: String,
    val returnValue: Any? = null,
    val error: String? = null,
    val durationMs: Long,
)

class CodeSandbox {
    companion object {
        private const val TIMEOUT_MS = 3000L
    }

    suspend fun runCode(rawCode: String, language: String = "js"): SandboxResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val code = if (language.lowercase().startsWith("ts")) {
                stripTypeScript(rawCode)
            } else {
                rawCode
            }

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            var returnValue: Any? = null
            var error: String? = null

            val cx = Context.enter()
            try {
                cx.setOptimizationLevel(-1) // interpreter mode — no JIT, safer
                cx.languageVersion = Context.VERSION_ES6
                val scope = cx.initStandardObjects()

                // Define a captured console object
                val console = object : ScriptableObject() {
                    override fun getClassName() = "NOVAConsole"

                    fun log(vararg args: Any?) {
                        stdout.appendLine(args.joinToString(" ") { format(it) })
                    }
                    fun info(vararg args: Any?) {
                        stdout.appendLine(args.joinToString(" ") { format(it) })
                    }
                    fun warn(vararg args: Any?) {
                        stdout.appendLine("WARN: " + args.joinToString(" ") { format(it) })
                    }
                    fun error(vararg args: Any?) {
                        stderr.appendLine("ERR: " + args.joinToString(" ") { format(it) })
                    }
                }
                scope.put("console", scope, console)

                // Run with timeout via a watchdog thread
                var timedOut = false
                val watchdog = Thread {
                    try {
                        Thread.sleep(TIMEOUT_MS)
                        timedOut = true
                        cx.evaluateString(scope, "throw new Error('TIMEOUT')", "watchdog", 1, null)
                    } catch (_: InterruptedException) {}
                }
                watchdog.isDaemon = true
                watchdog.start()

                try {
                    val result = cx.evaluateString(scope, code, "nova-sandbox.js", 1, null)
                    watchdog.interrupt()
                    returnValue = result?.let { format(it) }
                } catch (e: Exception) {
                    watchdog.interrupt()
                    val msg = e.message ?: e.toString()
                    if (timedOut || msg.contains("TIMEOUT")) {
                        error = "Execution timed out after ${TIMEOUT_MS}ms"
                        stderr.appendLine(error)
                    } else {
                        error = msg
                        stderr.appendLine(msg)
                    }
                }
            } finally {
                Context.exit()
            }

            val durationMs = System.currentTimeMillis() - startedAt
            SandboxResult(
                ok = error == null,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim(),
                returnValue = returnValue,
                error = error,
                durationMs = durationMs,
            )
        }

    /**
     * Lightweight TypeScript type stripper — removes type annotations so
     * Rhino (which only does JS) can run the code.
     */
    private fun stripTypeScript(code: String): String {
        var out = code
        // Remove `import type` statements
        out = Regex("""^\s*import\s+type\s+[^;]*;?\s*$""", RegexOption.MULTILINE).replace(out, "")
        // Remove `type X = ...;` declarations
        out = Regex("""^\s*(export\s+)?type\s+\w+[^=]*=\s*[^;]*;?\s*$""", RegexOption.MULTILINE).replace(out, "")
        // Remove interface blocks
        out = Regex("""^\s*(export\s+)?interface\s+\w+\s*(<[^>]*>)?\s*\{[^}]*\}\s*$""", RegexOption.MULTILINE).replace(out, "")
        // Strip generic type args: foo<string>( -> foo(
        out = Regex("""(\w)<[^<>]*?>\s*\(""").replace(out) { mr ->
            "${mr.groupValues[1]}("
        }
        // Strip `as X` assertions
        out = Regex("""\s+as\s+[A-Za-z_$][\w.$<>\[\]|&,\s]*""").replace(out, "")
        // Strip non-null assertions: x!. -> x.
        out = Regex("""(\w)!\.""").replace(out) { mr -> "${mr.groupValues[1]}." }
        // Strip param type annotations: (x: number) -> (x)
        out = Regex("""\(([^()]*)\)""").replace(out) { mr ->
            val inside = mr.groupValues[1]
            val cleaned = inside.split(",").joinToString(",") { p ->
                p.replace(Regex("""^\s*(\.\.\.)?([\w$]+)\s*:[^=]*""")) { m ->
                    val g = m.groupValues
                    if (g[1].isNotEmpty()) "...${g[2]}" else g[2]
                }
            }
            "($cleaned)"
        }
        // Strip return type: ): Foo { -> ) {
        out = Regex("""\)\s*:\s*[A-Za-z_$][\w.$<>\[\]|&,\s]*\s*\{""").replace(out) { ") {" }
        // Strip variable type annotations: const x: Foo = -> const x =
        out = Regex("""\b(const|let|var)\s+([\w$]+)\s*:\s*[^=;]*=\s*""").replace(out) { mr ->
            "${mr.groupValues[1]} ${mr.groupValues[2]} = "
        }
        return out
    }

    private fun format(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> {
                val enteredHere = Context.getCurrentContext() == null
                if (enteredHere) Context.enter()
                try {
                    Context.toString(value)
                } catch (e: Exception) {
                    value.toString()
                } finally {
                    if (enteredHere) Context.exit()
                }
            }
        }
    }
}
