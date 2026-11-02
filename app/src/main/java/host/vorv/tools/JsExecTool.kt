package host.vorv.tools

import com.google.gson.JsonObject
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * js_exec — run JavaScript in a HARDENED Rhino sandbox (audit finding C-1 + H-3).
 *
 * Defense in depth, each layer verified by JsExecToolTest (attack cases) and
 * by a local Rhino-jar prototype during remediation:
 *
 *  1. ClassShutter — the ONLY Java classes visible to script are Rhino's own,
 *     a tiny allowlist of immutable java.lang types, and the console bridge's
 *     own class. Every class access through every namespace path
 *     (Packages.java.lang.System, java.lang.Runtime, Class.forName, …) is
 *     denied at the JavaMembers/NativeJavaPackage gate: classes materialize
 *     as inert package placeholders and can never be constructed or called.
 *     Array type names ("[Ljava.lang.Object;") are normalized before the
 *     allowlist check so the vararg console bridge keeps working.
 *
 *  2. LiveConnect globals (Packages, java, javax, org, com, net, edu, io,
 *     android, androidx, host, jdk, sun, gnu, mozilla, javafx,
 *     GoogleServices) and import helpers (importClass, importPackage,
 *     JavaAdapter, JavaImporter) are deleted from the global scope. Rhino's
 *     lazy package machinery may re-expose the root namespace OBJECT, but it
 *     remains inert behind the shutter (asserted by test
 *     `Packages namespace is visible but inert`).
 *
 *  3. The global scope is SEALED and user code runs in a CHILD scope —
 *     console and the standard builtins cannot be redefined, while
 *     const/let/var/function declarations work normally in the child.
 *
 *  4. Timeout via Rhino's instruction-count observer on the evaluating
 *     thread (same-thread interruption — no cross-thread Context access),
 *     plus a dedicated daemon evaluation thread with a join deadline so a
 *     blocking native call (e.g. a synchronized console.wait) can never pin
 *     an IO-pool thread.
 *
 *  5. The console is a PLAIN class (not ScriptableObject): Rhino wraps it in
 *     a NativeJavaObject, which is what actually exposes log/info/warn/error
 *     to script. (The v0.5.x ScriptableObject pattern silently exposed NO
 *     methods — console.log never worked; caught by the new test suite.)
 */
class JsExecTool : Tool {
    override val name = "js_exec"
    override val description =
        "Execute JavaScript in a hardened sandbox. Only standard ECMAScript built-ins " +
        "(Math, JSON, Array, String, …) and console.log are available — no Java class " +
        "access, no file I/O, no network. Use for quick computation, string manipulation, " +
        "or testing logic. 5 second budget."

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
     * the deadline passes. The hook fires on the evaluating thread — Rhino's
     * documented interruption mechanism.
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

    /**
     * The ONLY Java object placed in the sandbox scope. PLAIN class on
     * purpose: Rhino's WrapFactory wraps it in a NativeJavaObject and exposes
     * exactly these public methods (plus Object's, which are harmless —
     * getClass() results are shutter-gated, wait/notify throw
     * IllegalMonitorStateException off-monitor).
     */
    class SandboxConsole(
        private val stdout: StringBuilder,
        private val stderr: StringBuilder,
    ) {
        fun log(vararg args: Any?) { stdout.appendLine(args.joinToString(" ") { fmt(it) }) }
        fun info(vararg args: Any?) { stdout.appendLine(args.joinToString(" ") { fmt(it) }) }
        fun warn(vararg args: Any?) { stderr.appendLine("WARN: " + args.joinToString(" ") { fmt(it) }) }
        fun error(vararg args: Any?) { stderr.appendLine("ERR: " + args.joinToString(" ") { fmt(it) }) }

        private fun fmt(v: Any?): String = when (v) {
            null -> "null"
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> v.toString()
        }
    }

