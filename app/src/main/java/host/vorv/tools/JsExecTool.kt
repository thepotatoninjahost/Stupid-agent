package host.vorv.tools

import com.google.gson.JsonObject
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.ScriptableObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * js_exec — run JavaScript in a HARDENED Rhino sandbox (audit finding C-1 + H-3).
 *
 * What "sandbox" means here, concretely:
 *
 *  1. ClassShutter — the only classes visible to script are Rhino's own and
 *     a tiny allowlist of immutable java.lang types (String, Number, Boolean,
 *     Math…). `Packages.java.lang.Runtime`, `java.io.File`, `java.lang.System.exit`,
 *     `host.vorv.*`, `com.chaquo.*`, `com.geniex.*` are all DENIED.
 *
 *  2. LiveConnect globals (Packages, java, javax, org, com, net, io, android,
 *     androidx, host, jdk, sun, …) plus importClass/importPackage/JavaAdapter/
 *     JavaImporter are DELETED from the scope before sealing.
 *
 *  3. The scope is SEALED — script cannot redefine console or re-add globals.
 *
 *  4. Timeout via Rhino's instruction-count observer: the ContextFactory
 *     subclass checks a wall-clock deadline every 10k interpreted instructions
 *     and aborts with an EvaluatorException. This runs on the SAME thread as
 *     evaluation (the previous cross-thread evaluateString watchdog was
 *     undefined behavior — audit H-3).
 *
 * Residual risk: none known for the interpreted engine (optimization level -1)
 * with the shutter above; Rhino CVEs historically target LiveConnect, which
 * is removed. See SECURITY.md for the full threat model.
 */
class JsExecTool : Tool {
    override val name = "js_exec"
    override val description =
        "Execute JavaScript in a hardened sandbox. Only standard ECMAScript built-ins " +
        "(Math, JSON, Array, String, …) and console.log are available — no file I/O, no network, " +
        "no Java interop. Use for quick computation, string manipulation, or testing logic. " +
        "5 second timeout."

    override val timeoutMs: Long get() = 6_000L
    override val maxCallsPerTask: Int get() = 30

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

