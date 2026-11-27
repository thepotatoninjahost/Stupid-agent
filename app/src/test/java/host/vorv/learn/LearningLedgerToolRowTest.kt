package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire the review asked for: a build that fails has to leave a row the
 * trainer can see. Before this, `Kind.BUILD_RESULT` and `Kind.TOOL_CALL` were
 * declared and never written, so a failed build and a successful one produced
 * exactly the same training data as no build at all: nothing.
 */
class LearningLedgerToolRowTest {

    @Test
    fun `a failed build is a BUILD_RESULT row that keeps the compiler error`() {
        val row = LearningLedger.toolRow(
            ts = 1L,
            tool = "build_app",
            ok = false,
            output = "",
            stderr = "Main.java:4: error: ';' expected",
            error = "javac failed",
        )
        assertEquals(LearningLedger.Kind.BUILD_RESULT, row.kind)
        assertFalse(row.ok!!)
        assertEquals("tool:build_app", row.errorClass)
        assertTrue(row.outputText.contains("';' expected"))
        assertTrue(row.outputText.contains("javac failed"))
    }

    @Test
    fun `installing and testing an app is also a build result`() {
        val row = LearningLedger.toolRow(1L, "native_test", true, "ok #1 …", "")
        assertEquals(LearningLedger.Kind.BUILD_RESULT, row.kind)
    }

    @Test
    fun `every other tool is a TOOL_CALL row, and a success carries no error class`() {
        val row = LearningLedger.toolRow(1L, "linux_run", true, "Linux 6.6.127", "")
        assertEquals(LearningLedger.Kind.TOOL_CALL, row.kind)
        assertTrue(row.ok!!)
        assertNull(row.errorClass)
    }

    @Test
    fun `a megabyte of build log is capped, not stored`() {
        val row = LearningLedger.toolRow(1L, "build_app", true, "x".repeat(2_000_000), "")
        assertTrue(row.outputText.length <= LearningLedger.MAX_TEXT)
        assertTrue(row.inputText.length <= LearningLedger.MAX_TEXT)
    }

    @Test
    fun `a tool result is evidence, never a training target`() {
        assertFalse(LearningLedger.Kind.BUILD_RESULT.isTrainingTarget)
        assertFalse(LearningLedger.Kind.TOOL_CALL.isTrainingTarget)
    }
}
