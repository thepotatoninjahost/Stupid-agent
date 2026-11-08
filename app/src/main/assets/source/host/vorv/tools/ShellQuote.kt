package host.vorv.tools

/**
 * THE single shell-quoting helper for every string interpolated into a
 * command line that bash will execute.
 *
 * H-1 (code review, 2026-11): ShellTool had a correct private `shQuote()`,
 * but HealingAgent and TermuxProbe built their Termux dispatch wrappers by
 * interpolating the SAF-derived workspace path inside hand-written single or
 * double quotes. A workspace folder whose name contains a quote,
 * dollar-parenthesis, backtick or semicolon broke out of that quoting and
 * executed attacker-controlled text in Termux — the user picks the folder
 * name, and a folder named `x'$(cmd)'` is a perfectly legal SAF grant.
 * Three call sites, one correct, two injectable — exactly the failure mode
 * duplication predicts. One shared helper means this can never drift again;
 * the fix UPGRADES the healer and the probe to the quoting discipline the
 * shell tool already followed.
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