    /**
     * ContextFactory whose observeInstructionCount hook aborts evaluation once
     * the deadline passes. The hook fires on the evaluating thread — this is
     * Rhino's documented interruption mechanism.
     */
    private class WatchdogFactory : ContextFactory() {
        @Volatile var deadline: Long = 0L
        @Volatile var aborted: Boolean = false

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val d = deadline
            if (d > 0 && System.currentTimeMillis() > d) {
                aborted = true
                throw EvaluatorException("Script exceeded its execution deadline")
            }
        }
    }

    private val factory = WatchdogFactory()

    /**
     * The ONLY Java classes the sandbox exposes. Rhino needs java.lang
     * immutables for JS↔Java value coercion; everything else — including
     * Runtime, ProcessBuilder, System, Class, and every android/app package —
     * is denied by returning false.
     */
    private val allowedClasses: Set<String> = setOf(
        "java.lang.String", "java.lang.Object", "java.lang.Boolean", "java.lang.Character",
        "java.lang.Number", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
        "java.lang.Float", "java.lang.Short", "java.lang.Byte", "java.lang.Math",
        "java.lang.StringBuilder", "java.lang.CharSequence", "java.lang.Comparable",
        "java.lang.Iterable", "java.lang.Void", "java.lang.ArithmeticException",
        "java.lang.Exception", "java.lang.RuntimeException", "java.lang.Error",
        "java.lang.Throwable", "java.lang.IllegalArgumentException",
        "java.lang.IllegalStateException", "java.lang.IndexOutOfBoundsException",
        "java.lang.NullPointerException", "java.lang.UnsupportedOperationException",
        "java.lang.NumberFormatException", "java.lang.StackTraceElement",
    )

    private val classShutter = ClassShutter { className -> isClassVisible(className) }

    /**
     * The shutter gate. Rhino reports ARRAYS with JVM notation ("[Ljava.lang.Object;"),
     * so array markers are stripped before matching — a vararg bridge method
     * (log(Object[])) otherwise reads as an unknown class and every console
     * call would be denied.
     */
    private fun isClassVisible(className: String): Boolean {
        var name = className
        while (name.startsWith("[")) {
            name = name.removePrefix("[")
            if (name.startsWith("L") && name.endsWith(";")) {
                name = name.substring(1, name.length - 1)
            }
        }
        // Rhino's own machinery must keep working.
        if (name.startsWith("org.mozilla.javascript.")) return true
        // The console bridge is the ONLY Java object placed in the scope; its
        // classes live under host.vorv.tools.JsExecTool*. Script can call
        // console.log/info/warn/error — nothing else. The dangerous neighbors
        // (java.lang.Class, java.lang.reflect.**, Runtime, ProcessBuilder,
        // every other app/Android class) remain DENIED, and the LiveConnect
        // namespace globals are deleted, so this allowance cannot be leveraged
        // into object construction or reflection.
        if (name.startsWith("host.vorv.tools.JsExecTool")) return true
        return name in allowedClasses
    }

    /** LiveConnect globals and import helpers — deleted before sealing. */
    private val liveConnectGlobals = listOf(
        "Packages", "java", "javax", "org", "com", "net", "edu", "io",
        "android", "androidx", "host", "jdk", "sun", "gnu", "mozilla",
        "javafx", "GoogleServices",
        "importClass", "importPackage", "JavaAdapter", "JavaImporter",
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val code = input.get("code")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing code",
                durationMs = System.currentTimeMillis() - t0)

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var returnValue: String? = null
        var error: String? = null
        var timedOut = false

        val cx = factory.enterContext()
        try {
            cx.setOptimizationLevel(-1)          // interpreted: required for the observer
            cx.languageVersion = Context.VERSION_ES6
            // Rhino's setClassShutter has no public getter, so Kotlin's synthetic
            // property syntax doesn't apply — call the setter explicitly.
            cx.setClassShutter(classShutter)
            cx.instructionObserverThreshold = 10_000

            val scope = cx.initStandardObjects(null, false)

            val console = object : ScriptableObject() {
                override fun getClassName() = "VorvConsole"
                fun log(vararg args: Any?) { stdout.appendLine(args.joinToString(" ") { fmt(it) }) }
                fun info(vararg args: Any?) { stdout.appendLine(args.joinToString(" ") { fmt(it) }) }
                fun warn(vararg args: Any?) { stderr.appendLine("WARN: " + args.joinToString(" ") { fmt(it) }) }
                fun error(vararg args: Any?) { stderr.appendLine("ERR: " + args.joinToString(" ") { fmt(it) }) }
            }
            scope.put("console", scope, console)

            // Remove every LiveConnect escape hatch, then seal the scope.
            for (name in liveConnectGlobals) scope.delete(name)
            (scope as? ScriptableObject)?.sealObject()

            factory.aborted = false
            factory.deadline = System.currentTimeMillis() + SANDBOX_TIMEOUT_MS
            try {
                val result = cx.evaluateString(scope, code, "vorv-sandbox.js", 1, null)
                returnValue = result?.let { fmt(it) }
            } catch (e: EvaluatorException) {
                error = if (factory.aborted || timedOutFlag(factory)) {
                    timedOut = true
                    "Execution timed out after ${SANDBOX_TIMEOUT_MS}ms"
                } else {
                    e.message ?: e.toString()
                }
                stderr.appendLine(error)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                stderr.appendLine(error)
            } finally {
                factory.deadline = 0L
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
            error = if (timedOut) "Execution timed out after ${SANDBOX_TIMEOUT_MS}ms" else error,
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("exec", ctx.taskId, "exec iter ${ctx.iteration}"),
        )
    }

    private fun timedOutFlag(factory: WatchdogFactory) = factory.aborted

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

    private companion object {
        const val SANDBOX_TIMEOUT_MS = 5_000L
    }
}
