package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Step 10: the champion pointer, and the rollback that must never re-download. */
class GenerationStoreTest {

    private val root: File = Files.createTempDirectory("gens").toFile()

    private fun artifact(name: String, body: String): Pair<String, String> {
        val file = File(root, name)
        file.writeText(body)
        return name to GenerationStore.sha256(file)
    }

    private fun store() = GenerationStore(root)

    private fun generation(id: Int, name: String, body: String, accepted: Boolean = true): GenerationStore.Generation {
        val (path, hash) = artifact(name, body)
        return GenerationStore.Generation(
            id = id, createdAt = id.toLong(), origin = "phone",
            artifactPath = path, artifactSha256 = hash, sizeBytes = 0,
            valLoss = 1.0 - id / 10.0, accepted = accepted,
            decision = if (accepted) "won" else "refused: loss up",
            corpusSha256 = "abc", config = "lr=3e-4,steps=200",
        )
    }

    @Test
    fun `an accepted generation becomes the champion and is recorded`() {
        val result = store().add(generation(1, "g1.bin", "weights one"), accept = true)
        assertTrue((result as GenerationStore.Result.Ok).message, result.message.contains("champion"))
        assertEquals(1, store().champion()?.id)
        assertEquals(1, store().generations().size)
    }

    @Test
    fun `a refused generation is kept for inspection but never serves`() {
        val s = store()
        s.add(generation(1, "g1.bin", "one"), accept = true)
        val refused = s.add(generation(2, "g2.bin", "two", accepted = false), accept = false)
        assertTrue((refused as GenerationStore.Result.Ok).message.contains("not promoted"))
        assertEquals(1, s.champion()?.id)
        assertEquals(2, s.generations().size)
        assertTrue(s.promote(2) is GenerationStore.Result.Refused)
    }

    @Test
    fun `rollback returns to the previous accepted generation and keeps the newer one`() {
        val s = store()
        s.add(generation(1, "g1.bin", "one"), accept = true)
        s.add(generation(2, "g2.bin", "two"), accept = true)
        assertEquals(2, s.champion()?.id)
        val back = s.rollback()
        assertTrue((back as GenerationStore.Result.Ok).message.contains("generation 1"))
        assertEquals(1, s.champion()?.id)
        assertEquals(2, s.generations().size)
    }

    @Test
    fun `rollback with nothing older says so in words`() {
        val s = store()
        s.add(generation(1, "g1.bin", "one"), accept = true)
        val refused = s.rollback()
        assertTrue(refused is GenerationStore.Result.Refused)
        assertTrue((refused as GenerationStore.Result.Refused).message.contains("nothing to go back to"))
    }

    @Test
    fun `a file whose bytes changed cannot be promoted`() {
        val s = store()
        s.add(generation(1, "g1.bin", "one"), accept = true)
        s.add(generation(2, "g2.bin", "two"), accept = true)
        File(root, "g1.bin").writeText("tampered")
        val back = s.rollback()
        assertTrue(back is GenerationStore.Result.Refused)
        assertTrue((back as GenerationStore.Result.Refused).message.contains("changed"))
        assertEquals(2, s.champion()?.id)
    }

    @Test
    fun `a missing file is refused instead of being downloaded again`() {
        val s = store()
        s.add(generation(1, "g1.bin", "one"), accept = true)
        s.add(generation(2, "g2.bin", "two"), accept = true)
        File(root, "g1.bin").delete()
        val back = s.rollback()
        assertTrue((back as GenerationStore.Result.Refused).message.contains("missing"))
    }

    @Test
    fun `adding a generation with the wrong hash is refused before anything is written`() {
        val s = store()
        val bad = generation(1, "g1.bin", "one").copy(artifactSha256 = "0".repeat(64))
        val result = s.add(bad, accept = true)
        assertTrue(result is GenerationStore.Result.Refused)
        assertNull(s.champion())
        assertEquals(0, s.generations().size)
    }

    @Test
    fun `a generation whose file never arrived is refused`() {
        val s = store()
        val ghost = generation(1, "g1.bin", "one").copy(artifactPath = "never-written.bin")
        val result = s.add(ghost, accept = true)
        assertTrue((result as GenerationStore.Result.Refused).message.contains("missing"))
        assertNull(s.champion())
    }

    @Test
    fun `the same id cannot be recorded twice`() {
        val s = store()
        s.add(generation(1, "g1.bin", "one"), accept = true)
        val again = s.add(generation(1, "g1b.bin", "other"), accept = true)
        assertTrue(again is GenerationStore.Result.Refused)
        assertEquals(1, s.generations().size)
    }

    @Test
    fun `the index survives being reopened and keeps the pointer`() {
        store().add(generation(1, "g1.bin", "one"), accept = true)
        store().add(generation(2, "g2.bin", "two"), accept = true)
        val fresh = GenerationStore(root)
        assertEquals(2, fresh.champion()?.id)
        assertEquals(listOf(1, 2), fresh.generations().map { it.id })
        assertEquals("phone", fresh.champion()?.origin)
    }

    @Test
    fun `the index is hand-written json, so shrinking the app cannot rename its keys`() {
        store().add(generation(1, "g1.bin", "one"), accept = true)
        val text = File(root, "generations.json").readText()
        for (key in listOf("generations", "championId", "artifactPath", "artifactSha256", "valLoss", "config")) {
            assertTrue("index lost key $key", text.contains("\"$key\""))
        }
    }

    @Test
    fun `a corrupt index reports no champion rather than inventing one`() {
        store().add(generation(1, "g1.bin", "one"), accept = true)
        File(root, "generations.json").writeText("{not json")
        assertNull(store().champion())
        assertTrue(store().generations().isEmpty())
        // The broken file is left in place for a human to look at.
        assertTrue(File(root, "generations.json").exists())
    }
}
