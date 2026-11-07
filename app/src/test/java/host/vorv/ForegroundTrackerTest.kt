package host.vorv

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M6 (code review, 2026-11): the heartbeat backs off while no activity is
 * started. These tests pin the counting rules AND the real wiring — a live
 * activity start/stop cycle through Robolectric's controller must flip the
 * tracker via the Application's ActivityLifecycleCallbacks, exactly as the
 * framework will on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ForegroundTrackerTest {

    @Test
    fun `a fresh tracker starts in the background`() {
        assertFalse("no activity yet — background", ForegroundTracker().isForeground)
    }

    @Test
    fun `one started activity is foreground`() {
        val tracker = ForegroundTracker()
        tracker.activityStarted()
        assertTrue(tracker.isForeground)
    }

    @Test
    fun `stops must be balanced before background returns`() {
        val tracker = ForegroundTracker()
        tracker.activityStarted()
        tracker.activityStarted()
        tracker.activityStopped()
        assertTrue("second activity is still visible", tracker.isForeground)
        tracker.activityStopped()
        assertFalse("all activities stopped — background", tracker.isForeground)
    }

    @Test
    fun `an unmatched stop never drives the state negative`() {
        val tracker = ForegroundTracker()
        tracker.activityStopped()
        assertFalse(tracker.isForeground)
        tracker.activityStarted()
        assertTrue("tracker still works after the unmatched stop", tracker.isForeground)
    }

    @Test
    fun `a real activity lifecycle flips the tracker through the application callbacks`() {
        val tracker = ForegroundTracker()
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.registerActivityLifecycleCallbacks(tracker.lifecycleCallbacks())

        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.setup() // create + start + resume → onActivityStarted fires
        assertTrue("a visible activity means foreground", tracker.isForeground)

        controller.pause().stop() // → onActivityStopped fires
        assertFalse("all activities stopped means background", tracker.isForeground)
    }
}
