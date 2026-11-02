package host.vorv.healer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CrashParser tests, including the v0.6.9 fix: the crashed-class extractor
 * used to swallow the METHOD name ("…NotifyTool.run"), which then failed
 * every source lookup.
 */
class CrashParserTest {

    private val trace = """
        java.lang.NullPointerException: title must not be null
            at host.vorv.tools.NotifyTool.run(NotifyTool.kt:66)
            at host.vorv.kernel.CognitiveKernel.run(CognitiveKernel.kt:201)
            at android.app.Activity.performCreate(Activity.java:9000)
    """.trimIndent()

    @Test
    fun `extracts the class without the trailing method name`() {
        assertEquals(
            "host.vorv.tools.NotifyTool",
            CrashParser.extractCrashedClass(trace),
        )
    }

    @Test
    fun `strips inner class suffixes`() {
        val inner = """
            java.lang.RuntimeException: boom
                at host.vorv.tools.ShellTool${'$'}Companion.quote(SystemTools.kt:120)
        """.trimIndent()
        assertEquals("host.vorv.tools.ShellTool", CrashParser.extractCrashedClass(inner))
    }

    @Test
    fun `returns null when no host vorv frame exists`() {
        assertNull(CrashParser.extractCrashedClass(
            "java.lang.Exception: x\n    at android.app.Activity.onCreate(Activity.java:1)"))
    }

    @Test
    fun `boot trace breadcrumb maps to the boot class`() {
        assertEquals(
            "host.vorv.VorvApplication",
            CrashParser.inferCrashedClassFromBootTrace("[00:00.100] init_room_begin"),
        )
        assertNull(CrashParser.inferCrashedClassFromBootTrace("[00:00.100] unknown_step"))
    }
}
