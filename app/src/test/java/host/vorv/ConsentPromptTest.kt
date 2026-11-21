package host.vorv

import host.vorv.healer.PatchProposal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C-2 pinned: the consent dialog must tell the truth. It used to promise
 * "the patched class replaces the original" and "Verified: full public API
 * kept" for every proposal, even when the completeness check had not run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ConsentPromptTest {

    private val sha = "9f2c" + "a".repeat(60)

    private fun proposal(verified: Boolean) = PatchProposal(
        patchId = "heal-2026-11-21-001",
        targetClass = "host.vorv.tools.NotifyTool",
        description = "Guard the title against empty strings. " + "Detail ".repeat(80),
        sha256 = sha,
        signature = "c2ln",
        surfaceVerified = verified,
        attempts = 2,
    )

    @Test
    fun `a verified proposal promises replacement and shows the full hash`() {
        val msg = MainActivity.buildPatchConsentMessage(proposal(verified = true))
        assertTrue(msg.contains("replaces the original at the next app start"))
        assertTrue(msg.contains("Verified: full public API kept"))
        assertTrue("the full 64-char SHA-256 must be shown", msg.contains(sha))
        assertTrue(msg.contains("DENY = nothing changes."))
        assertFalse("no ellipsis in the description", msg.contains("…"))
    }

    @Test
    fun `an unverified proposal does not claim replacement or verification`() {
        val msg = MainActivity.buildPatchConsentMessage(proposal(verified = false))
        assertTrue(msg.contains("Verified: NOT verified"))
        assertFalse(
            "must not promise that the class is replaced: $msg",
            msg.contains("replaces the original"),
        )
        assertTrue(
            "must say the patch may not replace the running class",
            msg.contains("may not replace the running class"),
        )
        assertTrue(msg.contains("the completeness check did not run"))
    }
}
