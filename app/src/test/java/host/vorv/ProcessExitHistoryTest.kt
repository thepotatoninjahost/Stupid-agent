package host.vorv

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessExitHistoryTest {
    @Test fun distinguishesNativeCrashFromSignalAndUnknownReason() {
        assertEquals("native_crash", ProcessExitHistory.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("signaled", ProcessExitHistory.reasonName(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals("unrecognized", ProcessExitHistory.reasonName(-123))
    }
}
