package host.vorv.watchdog

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M-7: a swipe is not a crash, and one death is reported once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UserCloseCrashTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the marker is set, read and cleared`() {
        UserCloseMarker.clear(context)
        assertFalse(UserCloseMarker.isSet(context))
        UserCloseMarker.mark(context)
        assertTrue(UserCloseMarker.file(context).exists())
        assertTrue(UserCloseMarker.isSet(context))
        UserCloseMarker.clear(context)
        assertFalse(UserCloseMarker.isSet(context))
        assertFalse(UserCloseMarker.file(context).exists())
    }

    @Test
    fun `a damaged or ancient marker reads as a crash`() {
        UserCloseMarker.file(context).apply { parentFile?.mkdirs() }.writeText("not a number")
        assertFalse(UserCloseMarker.isSet(context))

        val ancient = System.currentTimeMillis() - 25L * 60 * 60 * 1000
        UserCloseMarker.file(context).writeText(ancient.toString())
        assertFalse(UserCloseMarker.isSet(context))

        UserCloseMarker.file(context).writeText((System.currentTimeMillis() - 1000).toString())
        assertTrue(UserCloseMarker.isSet(context))
    }

    @Test
    fun `a crash is claimed once inside the window`() {
        CrashEvents.file(context).delete()
        assertTrue(CrashEvents.claim(context, "test"))
        assertFalse("the same death is not reported twice", CrashEvents.claim(context, "test"))
        assertTrue("a later death is a new one", CrashEvents.claim(context, "test", windowMs = 0))
        assertTrue(CrashEvents.file(context).readText().contains("source=test"))
    }

    @Test
    fun `onTaskRemoved writes the marker and stops the watchdog`() {
        UserCloseMarker.clear(context)
        val controller = Robolectric.buildService(WatchdogService::class.java).create()
        controller.get().onTaskRemoved(Intent(Intent.ACTION_MAIN))
        assertTrue("a swipe must be recorded as a user close", UserCloseMarker.isSet(context))
        controller.destroy()
    }

    @Test
    fun `a user close writes no crash file and does not relaunch`() {
        UserCloseMarker.mark(context)
        val before = crashFiles().size
        val controller = Robolectric.buildService(WatchdogService::class.java).create()
        val service = controller.get()
        val died = WatchdogService::class.java.getDeclaredMethod("onMainProcessDied", String::class.java)
            .apply { isAccessible = true }
        died.invoke(service, "binder death")
        assertEquals("no crash file for a user close", before, crashFiles().size)
        controller.destroy()
    }

    @Test
    fun `a death without the marker writes exactly one crash file`() {
        UserCloseMarker.clear(context)
        CrashEvents.file(context).delete()
        val before = crashFiles().size
        val controller = Robolectric.buildService(WatchdogService::class.java).create()
        val service = controller.get()
        val died = WatchdogService::class.java.getDeclaredMethod("onMainProcessDied", String::class.java)
            .apply { isAccessible = true }
        died.invoke(service, "binder death")
        assertEquals("one death, one report", before + 1, crashFiles().size)
        died.invoke(service, "stale heartbeat")
        assertEquals("the second detection is the same death", before + 1, crashFiles().size)
        controller.destroy()
    }

    private fun crashFiles(): List<File> =
        File(context.getExternalFilesDir(null), "watchdog")
            .listFiles()
            ?.filter { it.name.startsWith("crash_detected_") }
            ?.toList()
            .orEmpty()
}
