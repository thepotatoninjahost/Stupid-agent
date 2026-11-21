package host.vorv.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repair label says WHICH model wrote a patch. There is one on-device
 * model now, so the label names it and nothing else. Pure function — no
 * GenieX, safe in plain JUnit.
 */
class ModelManagerRepairLabelTest {

    @Test fun `the label names the model and the purpose`() {
        assertEquals(
            "OmniCoder-9B (self-fix)",
            ModelManager.repairLabel(ModelChoice.OMNICODER9B, "self-fix"),
        )
    }

    @Test fun `every label carries its purpose and names its model`() {
        for (choice in ModelChoice.entries) {
            val label = ModelManager.repairLabel(choice, "crash analysis")
            assertTrue("label must name its purpose: $label", label.contains("crash analysis"))
            assertTrue(
                "label must name its model: $label",
                label.contains(choice.displayName.substringBefore(" (")),
            )
        }
    }
}
