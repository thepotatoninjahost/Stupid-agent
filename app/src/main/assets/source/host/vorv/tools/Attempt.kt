package host.vorv.tools

/**
 * For the places where a failure is genuinely survivable and the caller wants
 * to carry on: the failure is still written down, once, with what was being
 * attempted. The rule this exists to enforce is the owner's: nothing fails
 * silently.
 *
 * `runCatching { … }` on its own still has its uses (you may want the
 * `Result`), but a `runCatching` whose result is thrown away leaves no trace.
 * [SilentFailureGuardTest] keeps that count from growing.
 */
object Attempt {

    /** Replaced in tests. Never throws: a logger that fails must not mask the failure. */
    @Volatile
    var logger: (String) -> Unit = { message ->
        try {
            android.util.Log.e("VorvAttempt", message)
        } catch (_: Throwable) {
            // Log is stubbed in plain JVM tests; losing a log line is not worth a crash.
        }
    }

    /** Runs [block]; on any failure logs one line and returns null. */
    inline fun <T> of(what: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        note(what, t)
        null
    }

    /** As [of], with the value to use when it fails. */
    inline fun <T> orDefault(what: String, fallback: T, block: () -> T): T = of(what, block) ?: fallback

    fun note(what: String, t: Throwable) {
        logger("$what failed — ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
    }
}
