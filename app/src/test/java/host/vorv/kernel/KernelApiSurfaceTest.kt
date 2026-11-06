package host.vorv.kernel

import host.vorv.data.entity.ToolCall
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * C-1 regression tests (code review, 2026-11): `trace` and `emitTelemetry`
 * are members of the SHARED [KernelApi] interface, not of the concrete
 * CognitiveKernel class.
 *
 * THE BUG: AgentViewModel held the kernel as `CognitiveKernel` and re-cast
 * the PatchRegistry result with `as? CognitiveKernel`. A class hot-patched
 * by the healer loads under its OWN ClassLoader, so it is NEVER an
 * instanceof the original class — the cast always failed for a real patch
 * and the freshly built patched kernel was silently discarded in favor of
 * the original. Self-patch dispatch for the kernel was dead code: every
 * approved, verified, probation-passed kernel patch still did nothing.
 *
 * These tests pin the interface surface: everything the ViewModel needs is
 * reachable through a plain KernelApi reference — no concrete-type cast in
 * sight, so a cross-classloader kernel (the real patch situation) works.
 */
class KernelApiSurfaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Minimal persistence sink — no Room, no Android (audit H-6 style). */
    private class FakeSink : KernelEventSink {
        override suspend fun logEpisode(kind: String, summary: String, detailsJson: String) {}
        override suspend fun insertToolCall(call: ToolCall) {}
        override suspend fun recordSkillResult(toolName: String, ok: Boolean) {}
        override suspend fun setTaskIterations(taskId: String, iterations: Int) {}
    }

    @Test
    fun `telemetry flows through a plain KernelApi reference`() = runTest {
        // The reference type is KernelApi — exactly what AgentViewModel holds
        // after the fix. Any member needed off the concrete type breaks this
        // line's compilation, which is the point.
        val api: KernelApi = CognitiveKernel(FakeSink(), emptyList(), tmp.newFolder())

        val received = mutableListOf<TraceStep>()
        val job = launch { api.trace.take(1).toList(received) }
        runCurrent()

        api.emitTelemetry(31.4f, 1234L, "local")
        runCurrent()
        job.join()

        assertEquals(
            listOf<TraceStep>(TraceStep.Telemetry(31.4f, 1234L, "local")),
            received,
        )
    }

    @Test
    fun `KernelApi declares the full dispatch surface the ViewModel needs`() {
        val members = KernelApi::class.java.methods.map { it.name }.toSet()
        assertTrue("run must be on the interface", "run" in members)
        assertTrue(
            "emitTelemetry must be on the interface (the member that caused the dead cast)",
            "emitTelemetry" in members,
        )
        assertTrue("trace must be on the interface", "getTrace" in members)
    }
}
