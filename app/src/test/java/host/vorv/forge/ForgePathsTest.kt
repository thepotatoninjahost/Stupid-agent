package host.vorv.forge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * ensureLayout is the first thing self-repair does with the filesystem. The
 * old version reported every missing directory as unmakeable instead of
 * creating it, which is how "cannot prepare a work directory" became the
 * answer to every self-repair attempt on a healthy install.
 */
class ForgePathsTest {

    @Test
    fun `missing directories are created, not reported as failures`() {
        val root = createTempDirectory("forges").toFile()
        val targets = listOf("forge", "forge/toolchain", "forge/toolchain/lib", "forge/work")
            .map { File(root, it) }

        val problems = ForgePaths.ensureDirs(targets)

        assertEquals("nothing should fail: $problems", emptyList<String>(), problems)
        assertTrue("every directory must exist", targets.all { it.isDirectory })
    }

    @Test
    fun `a second call is a no-op`() {
        val root = createTempDirectory("forges2").toFile()
        val targets = listOf(File(root, "a"), File(root, "a/b"))
        assertEquals(emptyList<String>(), ForgePaths.ensureDirs(targets))
        assertEquals(emptyList<String>(), ForgePaths.ensureDirs(targets))
    }

    @Test
    fun `a directory that cannot be made is named, not swallowed`() {
        val root = createTempDirectory("forges3").toFile()
        val blocker = File(root, "blocker").apply { writeText("not a directory") }
        val impossible = File(blocker, "child")

        val problems = ForgePaths.ensureDirs(listOf(impossible))

        assertEquals(1, problems.size)
        assertTrue("must name the path: $problems", problems.first().contains("child"))
    }
}
