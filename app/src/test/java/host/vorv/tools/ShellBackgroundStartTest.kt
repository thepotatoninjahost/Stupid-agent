package host.vorv.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M13 (code review, 2026-11): when Android blocks startService because Vorv
 * is backgrounded (IllegalStateException on Android 8+), the shell tool must
 * fail with a message that NAMES the cause and the exact remedy — the old
 * generic "shell dispatch failed: …" wrapper buried both.
 */
class ShellBackgroundStartTest {

    @Test
    fun `the background-start wall names the cause`() {
        val msg = ShellTool.backgroundStartError()
        assertTrue("names Android as the blocker", msg.contains("Android blocked"))
        assertTrue("names the background restriction", msg.contains("BACKGROUND"))
        assertTrue("names the exact remediation", msg.contains("foreground"))
        assertTrue("says what to do", msg.contains("open the app"))
    }
}
