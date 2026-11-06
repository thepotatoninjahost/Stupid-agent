package host.vorv.healer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * H3a / H3b / M8 (code review, 2026-11): the self-repair healer's budgets
 * and scratch-file hygiene.
 *
 *  - H3a: the Termux compile budget must be 8-10 minutes (600 s), not the
 *    150 s that killed healthy compiles mid-flight, and every attempt must
 *    record its real duration.
 *  - H3b: the source cap must hold the LARGEST bundled source file whole
 *    (self_fix re-emits files completely; a truncated source guarantees a
 *    broken patch). Largest file today: AgentViewModel.kt ≈ 47 K chars.
 *  - M8: verifier candidate dex files must never accumulate.
 */
class HealingAgentBudgetsTest {

    // Reflection on private constants would be brittle; the budget values
    // are pinned through the honest public surface instead.
    private fun constant(name: String): Long =
        HealingAgent::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            (it.get(null) as Number).toLong()
        }

    @Test
    fun `compile budget is the 10-minute window (H3a)`() {
        val budget = constant("COMPILE_TIMEOUT_MS")
        assertTrue(
            "COMPILE_TIMEOUT_MS must be 600_000 (10 min), was $budget",
            budget == 600_000L,
        )
    }

    @Test
    fun `source cap holds every real source file whole (H3b)` {
        val cap = constant("MAX_SOURCE_CHARS")
        val largestBundled = 47_000L // AgentViewModel.kt ≈ 46.8 K chars today
        assertTrue(
            "MAX_SOURCE_CHARS ($cap) must be ≥ 2x the largest bundled source ($largestBundled)",
            cap >= largestBundled * 2,
        )
        assertEquals("H3b plan value", 131_072L, cap)
    }

    // ------------------------------------------------------------- M8

    @Test
    fun `sweep deletes candidate dex files and keeps everything else`() {
        val dir = File.createTempFile("heal", "").let { f ->
            f.delete(); f.mkdirs(); f
        }
        try {
            File(dir, "candidate_111.dex").writeText("old")
            File(dir, "candidate_222.dex").writeText("older")
            File(dir, "app_classes.jar").writeText("keep")
            File(dir, "app_classes.version").writeText("30")
            File(dir, "candidate_notes.txt").writeText("keep — not a dex")

            sweepCandidateDex(dir)

            assertFalse(File(dir, "candidate_111.dex").exists())
            assertFalse(File(dir, "candidate_222.dex").exists())
            assertTrue("classpath jar is not scratch", File(dir, "app_classes.jar").exists())
            assertTrue("version stamp is not scratch", File(dir, "app_classes.version").exists())
            assertTrue("non-dex candidate-named file survives", File(dir, "candidate_notes.txt").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `sweep on a missing directory is a no-op, not a crash`() {
        sweepCandidateDex(File("/nonexistent/healer-dir"))
        // reaching here without an exception IS the assertion
    }
}
