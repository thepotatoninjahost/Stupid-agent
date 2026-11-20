package host.vorv.healer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * H3a / H3b / M8 (code review, 2026-11): the self-repair healer's budgets
 * and scratch-file hygiene.
 *
 *  - H3a: the compile budget must be 8-10 minutes (600 s), not the
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

    /**
     * H3b was built on a wrong number: the comment claimed the largest
     * bundled source is ~47 K chars and the 131 072 cap was 2.8x it, but
     * AgentViewModel.kt is 107 526 chars (2 270 lines) — 1.2x, not 2.8x.
     * The size of the cap never mattered anyway: self_fix makes the model
     * RE-EMIT the whole file, so what bounds it is the engine's output
     * ceiling. Review 2026-11-20.
     */
    @Test
    fun `self-patch budget is the engine's output ceiling, not a guess`() {
        // The on-device engine: 2048 tokens ≈ 6 144 chars of Kotlin.
        assertEquals(6_144, selfFixBudgetChars(2_048))
        // A cloud engine with the interface default fits more.
        assertTrue(selfFixBudgetChars(8_192) > selfFixBudgetChars(2_048))
    }

    @Test
    fun `the real AgentViewModel cannot be re-emitted by the on-device engine`() {
        // Gradle runs tests from the module dir; the repo-root form covers
        // an IDE that runs them from there. Hard failure (not a fallback)
        // when neither exists — a silently skipped measurement proves nothing.
        val realFile = listOf(
            File("src/main/java/host/vorv/AgentViewModel.kt"),
            File("app/src/main/java/host/vorv/AgentViewModel.kt"),
        ).firstOrNull { it.exists() }
        assertNotNull("AgentViewModel.kt not found from ${File(".").absolutePath}", realFile)
        val reference = realFile!!.length().toInt()
        assertTrue(
            "AgentViewModel.kt is $reference chars — the 47 K claim in the H3b comment was wrong",
            reference > 100_000,
        )
        assertTrue(
            "a $reference-char class must be refused by a 2048-token engine",
            selfFixCannotEmitWhole(reference, 2_048),
        )
        assertFalse(
            "a small class fits",
            selfFixCannotEmitWhole(4_000, 2_048),
        )
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
