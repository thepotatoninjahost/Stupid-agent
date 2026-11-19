package host.vorv.learn

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The CI half of the speed rule: the speeds measured on the owner's phone are
 * kept in `docs/BENCHMARKS.tsv`, and a build more than 20% slower than the one
 * before it must fail this build rather than be written down as fine.
 *
 * The comparison itself is [SpeedRecord.verdict], the same code the phone uses,
 * so CI and the phone cannot disagree about what "a regression" means.
 */
class SpeedRecordFileTest {

    private fun recordsFile(): File? =
        listOf(File("docs/BENCHMARKS.tsv"), File("../docs/BENCHMARKS.tsv"))
            .firstOrNull { it.isFile }

    private fun parse(file: File): List<SpeedRecord.Row> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("generation") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            val generation = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val speed = parts.getOrNull(2)?.toDoubleOrNull() ?: return@mapNotNull null
            val at = parts.getOrNull(3)?.toLongOrNull() ?: return@mapNotNull null
            SpeedRecord.Row(generation, parts.getOrNull(1) ?: "", speed, at)
        }

    @Test
    fun `no recorded build is more than 20 percent slower than the build before it`() {
        val file = recordsFile()
        assumeTrue(
            "docs/BENCHMARKS.tsv is not in this checkout, so no phone speed has been recorded here.",
            file != null,
        )
        val rows = parse(file!!)
        val judged = ArrayList<String>()
        val regressions = ArrayList<String>()
        rows.map { it.config }.distinct().forEach { config ->
            val previousRow = rows.filter { it.config == config }.sortedBy { it.at }
            previousRow.zipWithNext().forEach { (previous, current) ->
                val verdict = SpeedRecord.verdict(previous, current)
                judged += verdict.sentence
                if (!verdict.accepted) {
                    regressions += "generation ${current.generation}: ${verdict.sentence}"
                }
            }
        }
        assertTrue(
            "A recorded build fell more than 20% below the build before it, so this build must not ship:\n" +
                regressions.joinToString("\n"),
            regressions.isEmpty(),
        )
        println(
            if (judged.isEmpty()) {
                "docs/BENCHMARKS.tsv has no rows yet: no speed has been measured on a phone, so nothing is claimed."
            } else {
                judged.joinToString("\n")
            },
        )
    }
}
