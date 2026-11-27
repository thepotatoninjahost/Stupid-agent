package host.vorv.healer

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-11-27 crash left Android's "CRASH (uncaught Java exception)" record
 * and no Java report at all, so nobody could name the class or the line. A Java
 * crash has no OS tombstone — the stack must be in the app's own report. These
 * pin the contents that make a report useful.
 */
class CrashTextTest {

    private class Boom : IllegalStateException("the guest command failed")

    @Test
    fun `a report names the thread, the exception, the message and the crashing frame`() {
        val text = CrashText.format(
            threadName = "vorv-guest-stdout",
            throwable = Boom(),
            bootTrace = "[09:55:37.700] run_linux — begun",
        )
        assertTrue(text.contains("vorv-guest-stdout"))
        // The concrete class is what the JVM names; the stack below carries it
        // too, exactly as an uncaught exception prints.
        assertTrue(text.contains("Boom"))
        assertTrue(text.contains("the guest command failed"))
        assertTrue("the stack must contain a real frame", text.contains("CrashTextTest"))
        assertTrue(text.contains("run_linux"))
        assertTrue("say who wrote it", text.contains("crash handler"))
        assertTrue("a Java crash has no tombstone", text.contains("no OS tombstone"))
    }

    @Test
    fun `the fallback worker report carries the same stack`() {
        val text = CrashText.format(
            threadName = "main",
            exceptionClass = "java.lang.OutOfMemoryError",
            message = "Failed to allocate",
            stackTrace = "java.lang.OutOfMemoryError: Failed to allocate\n\tat host.vorv.Kernel.run(Kernel.kt:42)",
            bootTrace = "[10:00:00.000] BOOT_COMPLETE",
            writtenBy = "CrashReportWorker, in a fresh process after the crash",
        )
        assertTrue(text.contains("CrashReportWorker"))
        assertTrue(text.contains("OutOfMemoryError"))
        assertTrue(text.contains("Kernel.kt:42"))
        assertTrue(text.contains("BOOT_COMPLETE"))
    }
}
