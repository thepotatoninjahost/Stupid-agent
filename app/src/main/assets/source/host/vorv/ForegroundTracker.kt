package host.vorv

import android.app.Activity
import android.app.Application

/**
 * M6 (code review, 2026-11): the heartbeat writer must BACK OFF when no
 * Activity is resumed — a 5-second flash write for the whole process
 * lifetime was battery/storage wear with no diagnostic value while the app
 * sits in the background. This tracker counts STARTED activities (the
 * standard "is any UI visible" signal, and the safe pairing for
 * started/stopped callbacks): foreground = at least one activity in the
 * started state.
 *
 * The counting rules are pure JVM and unit-tested (ForegroundTrackerTest),
 * including a real activity start/stop cycle driven through Robolectric's
 * activity controller to pin the Application-callbacks wiring.
 *
 * Threading: started/stopped callbacks arrive on the MAIN thread (Android
 * guarantees lifecycle callbacks there), so the counter has a single
 * writer; the heartbeat thread only READS [isForeground], and @Volatile
 * gives that read cross-thread visibility.
 */
internal class ForegroundTracker {

    /** Number of activities currently in the STARTED state. */
    @Volatile
    private var startedCount: Int = 0

    /** True while at least one activity is started (UI visible). */
    val isForeground: Boolean
        get() = startedCount > 0

    fun activityStarted() {
        startedCount += 1
    }

    /** Guarded so an unmatched stop can never drive the state negative. */
    fun activityStopped() {
        if (startedCount > 0) startedCount -= 1
    }

    /** Wires this tracker into the Application's lifecycle callbacks. */
    fun lifecycleCallbacks(): Application.ActivityLifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                activityStarted()
            }

            override fun onActivityStopped(activity: Activity) {
                activityStopped()
            }
        }
}
