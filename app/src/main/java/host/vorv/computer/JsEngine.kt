package host.vorv.computer

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.ScriptableObject

/**
 * The JavaScript half of the app's computer: a real interpreter that runs
 * inside the APK, with no Termux, no shell and no way to reach the phone.
 *
 * The engine exists so the agent can *build and run things* on the device:
 * a computation, a scripted transformation, a small program it wrote itself.
 * It is not a route to Java: every class is refused by [Shutter], including
 * `java.lang.System`, so the only surface a script sees is the standard
 * ECMAScript library plus [console].
 */
class JsEngine(private val maxOutput: Int = 8_000) : Engine {

    override val id: String = "js"

    override fun available(): Boolean = true

    override fun run(code: String, timeoutMs: Long): EngineRun {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val chars = java.util.concurrent.atomic.AtomicInteger(0)
        val console = Console { line ->
            if (lines.size < MAX_LINES && chars.get() < maxOutput) {
                chars.addAndGet(line.length + 1)
                lines += line
            }
        }
        val factory = DeadlineFactory(System.currentTimeMillis() + timeoutMs.coerceAtLeast(1_000))

        val worker = Thread {
            var cx: Context? = null
            try {
                cx = factory.enterContext()
                cx.setInterpretedMode(true) // no JIT: predictable deadline checks
                cx.languageVersion = Context.VERSION_ES6
                cx.setClassShutter(Shutter)
                cx.wrapFactory?.isJavaPrimitiveWrap = false

                val scope = cx.initSafeStandardObjects(null, true)
                ScriptableObject.putProperty(scope, "console", Context.javaToJS(console, scope))
                cx.evaluateString(scope, code, "task.js", 1, null)
            } catch (e: EvaluatorException) {
                // A failed script must say why: an empty result the caller
                // cannot explain is the same defect as swallowing the error.
                append(lines, factory.expired, e.details() ?: e.message ?: "evaluation error")
            } catch (e: Throwable) {
                append(lines, factory.expired, e.message ?: e.javaClass.simpleName)
            } finally {
                cx?.let { Context.exit() }
            }
        }.apply {
            isDaemon = true
            name = "vorv-js"
        }

        worker.start()
        worker.join(timeoutMs.coerceAtLeast(1_000) + 500)
        val snapshot = synchronized(lines) { lines.toList() }
        if (worker.isAlive) {
            // A script stuck in a native call cannot be interrupted from here;
            // abandoning the daemon thread is the only safe option, and the
            // caller must know the output is partial.
            return EngineRun(snapshot, timedOut = true, error = null)
        }
        return EngineRun(snapshot, timedOut = factory.expired, error = null)
    }

    /** Keeps the reason visible without ever growing past the cap. */
    private fun append(lines: MutableList<String>, timedOut: Boolean, message: String) {
        if (!timedOut && lines.size < MAX_LINES) lines += message
    }

    /** Absolute deadline checked by Rhino between instruction batches. */
    private class DeadlineFactory(private val deadline: Long) : ContextFactory() {
        @Volatile var expired: Boolean = false

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.currentTimeMillis() > deadline) {
                expired = true
                throw EvaluatorException("execution deadline exceeded")
            }
        }
    }

    /**
     * Denies every Java class. The console bridge is a lambda-free object
     * reached through a NativeJavaObject, so its one type must be visible;
     * everything else — including arrays and `Class.forName` results — is
     * refused.
     */
    private object Shutter : ClassShutter {
        override fun visibleToScripts(fullClassName: String): Boolean =
            fullClassName == Console::class.java.name ||
                fullClassName == "java.lang.String"
    }

    /** Plain class: Rhino exposes its methods as script-callable properties. */
    class Console(private val sink: (String) -> Unit) {
        fun log(msg: Any?) = sink(render(msg))
        fun info(msg: Any?) = sink(render(msg))
        fun warn(msg: Any?) = sink("warn: " + render(msg))
        fun error(msg: Any?) = sink("error: " + render(msg))

        private fun render(v: Any?): String = when (v) {
            null -> "null"
            is String -> v
            else -> Context.toString(v)
        }
    }

    private companion object {
        const val MAX_LINES = 2_000
    }
}
