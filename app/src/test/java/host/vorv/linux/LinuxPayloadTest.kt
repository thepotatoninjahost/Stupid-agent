package host.vorv.linux

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
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
    fun `repair with no payload answers instead of throwing`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val state = LinuxPayload(ctx).repair()
        assertNotNull(state.listing)
    }
}
