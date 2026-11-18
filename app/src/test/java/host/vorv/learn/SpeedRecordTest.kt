package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeedRecordTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file() = File(temp.root, SpeedRecord.FILE)

    @Test
    fun `a fresh record file carries a header and one row`() {
        val file = file()
        val row = SpeedRecord.Row(1, "vorv-s", 12.5, 1_000)
        SpeedRecord.append(file, row)

        val text = file.readText()
        assertTrue(text.startsWith(SpeedRecord.HEADER))
        val rows = SpeedRecord.read(file)
        assertEquals(1, rows.size)
        assertEquals(12.5, rows[0].tokensPerSecond, 0.001)
        assertEquals("vorv-s", rows[0].config)
    }

    @Test
    fun `the first record for an architecture has nothing to compare`() {
        val verdict = SpeedRecord.verdict(null, SpeedRecord.Row(1, "vorv-s", 9.0, 1_000))
        assertTrue(verdict.accepted)
        assertNull(verdict.percentChange)
        assertTrue(verdict.sentence.contains("First record"))
    }

    @Test
    fun `a faster build is accepted and says how much faster`() {
        val previous = SpeedRecord.Row(1, "vorv-s", 10.0, 1_000)
        val verdict = SpeedRecord.verdict(previous, SpeedRecord.Row(2, "vorv-s", 12.0, 2_000))
        assertTrue(verdict.accepted)
        assertTrue(verdict.sentence.contains("up 20%"))
    }

    @Test
    fun `a small drop inside the allowance is accepted but named`() {
        val previous = SpeedRecord.Row(1, "vorv-s", 10.0, 1_000)
        val verdict = SpeedRecord.verdict(previous, SpeedRecord.Row(2, "vorv-s", 8.5, 2_000))
        assertTrue(verdict.accepted)
        assertTrue(verdict.sentence.contains("15% below"))
        assertTrue(verdict.sentence.contains("inside the 20% allowed"))
    }

    @Test
    fun `exactly twenty percent down is still allowed`() {
        val previous = SpeedRecord.Row(1, "vorv-s", 10.0, 1_000)
        val verdict = SpeedRecord.verdict(previous, SpeedRecord.Row(2, "vorv-s", 8.0, 2_000))
        assertTrue(verdict.accepted)
    }

    @Test
    fun `a drop above twenty percent is refused, not recorded as fine`() {
        val previous = SpeedRecord.Row(1, "vorv-s", 10.0, 1_000)
        val verdict = SpeedRecord.verdict(previous, SpeedRecord.Row(2, "vorv-s", 7.9, 2_000))
        assertFalse(verdict.accepted)
        assertTrue(verdict.sentence.startsWith("REFUSED"))
        assertTrue(verdict.sentence.contains("21% below"))
    }

    @Test
    fun `the comparison only looks at earlier builds of the same architecture`() {
        val rows = listOf(
            SpeedRecord.Row(1, "vorv-s", 10.0, 1_000),
            SpeedRecord.Row(2, "vorv-m", 4.0, 2_000),
            SpeedRecord.Row(3, "vorv-s", 11.0, 3_000),
        )
        val previous = SpeedRecord.previousFor(rows, "vorv-s", beforeGeneration = 3)
        assertEquals(1, previous?.generation)
        assertNull(SpeedRecord.previousFor(rows, "vorv-s", beforeGeneration = 1))
    }

    @Test
    fun `an unreadable line is skipped, the readable ones survive`() {
        val file = file()
        file.writeText(
            SpeedRecord.HEADER + "\n" +
                "1\tvorv-s\t12.0\t1000\n" +
                "this line is not a record\n" +
                "2\tvorv-s\tnine\t2000\n",
        )
        val rows = SpeedRecord.read(file)
        assertEquals(1, rows.size)
        assertEquals(12.0, rows[0].tokensPerSecond, 0.001)
    }

    @Test
    fun `a missing file reads as no records, never as an error`() {
        assertTrue(SpeedRecord.read(File(temp.root, "never-written.tsv")).isEmpty())
    }
}
