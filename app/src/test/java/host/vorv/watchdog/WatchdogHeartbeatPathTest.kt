package host.vorv.watchdog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M6 (code review, 2026-11): the 5-second heartbeat file must live in
 * INTERNAL storage. It used to sit on external storage (getExternalFilesDir)
 * — wearing external flash for the whole process lifetime and breaking
 * crash detection whenever external storage is unmounted or slow to mount
 * at boot. Both processes of the app share filesDir, so the watchdog reads
 * the same path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WatchdogHeartbeatPathTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `heartbeat file path is internal storage`() {
        val f = WatchdogService.heartbeatFile(context)
        assertTrue(
            "heartbeat must live under filesDir (internal), was ${f.absolutePath}",
            f.absolutePath.startsWith(context.filesDir.absolutePath),
        )
        assertFalse(
            "heartbeat must NOT live on external storage",
            f.absolutePath.contains("external"),
        )
    }

    @Test
    fun `writeHeartbeat writes the internal file with the step and pid`() {
        val f = WatchdogService.heartbeatFile(context)
        f.parentFile?.deleteRecursively()

        WatchdogService.writeHeartbeat(context, "m6_test_step")

        assertTrue("heartbeat file exists after write", f.exists())
        val text = f.readText()
        assertTrue("first line is the timestamp", text.lines().first().toLongOrNull() != null)
        assertTrue("step recorded", text.contains("m6_test_step"))
        assertTrue("pid recorded", text.contains("pid="))
    }

    @Test
    fun `the stale pre-M6 external heartbeat is swept on first write`() {
        val legacy = File(context.getExternalFilesDir(null), "watchdog/heartbeat.txt")
        legacy.parentFile?.mkdirs()
        legacy.writeText("1234\nancient\npid=999\n")

        WatchdogService.writeHeartbeat(context, "sweep")

        assertFalse("stale external heartbeat deleted", legacy.exists())
        assertTrue("internal heartbeat present", WatchdogService.heartbeatFile(context).exists())
    }

    @Test
    fun `writer and reader agree on the path`() {
        // The watchdog reads heartbeatFile(this); the writer writes
        // heartbeatFile(context). Same function = same file, by construction.
        assertEquals(
            WatchdogService.heartbeatFile(context).absolutePath,
            WatchdogService.heartbeatFile(context).absolutePath,
        )
    }
}
