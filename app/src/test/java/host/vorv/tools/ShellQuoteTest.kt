package host.vorv.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H-1 regression tests (code review, 2026-11): [ShellQuote.sh] keeps a
 * user-chosen workspace folder name inert.
 *
 * The bug: the SAF-derived workspace path
 * (`/storage/emulated/0/<user-chosen folder name>`) was interpolated inside
 * hand-written quotes, so a folder named `x'$(cmd)'` — a perfectly legal SAF
 * grant the USER picks — broke out of the quoting and executed arbitrary
 * text. The guest shell no longer builds strings for a shell parser to
 * unquote (paths are passed as argv), but `shell` still accepts a command
 * string and callers still compose paths, so the escaper is pinned here.
 *
 * Plain JUnit: [ShellQuote] is pure string logic with no Android references.
 */
class ShellQuoteTest {

    // ------------------------------------------------------ the escaper itself

    @Test
    fun `plain path is wrapped in single quotes`() {
        assertEquals("'/storage/emulated/0/work'", ShellQuote.sh("/storage/emulated/0/work"))
    }

    @Test
    fun `embedded single quote uses the backslash idiom`() {
        // ' → '\'' : close the quote, escape the quote, reopen.
        assertEquals("'it'\\''s'", ShellQuote.sh("it's"))
    }

    @Test
    fun `dollar paren command substitution is inert`() {
        assertEquals("'x\$(touch /data/pwned)x'", ShellQuote.sh("x\$(touch /data/pwned)x"))
    }

    @Test
    fun `backtick substitution is inert`() {
        assertEquals("'x`id`x'", ShellQuote.sh("x`id`x"))
    }

    @Test
    fun `semicolons and operators are inert`() {
        assertEquals("'; rm -rf ~ ;'", ShellQuote.sh("; rm -rf ~ ;"))
    }

    @Test
    fun `double quotes and dollar signs are inert`() {
        // \$ — literal "$HOME" text; unescaped it would be a Kotlin template.
        assertEquals("'\"\$HOME\"'", ShellQuote.sh("\"\$HOME\""))
    }

    @Test
    fun `empty string becomes empty quoted token`() {
        assertEquals("''", ShellQuote.sh(""))
    }

    // ------------------------------------------- bash word-splitting round-trip
    // The strongest possible assertion: parse the produced token with
    // POSIX single-quote word-splitting rules and get EXACTLY the input
    // back. If any character of the input were interpreted by the shell,
    // the round-trip would diverge.

    /** Split [s] the way bash splits words, honoring '...' quoting and
     *  backslash escapes outside quotes. */
    private fun bashSplitWords(s: String): List<String> {
        val words = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inQuote -> {
                    if (c == '\'') inQuote = false else cur.append(c)
                }
                c == '\'' -> inQuote = true
                c == '\\' && i + 1 < s.length -> { cur.append(s[i + 1]); i++ } // \<char> escape
                c == ' ' || c == '\t' || c == '\n' -> {
                    if (cur.isNotEmpty()) { words.add(cur.toString()); cur.clear() }
                }
                // Outside quotes these WOULD be syntax — a properly quoted
                // token never exposes them bare, so treating them as word
                // separators models the worst case: if any payload char
                // leaks outside quotes, the round-trip below diverges.
                c == ';' || c == '|' || c == '>' || c == '<' || c == '&' || c == '(' || c == ')' -> {
                    if (cur.isNotEmpty()) { words.add(cur.toString()); cur.clear() }
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) words.add(cur.toString())
        return words
    }

    private fun assertRoundTrips(payload: String) {
        val token = ShellQuote.sh(payload)
        // The full token parses back to exactly ONE word == the payload.
        assertEquals(listOf(payload), bashSplitWords(token))
    }

    @Test
    fun `quoting round-trips adversarial folder names`() {
        assertRoundTrips("/storage/emulated/0/x'\$(touch /data/pwned)'")
        assertRoundTrips("/storage/emulated/0/`rm -rf ~`")
        assertRoundTrips("/storage/emulated/0/a;b|c>d&e")
        assertRoundTrips("/storage/emulated/0/it's a \"quoted\" \$HOME")
        assertRoundTrips("/storage/emulated/0/ünïcödé ぷれいす")
    }
}
