package host.vorv.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Plan Step 7, item 6: the record is discovered from file names, saved without
 * losing a value, and says "not found" rather than inventing a command.
 */
class ProjectRecordTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a gradle project gets the wrapper commands`() {
        val record = ProjectRecord.discover(
            "/w/repo",
            listOf("build.gradle.kts", "gradlew", "app/build.gradle.kts", "app/src/Main.kt"),
        )
        assertEquals("./gradlew assembleDebug", record.buildCommand)
        assertEquals("./gradlew test", record.testCommand)
        assertEquals("./gradlew lint", record.lintCommand)
        assertTrue(record.conventions.contains("Kotlin/Java project built with Gradle"))
    }

    @Test
    fun `a gradle project in a subfolder is built from that subfolder`() {
        val record = ProjectRecord.discover("/w/repo", listOf("android/build.gradle.kts", "android/gradlew"))
        assertEquals("./android/gradlew assembleDebug", record.buildCommand)
        assertEquals("android", record.name)
    }

    @Test
    fun `a javascript project gets the npm commands`() {
        val record = ProjectRecord.discover("/w/site", listOf("package.json", "src/index.js"))
        assertEquals("npm run build", record.buildCommand)
        assertEquals("npm test", record.testCommand)
        assertEquals("site", record.name)
    }

    @Test
    fun `a python project gets the build and pytest commands`() {
        val record = ProjectRecord.discover("/w/tool", listOf("pyproject.toml", "tool/main.py"))
        assertEquals("python3 -m build", record.buildCommand)
        assertEquals("python3 -m pytest", record.testCommand)
    }

    @Test
    fun `a project with no known marker reports no commands instead of guessing`() {
        val record = ProjectRecord.discover("/w/notes", listOf("README.md", "notes.txt"))
        assertNull(record.buildCommand)
        assertNull(record.testCommand)
        assertNull(record.lintCommand)
        assertTrue(record.summary().contains("build: not found"))
        assertTrue(record.summary().contains("test: not found"))
    }

    @Test
    fun `an AGENTS file is listed as a convention`() {
        val record = ProjectRecord.discover("/w/repo", listOf("AGENTS.md", "build.gradle.kts", "gradlew"))
        assertTrue(record.conventions.any { it.contains("AGENTS.md") })
    }

    @Test
    fun `a saved record loads back unchanged`() {
        val record = ProjectRecord(
            root = "/w/repo",
            name = "repo",
            buildCommand = "gradlew assembleDebug",
            testCommand = "gradlew test",
            lintCommand = null,
            conventions = listOf("Kotlin sources"),
            lastPlan = "read the index, then answer",
        )
        val directory = temp.newFolder()
        ProjectRecord.save(record, directory)
        assertEquals(record, ProjectRecord.load(directory))
    }

    @Test
    fun `a value with a tab cannot break the file`() {
        val record = ProjectRecord("/w", "w", "a\tb", null, null, listOf("one\ttwo"), null)
        val directory = temp.newFolder()
        ProjectRecord.save(record, directory)
        val loaded = ProjectRecord.load(directory)!!
        assertEquals("a b", loaded.buildCommand)
        assertEquals(listOf("one two"), loaded.conventions)
    }

    @Test
    fun `nothing is loaded when the record was never written`() {
        assertNull(ProjectRecord.load(temp.newFolder()))
    }
}
