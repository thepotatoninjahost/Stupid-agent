package host.vorv.computer

import java.io.File
import java.io.IOException

/** Result of one engine execution. */
data class EngineRun(
    val output: List<String>,
    val timedOut: Boolean,
    val error: String?,
)

/** A language runtime the app owns. */
interface Engine {
    val id: String
    fun available(): Boolean
    fun run(code: String, timeoutMs: Long): EngineRun
}

/**
 * The app's computer: a private workspace plus the language runtimes it owns,
 * behind one audit-logged entry point.
 *
 * This is the scheduler the rest of the app talks to instead of a shell. It
 * refuses to run anything it cannot execute itself, it never concatenates a
 * command line, and every run leaves a line in `log/audit.log` recording what
 * engine ran, for how long and how it ended — never the code itself.
 */
class Computer(
    private val home: ComputerHome,
    engines: List<Engine>,
    private val quotaBytes: Long = ComputerHome.DEFAULT_QUOTA,
) {

    private val engines: Map<String, Engine> = engines.associateBy { it.id }

    sealed interface Outcome {
        data class Ok(val engine: String, val output: List<String>, val elapsedMs: Long) : Outcome
        data class Failed(val engine: String, val detail: String, val output: List<String>) : Outcome
        data class TimedOut(val engine: String, val output: List<String>, val budgetMs: Long) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    /** Engines that can actually run right now, with the reason for any that cannot. */
    fun engines(): List<Pair<String, Boolean>> = engines.values.map { it.id to it.available() }

    fun run(kind: String, code: String, timeoutMs: Long, estimatedBytes: Long = 0L): Outcome {
        val engine = engines[kind] ?: return refuse("unknown engine '$kind' (have: ${engines.keys.joinToString()})")
        if (!engine.available()) return refuse("engine '$kind' is not available on this device")
        if (code.isBlank()) return refuse("empty program")
        if (!home.hasRoomFor(estimatedBytes, quotaBytes)) {
            return refuse("workspace full: ${home.usedBytes()} of $quotaBytes bytes used")
        }
        if (home.provision().isNotEmpty()) return refuse("workspace cannot be created at ${home.root}")

        val started = System.currentTimeMillis()
        val run = try {
            engine.run(code, timeoutMs)
        } catch (e: Exception) {
            return Outcome.Failed(kind, "engine crashed: ${e.message ?: e.javaClass.simpleName}", emptyList())
        }
        val elapsed = System.currentTimeMillis() - started

        val outcome = when {
            run.timedOut -> Outcome.TimedOut(kind, run.output, timeoutMs)
            run.error != null -> Outcome.Failed(kind, run.error, run.output)
            else -> Outcome.Ok(kind, run.output, elapsed)
        }
        audit(kind, outcome, elapsed, code.length)
        return outcome
    }

    private fun refuse(reason: String): Outcome {
        audit("none", Outcome.Refused(reason), 0L, 0)
        return Outcome.Refused(reason)
    }

    /**
     * Append-only, best-effort: a failed audit write must never fail the task
     * the user asked for, but it is reported as a line the next reader sees.
     */
    private fun audit(engine: String, outcome: Outcome, elapsedMs: Long, codeChars: Int) {
        val verdict = when (outcome) {
            is Outcome.Ok -> "ok"
            is Outcome.Failed -> "failed"
            is Outcome.TimedOut -> "timeout"
            is Outcome.Refused -> "refused"
        }
        val detail = when (outcome) {
            is Outcome.Failed -> outcome.detail.take(200).replace('\n', ' ')
            is Outcome.Refused -> outcome.reason.take(200)
            is Outcome.TimedOut -> "budget ${outcome.budgetMs}ms"
            is Outcome.Ok -> "${outcome.output.size} lines"
        }
        val line = "${System.currentTimeMillis()} engine=$engine verdict=$verdict ms=$elapsedMs " +
            "codeChars=$codeChars $detail\n"
        try {
            File(home.logDir, "audit.log").appendText(line)
        } catch (_: IOException) {
            // Never let bookkeeping break execution.
        }
    }
}
