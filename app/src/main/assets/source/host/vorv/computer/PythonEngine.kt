package host.vorv.computer

/**
 * The Python half of the computer, as an adapter.
 *
 * The interpreter itself ships in the APK (Chaquopy, CPython 3.11) and is
 * already used by `python_exec`; this class exists so the computer has one
 * uniform way to run a program regardless of language, without the caller
 * knowing how the interpreter is hosted. The runner is injected rather than
 * imported so this file stays free of Android and Chaquopy types and can be
 * exercised by ordinary JVM tests.
 */
class PythonEngine(
    private val runner: (code: String, timeoutMs: Long) -> List<String>,
    private val probe: () -> Boolean = { true },
) : Engine {

    override val id: String = "python"

    override fun available(): Boolean = runCatching(probe).getOrDefault(false)

    override fun run(code: String, timeoutMs: Long): EngineRun {
        val started = System.currentTimeMillis()
        return try {
            val lines = runner(code, timeoutMs)
            EngineRun(lines, timedOut = false, error = null)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            EngineRun(emptyList(), timedOut = true, error = null)
        } catch (e: Exception) {
            val budgetSpent = System.currentTimeMillis() - started >= timeoutMs
            EngineRun(emptyList(), timedOut = budgetSpent, error = if (budgetSpent) null else e.message)
        }
    }
}