    /**
     * The ONLY Java classes the sandbox exposes (beyond Rhino internals and
     * the console bridge): immutable java.lang types needed for JS↔Java value
     * coercion. Runtime, ProcessBuilder, System, Class, reflect, io, net and
     * every app/Android class are DENIED.
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
     * The shutter gate. Rhino reports ARRAYS in JVM notation
     * ("[Ljava.lang.Object;") — markers are stripped before matching so the
     * vararg console bridge (log(Object[])) is not misread as an unknown
     * class. Rhino internals, the console bridge's own classes, and the
     * immutable java.lang allowlist are visible; everything else is denied.
     */
    private fun isClassVisible(className: String): Boolean {
        var name = className
        while (name.startsWith("[")) {
            name = name.removePrefix("[")
            if (name.startsWith("L") && name.endsWith(";")) {
                name = name.substring(1, name.length - 1)
            }
        }
        if (name.startsWith("org.mozilla.javascript.")) return true
        if (name.startsWith("host.vorv.tools.JsExecTool")) return true
        return name in allowedClasses
    }

    /** LiveConnect globals and import helpers — deleted from the global scope. */
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
        val resultRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val errorRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var timedOut = false

        // Audit L-5: ONE WatchdogFactory PER EVALUATION. The shared factory
        // carried mutable deadline/aborted state across concurrent runs, so
        // one evaluation's deadline could abort (or fail to abort) another's.
        // A local factory makes every evaluation self-contained.
        val factory = WatchdogFactory()

        // Evaluation runs on a dedicated daemon thread: a blocking native
        // call inside the sandbox can never pin an IO-pool thread — the join
        // deadline bounds it.
        val worker = Thread {
            val cx = factory.enterContext()
            try {
                cx.setOptimizationLevel(-1)          // interpreted: required for the observer
                cx.languageVersion = Context.VERSION_ES6
                // Rhino's setClassShutter has no public getter — synthetic
                // property syntax doesn't apply; call the setter directly.
                cx.setClassShutter(classShutter)
                cx.instructionObserverThreshold = 10_000

                val global = cx.initStandardObjects(null, false)
                global.put("console", global, SandboxConsole(stdout, stderr))
                for (name in liveConnectGlobals) global.delete(name)
                global.sealObject()

                // Child scope: user declarations land here; the sealed global
                // (console + builtins) cannot be tampered with.
                val child = cx.newObject(global)
                child.prototype = global
                child.parentScope = global

                factory.aborted = false
                factory.deadline = System.currentTimeMillis() + SANDBOX_TIMEOUT_MS
                try {
                    val result = cx.evaluateString(child, code, "vorv-sandbox.js", 1, null)
                    resultRef.set(result?.toString())
                } catch (e: EvaluatorException) {
                    errorRef.set(if (factory.aborted) TIMEOUT_MSG else (e.message ?: e.toString()))
                } catch (e: Exception) {
                    errorRef.set(e.message ?: e.toString())
                } finally {
                    factory.deadline = 0L
                }
            } finally {
                Context.exit()
            }
        }.apply { isDaemon = true; start() }

        worker.join(SANDBOX_TIMEOUT_MS + JOIN_MARGIN_MS)
        if (worker.isAlive) {
            timedOut = true
            worker.interrupt()
        }

        val error = errorRef.get()
        val returnValue = resultRef.get()

        ToolResult(
            ok = !timedOut && error == null,
            output = buildString {
                if (stdout.isNotEmpty()) append(stdout.toString().trim())
                if (returnValue != null && returnValue != "undefined") {
                    if (isNotEmpty()) append("\n")
                    append("=> $returnValue")
                }
                if (isEmpty()) append("(no output)")
            },
            stderr = stderr.toString().trim(),
            error = when {
                timedOut -> TIMEOUT_MSG
                error != null -> error
                else -> null
            },
            durationMs = System.currentTimeMillis() - t0,
            citation = Citation("exec", ctx.taskId, "exec iter ${ctx.iteration}"),
        )
    }

    private companion object {
        const val SANDBOX_TIMEOUT_MS = 5_000L
        const val JOIN_MARGIN_MS = 1_500L
        const val TIMEOUT_MSG = "Execution timed out after $SANDBOX_TIMEOUT_MS ms"
    }
}
