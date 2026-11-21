package host.vorv.watchdog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The approval contract. The parts that need a real phone — the system's
 * fingerprint/PIN prompt and its Keystore binding — cannot run here, so what
 * is pinned is the shape: what exactly gets signed, that an approval names
 * one patch only, and that anything else is refused rather than trusted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PatchConsentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the statement names the patch, its class and its exact bytes`() {
        assertEquals(
            "vorv-consent-v1|heal_20260101_1|host.vorv.tools.NotifyTool|abcd",
            PatchConsent.statement("heal_20260101_1", "host.vorv.tools.NotifyTool", "abcd"),
        )
    }

    @Test
    fun `approval for one patch is a different statement than for another`() {
        val a = PatchConsent.statement("fix_1", "host.vorv.tools.NotifyTool", "aaaa")
        val b = PatchConsent.statement("fix_1", "host.vorv.tools.NotifyTool", "bbbb")
        val c = PatchConsent.statement("fix_2", "host.vorv.tools.NotifyTool", "aaaa")
        assertNotEquals(a, b) // different bytes
        assertNotEquals(a, c) // different patch id
    }

    @Test
    fun `garbage tokens are refused, not trusted`() {
        val statement = PatchConsent.statement("fix_1", "host.vorv.tools.NotifyTool", "aaaa")
        assertFalse(PatchConsent.verify(context, statement, ""))
        assertFalse(PatchConsent.verify(context, statement, "not-a-token"))
        assertFalse(PatchConsent.verify(context, statement, "Ed25519:AAAA"))
        assertFalse(PatchConsent.verify(context, "", "EC:AAAA"))
        // A well-formed token nobody signed on this device.
        assertFalse(
            PatchConsent.verify(
                context, statement,
                "EC:" + android.util.Base64.encodeToString(
                    ByteArray(70), android.util.Base64.NO_WRAP),
            ),
        )
    }

    @Test
    fun `without a lock screen no approval key is created and none can be signed`() {
        // Robolectric has no secure lock screen, which is exactly the "cannot
        // prove the user is there" case: the answer must be "no", not a
        // fallback that would be forgeable.
        assertFalse(PatchConsent.hasDeviceLock(context))
        assertFalse(PatchConsent.ensureKey(context))
        assertTrue(PatchConsent.newSignature(context) == null)
    }
}
