package host.vorv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BootTracerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val separator = "\n${"=".repeat(60)}\n"

    @Before fun setUp() = BootTracer.clear(context)
    @After fun tearDown() = BootTracer.clear(context)

    @Test fun firstBootHasNoPreviousTrace() {
        BootTracer.start(context)
        assertNull(BootTracer.readPreviousTrace(context))
        assertTrue(BootTracer.previousBootCompleted(context))
    }

    @Test fun callbacksAfterCompletionDoNotMakeBootIncomplete() {
        BootTracer.start(context)
        BootTracer.complete(context)
        BootTracer.step(context, "geniex_init_success")
        BootTracer.start(context)
        assertTrue(BootTracer.previousBootCompleted(context))
    }

    @Test fun incompletePreviousBootIsDetected() {
        BootTracer.start(context)
        BootTracer.step(context, "init_room_begin")
        BootTracer.start(context)
        assertFalse(BootTracer.previousBootCompleted(context))
        assertTrue(BootTracer.readPreviousTrace(context)!!.contains("init_room_begin"))
    }

    @Test fun oldWatchdogSectionIsFoldedIntoItsMainBoot() {
        BootTracer.traceFile(context).apply {
            parentFile!!.mkdirs()
            writeText(separator + "[01] BOOT_STARTED — pid=10\n[02] init_room_end\n" +
                separator + "[03] BOOT_STARTED — pid=20\n[04] watchdog_process_skip — pid=20\n" +
                "[05] BOOT_COMPLETE — ui is up\n[06] geniex_init_success\n" +
                separator + "[07] BOOT_STARTED — pid=30\n")
        }
        val previous = BootTracer.readPreviousTrace(context)!!
        assertTrue(previous.contains("pid=10"))
        assertTrue(previous.contains("BOOT_COMPLETE"))
        assertFalse(previous.contains("watchdog_process_skip"))
        assertTrue(BootTracer.previousBootCompleted(context))
    }

    @Test fun differentPidDoesNotDiscardARealBoot() {
        val trace = separator + "[01] BOOT_STARTED — pid=10\n" +
            "[02] watchdog_process_skip — pid=20\n" + separator + "[03] BOOT_STARTED — pid=30\n"
        assertEquals(2, BootTracer.mainBootSections(trace).size)
    }
}
