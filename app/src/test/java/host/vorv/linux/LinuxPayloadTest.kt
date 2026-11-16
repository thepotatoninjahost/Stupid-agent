package host.vorv.linux

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Inspecting the payload must never throw, whatever the install looks like:
 * the Setup card depends on it, and a crash here would read as "no Linux".
 */
@RunWith(RobolectricTestRunner::class)
class LinuxPayloadTest {

    @Test
    fun `inspect always answers, even with no payload installed`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val state = LinuxPayload(ctx).inspect()
        assertNotNull(state.listing)
        assertNotNull(state.extracted)
    }

    @Test
    fun `the payload report answers instead of throwing`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val state = LinuxPayload(ctx).payloadReport()
        assertNotNull(state.listing)
    }

    /**
     * F-8: this used to be `repair()`, which copied proot into app storage and
     * exec'd it — an operation Android 10+ always refuses. The only honest
     * answer is a diagnosis, and a test install has no payload, so the
     * diagnosis must name that rather than claim a repair.
     */
    @Test
    fun `a payload-less install is reported as such, not as repaired`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val state = LinuxPayload(ctx).payloadReport()
        if (!state.payloadInApk) {
            assertNotNull("a build with no payload must say so", state.diagnosis)
            assertTrue(
                "the diagnosis must point at the build, not at the user",
                state.diagnosis!!.contains("no proot for any ABI"),
            )
        }
    }
}
