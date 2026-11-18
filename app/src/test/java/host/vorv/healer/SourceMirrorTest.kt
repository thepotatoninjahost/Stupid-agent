package host.vorv.healer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app ships a copy of its own Kotlin sources in `assets/source` so the
 * repair agent can read, and refuse to patch, code it does not know.
 *
 * That copy has drifted before: 16 files were months out of date, so a repair
 * decision was being made against code that no longer existed. This test makes
 * drift a build failure instead of a quiet lie.
 */
class SourceMirrorTest {

    private val module = File(".").absoluteFile
    private val sources = File(module, "src/main/java/host/vorv")
    private val mirror = File(module, "src/main/assets/source/host/vorv")

    @Test
    fun `every source file has a mirror`() {
        assertTrue("source tree not found at $sources", sources.isDirectory)
        val missing = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(sources).path }
            .filter { !File(mirror, it).isFile }
            .toList()
        assertEquals("missing from assets/source: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `every mirror is byte-identical to its source`() {
        val drifted = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(sources) }
            .filter { rel -> File(mirror, rel.path).isFile && File(mirror, rel.path).readText() != it.readText() }
            .map { it.path }
            .toList()
        assertEquals("mirror differs from source (copy the file into assets/source): $drifted", emptyList<String>(), drifted)
    }

    @Test
    fun `the mirror holds nothing the source no longer has`() {
        val orphans = mirror.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(mirror).path }
            .filter { !File(sources, it).isFile }
            .toList()
        assertEquals("assets/source holds deleted files: $orphans", emptyList<String>(), orphans)
    }
}
