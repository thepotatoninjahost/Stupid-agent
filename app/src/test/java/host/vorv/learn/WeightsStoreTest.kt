package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WeightsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cfg = VorvCore.CoreConfig(vocab = 12, d = 8, layers = 1, heads = 2, kvHeads = 1, ff = 16, ctx = 8, name = "TINY")

    private fun meta(gen: Int) = WeightsStore.Meta(
        generation = gen, created = 1_700_000_000_000, trainLoss = 1.25, valLoss = 1.5,
        corpusRows = 40, steps = 100, seed = 3, note = "first run",
    )

    @Test
    fun aSavedGenerationLoadsBackByteForByte() {
        val root = tmp.newFolder("brain")
        val core = VorvCore(cfg, seed = 9)
        val saved = WeightsStore.save(root, core, meta(1))
        assertTrue(saved.sha256.length == 64)
        assertTrue(File(saved.dir, WeightsStore.MANIFEST).readText().contains("\"params\": ${core.parameterCount}"))
        val loaded = WeightsStore.load(root, 1)
        assertEquals(core.parameterCount, loaded.parameterCount)
        for ((a, b) in core.params.zip(loaded.params)) {
            assertTrue(a.data.contentEquals(b.data))
        }
        val ids = intArrayOf(1, 2, 3)
        assertEquals(core.lossValue(ids), loaded.lossValue(ids), 0f)
        assertEquals(listOf(1), WeightsStore.generations(root))
    }

    @Test
    fun activePointerOnlyPointsAtARealGeneration() {
        val root = tmp.newFolder("brain")
        assertNull(WeightsStore.active(root))
        WeightsStore.save(root, VorvCore(cfg, seed = 1), meta(1))
        WeightsStore.save(root, VorvCore(cfg, seed = 2), meta(2))
        WeightsStore.setActive(root, 2)
        assertEquals(2, WeightsStore.active(root))
        assertEquals(listOf(1, 2), WeightsStore.generations(root))
        File(WeightsStore.dirFor(root, 2), WeightsStore.WEIGHTS).delete()
        assertNull("a deleted champion must not be reported as active", WeightsStore.active(root))
    }

    @Test
    fun savingTwiceReplacesWithoutLeavingParts() {
        val root = tmp.newFolder("brain")
        val first = VorvCore(cfg, seed = 1)
        WeightsStore.save(root, first, meta(1))
        val second = VorvCore(cfg, seed = 4)
        WeightsStore.save(root, second, meta(1))
        val dir = WeightsStore.dirFor(root, 1)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        assertEquals(second.lossValue(intArrayOf(2, 3)), WeightsStore.load(root, 1).lossValue(intArrayOf(2, 3)), 0f)
    }

    @Test
    fun aTruncatedGenerationIsRefused() {
        val root = tmp.newFolder("brain")
        WeightsStore.save(root, VorvCore(cfg, seed = 1), meta(1))
        val file = File(WeightsStore.dirFor(root, 1), WeightsStore.WEIGHTS)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOfRange(0, bytes.size / 2))
        val failure = runCatching { WeightsStore.load(root, 1) }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is WeightsStore.BadGeneration)
    }

    @Test
    fun oneFlippedByteIsRefused() {
        val root = tmp.newFolder("brain")
        WeightsStore.save(root, VorvCore(cfg, seed = 1), meta(1))
        val file = File(WeightsStore.dirFor(root, 1), WeightsStore.WEIGHTS)
        val bytes = file.readBytes()
        bytes[40] = (bytes[40].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)
        val failure = runCatching { WeightsStore.load(root, 1) }.exceptionOrNull()
        assertTrue("expected a checksum refusal, got $failure", failure is WeightsStore.BadGeneration)
        assertTrue(failure!!.message!!.contains("checksum"))
    }

    @Test
    fun aMissingGenerationSaysWhichOne() {
        val root = tmp.newFolder("brain")
        val failure = runCatching { WeightsStore.load(root, 7) }.exceptionOrNull()
        assertTrue(failure is WeightsStore.BadGeneration)
        assertTrue(failure!!.message!!.contains("generation 7"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun generationZeroIsNotAThing() {
        WeightsStore.dirFor(tmp.newFolder("brain"), 0)
    }
}
