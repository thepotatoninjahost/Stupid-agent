package host.vorv.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.9 repair-model labeling: the engine label says WHICH model wrote a
 * patch. A 3B-generated patch must never masquerade as a 7B one (user
 * feedback: "what good is a 3B model going to do" — answer: it doesn't get
 * to, unless the 7B is missing from the device, and then the label admits
 * it). Pure function — no GenieX, safe in plain JUnit.
 */
class ModelManagerRepairLabelTest {

    @Test fun `7B label names the model and the purpose`() {
        assertEquals(
            "Qwen2.5-Coder-7B (self-fix)",
            ModelManager.repairLabel(ModelChoice.QWEN_7B, "self-fix"),
        )
    }

    @Test fun `3B label admits the 7B was not downloaded`() {
        val label = ModelManager.repairLabel(ModelChoice.QWEN_3B, "self-repair")
        assertTrue(label.startsWith("Qwen2.5-Coder-3B (self-repair"))
        assertTrue(
            "the 3B fallback label must say the 7B is missing: $label",
            label.contains("7B not downloaded"),
        )
    }

    @Test fun `every label carries its purpose and names its model`() {
        for (choice in ModelChoice.entries) {
            val label = ModelManager.repairLabel(choice, "crash analysis")
            assertTrue("label must name its purpose: $label", label.contains("crash analysis"))
            assertTrue(
                "label must name its model: $label",
                label.contains(if (choice == ModelChoice.QWEN_7B) "7B" else "3B"),
            )
        }
    }

    @Test fun `the two models never share a label`() {
        val a = ModelManager.repairLabel(ModelChoice.QWEN_7B, "self-fix")
        val b = ModelManager.repairLabel(ModelChoice.QWEN_3B, "self-fix")
        assertTrue(a != b)
    }
}
