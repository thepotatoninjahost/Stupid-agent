package host.vorv.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H-1 regression tests (code review, 2026-11): shell quoting of every
 * string interpolated into a Termux dispatch wrapper.
 *
 * The bug: HealingAgent and TermuxProbe interpolated the SAF-derived
 * workspace path (`/storage/emulated/0/<user-chosen folder name>`) inside
 * hand-written quotes. A folder named `x'$(cmd)'` — a perfectly legal SAF
 * grant the USER picks — broke out of the quoting and executed arbitrary
 * text inside Termux. The fix routes every interpolation through
 * [ShellQuote.sh]; these tests pin the escaping AND prove the two
 * dispatch builders survive the exact payloads an attacker would use.
 *
 * Plain JUnit (no Robolectric): [ShellQuote] and [TermuxProbe.buildProbeWrapper]
 * are pure string logic with no Android references.
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
        assertEquals("'\"$HOME\"'", ShellQuote.sh("\"$HOME\""))
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

    // ------------------------------------------- the two dispatch builders (H-1)

    private val payloads = listOf(
        "x'\$(touch /data/local/tmp/pwned)'",   // the exact H-1 vector
        "x'`id`'",
        "a'; rm -rf /sdcard; #",
        "weird name with spaces & \$ymbols",
        "`reboot`",
    )

    @Test
    fun `probe wrapper quotes every path against injection payloads`() {
        for (p in payloads) {
            val rawDir = "/storage/emulated/0/$p"
            val w = TermuxProbe.buildProbeWrapper(
                "QWJD", rawDir, ".vorv-shell", "id.out", "id.err", "id.code")
            // The quoted paths round-trip through bash word-splitting as
            // EXACTLY the intended paths — if any payload character leaked
            // outside the quotes, the split would diverge (this is the
            // assertion that fails on the pre-fix hand-written quoting).
            val words = bashSplitWords(w)
            assertTrue("out path not inert: $w", words.contains("/storage/emulated/0/$p/.vorv-shell/id.out"))
            assertTrue("err path not inert: $w", words.contains("/storage/emulated/0/$p/.vorv-shell/id.err"))
            assertTrue("code path not inert: $w", words.contains("/storage/emulated/0/$p/.vorv-shell/id.code"))
        }
    }

    @Test
    fun `probe wrapper keeps the transport prefix intact`() {
        val w = TermuxProbe.buildProbeWrapper(
            "QWJD", "/storage/emulated/0/plain", ".vorv-shell", "a.out", "a.err", "a.code")
        assertTrue(w.startsWith("printf '%s' 'QWJD' | base64 -d | bash > "))
        assertTrue(w.endsWith("; echo \$? > '/storage/emulated/0/plain/.vorv-shell/a.code'"))
    }

    @Test
    fun `healer dispatch command quotes every path against injection payloads`() {
        for (p in payloads) {
            val rawDir = "/storage/emulated/0/$p"
            val cmd = host.vorv.healer.HealingAgent.Companion.dispatchCommand(
                rawDir, ".vorv-heal", "c.sh", "o.txt", "e.txt", "k.txt")
            val words = bashSplitWords(cmd)
            assertTrue("script path not inert: $cmd", words.contains("/storage/emulated/0/$p/.vorv-heal/c.sh"))
            assertTrue("out path not inert: $cmd", words.contains("/storage/emulated/0/$p/.vorv-heal/o.txt"))
            assertTrue("err path not inert: $cmd", words.contains("/storage/emulated/0/$p/.vorv-heal/e.txt"))
            assertTrue("code path not inert: $cmd", words.contains("/storage/emulated/0/$p/.vorv-heal/k.txt"))
        }
    }

    @Test
    fun `healer dispatch command keeps redirection and exit-code capture`() {
        val cmd = host.vorv.healer.HealingAgent.Companion.dispatchCommand(
            "/storage/emulated/0/plain", ".vorv-heal", "c.sh", "o.txt", "e.txt", "k.txt")
        assertEquals(
            "bash '/storage/emulated/0/plain/.vorv-heal/c.sh' > '/storage/emulated/0/plain/.vorv-heal/o.txt' " +
                "2> '/storage/emulated/0/plain/.vorv-heal/e.txt'; echo \$? > '/storage/emulated/0/plain/.vorv-heal/k.txt'",
            cmd)
    }
}
