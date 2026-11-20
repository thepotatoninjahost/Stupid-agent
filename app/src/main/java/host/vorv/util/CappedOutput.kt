package host.vorv.util

/**
 * Accumulates a child process's output, never past [cap] characters.
 *
 * The check-before-append idiom this replaces (`if (out.length < cap)
 * out.appendLine(line)`) let any single line through as long as the buffer
 * was still under the cap, so one very long line — a minified bundle, a
 * binary blob, `cat` of a big file — went in whole and the "cap" only ever
 * bounded the number of lines that followed it (L-12, code review
 * 2026-11-20).
 *
 * Thread-safe: the reader is a drain thread and the caller reads after
 * joining it, or while it may still be alive when a timeout fires.
 */
internal class CappedOutput(private val cap: Int) {

    private val sb = StringBuilder()
    private var cut = false

    fun appendLine(line: String) {
        synchronized(this) {
            val room = cap - sb.length
            when {
                room <= 0 -> cut = true
                line.length + 1 <= room -> sb.append(line).append('\n')
                else -> {
                    sb.append(line, 0, room)
                    cut = true
                }
            }
        }
    }

    /** The text so far, with an honest marker when anything was dropped. */
    fun text(): String = synchronized(this) {
        if (cut) sb.toString() + "\n…(output capped at $cap chars)\n" else sb.toString()
    }
}
