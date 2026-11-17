package host.vorv.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.9.13 — the header said "Cloud — needs API key" while a downloaded on-phone
 * coder sat unused. These pin the four answers so the label and the engine can
 * never disagree again.
 */
class BrainChoiceTest {

    @Test
    fun `a chosen local mode is never quietly replaced by the cloud`() {
        assertEquals("local", BrainChoice.mode("local", hasApiKey = true, localModelReady = false))
        assertFalse(BrainChoice.usable("local", hasApiKey = true, localModelReady = false))
    }

    @Test
    fun `no key but a downloaded coder runs on the coder`() {
        assertEquals("local", BrainChoice.mode("cloud", hasApiKey = false, localModelReady = true))
        assertTrue(BrainChoice.usable("cloud", hasApiKey = false, localModelReady = true))
        assertEquals(
            "no cloud key — running on the on-phone coder",
            BrainChoice.notice("cloud", hasApiKey = false, localModelReady = true),
        )
    }

    @Test
    fun `a key keeps the cloud in charge`() {
        assertEquals("cloud", BrainChoice.mode("cloud", hasApiKey = true, localModelReady = true))
        assertTrue(BrainChoice.usable("cloud", hasApiKey = true, localModelReady = true))
        assertNull(BrainChoice.notice("cloud", hasApiKey = true, localModelReady = true))
    }

    @Test
    fun `nothing downloaded and no key means no brain, said plainly`() {
        assertEquals("cloud", BrainChoice.mode("cloud", hasApiKey = false, localModelReady = false))
        assertFalse(BrainChoice.usable("cloud", hasApiKey = false, localModelReady = false))
        assertNull(BrainChoice.notice("cloud", hasApiKey = false, localModelReady = false))
    }
}
