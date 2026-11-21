package host.vorv.nativeapp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding 22 (code review, 2026-11): the accessibility service could read and
 * tap whatever app the user had in front — Settings, a password manager, their
 * bank. It is now scoped to the app the test session opened.
 */
class UiAutomationSessionTest {

    @After
    fun tearDown() {
        UiAutomationService.endSession()
    }

    @Test
    fun `nothing is readable until a session names an app`() {
        assertNull("no session yet", UiAutomationService.sessionTargetForTest())
        assertFalse("no session, no read", UiAutomationService.allowsRead(null, "com.example"))
        assertFalse("foreground alone is not enough", UiAutomationService.allowsRead(null, null))
    }

    @Test
    fun `only the app under test is readable while its session is open`() {
        UiAutomationService.beginSession("com.example.under.test")
        assertEquals("com.example.under.test", UiAutomationService.sessionTargetForTest())
        assertTrue(UiAutomationService.allowsRead("com.example.under.test", "com.example.under.test"))
        assertFalse("the launcher is not the app", UiAutomationService.allowsRead("com.example.under.test", "com.android.launcher"))
        assertFalse("settings is not the app", UiAutomationService.allowsRead("com.example.under.test", "com.android.settings"))
        assertFalse("another app is not the app", UiAutomationService.allowsRead("com.example.under.test", "com.bank.app"))
    }

    @Test
    fun `ending the session closes the door again`() {
        UiAutomationService.beginSession("com.example.under.test")
        UiAutomationService.endSession()
        assertNull(UiAutomationService.sessionTargetForTest())
        assertFalse(UiAutomationService.allowsRead(null, "com.example.under.test"))
    }
}
