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
 *
 * M6 backoff (post-sweep audit, 2026-11): the review's demanded mechanism —
 * backing the write off when no activity is resumed — is pinned here too:
 * the cadence by foreground state, the cadence line recorded in the file,
 * the adaptive stale rule (a backgrounded-but-alive main process can never
 * be declared dead because of the backoff), and the one-shot legacy sweep.
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

    @Test
    fun `writeHeartbeat records the cadence it was written at`() {
        val f = WatchdogService.heartbeatFile(context)
        f.parentFile?.deleteRecursively()

        WatchdogService.writeHeartbeat(context, "m6_foreground_step")
        assertTrue(
            "default write records the 5 s foreground cadence",
            f.readText().contains("cadence=${WatchdogService.HEARTBEAT_INTERVAL_MS}"),
        )

        WatchdogService.writeHeartbeat(
            context, "m6_background_step", WatchdogService.HEARTBEAT_BACKGROUND_INTERVAL_MS,
        )
        assertTrue(
            "backoff write records the 60 s background cadence",
            f.readText().contains("cadence=${WatchdogService.HEARTBEAT_BACKGROUND_INTERVAL_MS}"),
        )
    }

    @Test
    fun `heartbeatIntervalMs picks the cadence by foreground state`() {
        assertEquals(
            WatchdogService.HEARTBEAT_INTERVAL_MS,
            WatchdogService.heartbeatIntervalMs(foreground = true),
        )
        assertEquals(
            WatchdogService.HEARTBEAT_BACKGROUND_INTERVAL_MS,
            WatchdogService.heartbeatIntervalMs(foreground = false),
        )
    }

    @Test
    fun `fresh foreground heartbeat is not stale and three intervals is stale`() {
        assertFalse(WatchdogService.heartbeatIsStale(ageMs = 14_999, cadenceMs = 5_000))
        assertTrue(WatchdogService.heartbeatIsStale(ageMs = 15_000, cadenceMs = 5_000))
    }

    @Test
    fun `background cadence keeps a live-but-backgrounded app from being declared dead`() {
        // 100 s of silence at the 60 s background cadence is NORMAL under
        // the M6 backoff — the old fixed 15 s rule would have called it stale.
        assertFalse(WatchdogService.heartbeatIsStale(ageMs = 100_000, cadenceMs = 60_000))
        // Only after three background cadences of silence may the fallback
        // act — and it still requires the main process to be really gone.
        assertTrue(WatchdogService.heartbeatIsStale(ageMs = 180_000, cadenceMs = 60_000))
    }

    @Test
    fun `legacy heartbeat without a cadence line follows the 5 s rule`() {
        assertFalse(WatchdogService.heartbeatIsStale(ageMs = 14_999, cadenceMs = null))
        assertTrue(WatchdogService.heartbeatIsStale(ageMs = 15_000, cadenceMs = null))
    }

    @Test
    fun `the legacy external sweep runs on every write`() {
        val legacy = File(context.getExternalFilesDir(null), "watchdog/heartbeat.txt")
        legacy.parentFile?.mkdirs()
        legacy.writeText("1234\nancient\npid=999\n")

        WatchdogService.writeHeartbeat(context, "first_write_sweeps")
        assertFalse("first write sweeps the legacy file", legacy.exists())

        // A legacy file reappearing mid-process (impossible in practice —
        // only an OLD app version writes there) would STILL be swept: the
        // sweep is stateless and unconditional, so no write can ever leave
        // a stale external file behind as a false crash signal.
        legacy.parentFile?.mkdirs()
        legacy.writeText("1234\nancient\npid=999\n")
        WatchdogService.writeHeartbeat(context, "second_write_sweeps_too")
        assertFalse("second write sweeps it again — stateless", legacy.exists())
        assertTrue("internal heartbeat present", WatchdogService.heartbeatFile(context).exists())
    }
}
