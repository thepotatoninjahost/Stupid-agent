package host.vorv.tools

/**
 * THE single shell-quoting helper for every string interpolated into a
 * command line that bash will execute.
 *
 * H-1 (code review, 2026-11): one call site quoted correctly and two did not
 * — they interpolated the SAF-derived workspace path inside hand-written
 * single or double quotes. A workspace folder whose name contains a quote,
 * dollar-parenthesis, backtick or semicolon broke out of that quoting and
 * executed attacker-controlled text. The user picks that folder name, and a
 * folder named `x'$(cmd)'` is a perfectly legal SAF grant. One shared helper
 * means this can never drift again. (The guest shell now passes paths as
 * argv, so nothing is re-quoted there; this stays for command strings.)
 *
 * Pure string logic (no Android types) so unit tests pin the escaping.
 */
object ShellQuote {

    /**
     * Single-quote [s] for a POSIX shell, escaping embedded single quotes
     * with the standard `'\''` idiom: the produced token contains [s]
     * verbatim and no character of [s] is ever interpreted by the shell.
     */
    fun sh(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
