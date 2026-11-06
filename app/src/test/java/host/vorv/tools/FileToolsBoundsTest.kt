package host.vorv.tools

import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M9 / M10 (code review, 2026-11): file tools honesty and bounds.
 *
 *  - M9: file_write must report the file's REAL name — SAF providers may
 *    rename on create (extension added, collision uniquified), and a
 *    reported-but-nonexistent path is a lie the model trips over on the
 *    next file_read.
 *  - M10: file_list is bounded in depth and entries, with an honest
 *    truncation note instead of a silent partial listing.
 *
 * Plain JVM: DocumentFile.fromFile wraps java.io.File with no framework.
 */
class FileToolsBoundsTest {

    // ------------------------------------------------------------- M9

    @Test
    fun `no rename - requested path reported as-is`() {
        val (path, note) = resolveWrittenPath("src/index.ts", "index.ts")
        assertEquals("src/index.ts", path)
        assertEquals("", note)
    }

    @Test
    fun `extension added by provider - real name and explicit note`() {
        val (path, note) = resolveWrittenPath("notes", "notes.txt")
        assertEquals("notes.txt", path)
        assertTrue(
            "the rename must be called out, was: $note",
            note.contains("renamed 'notes' to 'notes.txt'"),
        )
    }

    @Test
    fun `collision uniquified by provider - real name in a subdirectory`() {
        val (path, note) = resolveWrittenPath("data/report.csv", "report (1).csv")
        assertEquals("data/report (1).csv", path)
        assertTrue(note.contains("renamed 'report.csv' to 'report (1).csv'"))
    }

    @Test
    fun `root-level file without rename`() {
        val (path, note) = resolveWrittenPath("README.md", "README.md")
        assertEquals("README.md", path)
        assertEquals("", note)
    }

    // ------------------------------------------------------------ M10

    private fun tempTree(): DocumentFile {
        val f = File.createTempFile("vorv-tree", "").let { it.delete(); it.mkdirs(); it }
        return DocumentFile.fromFile(f)
    }

    @Test
    fun `listing a small tree is complete and untruncated`() {
        val root = tempTree()
        File(root.uri.path!!, "a.txt").writeText("hi")
        File(root.uri.path!!, "sub").mkdirs()
        File(root.uri.path!!, "sub/b.txt").writeText("yo")

        val out = StringBuilder()
        val budget = ListBudget()
        listBounded(root, "", out, budget)

        assertFalse(budget.truncatedByEntries)
        assertFalse(budget.truncatedByDepth)
        assertTrue(out.toString().contains("[dir] sub/"))
        assertTrue(out.toString().contains("a.txt"))
        assertTrue(out.toString().contains("sub/b.txt"))
    }

    @Test
    fun `depth beyond the cap is truncated with a flag`() {
        val root = tempTree()
        // Build a chain deeper than MAX_LIST_DEPTH.
        var dir = File(root.uri.path!!)
        repeat(MAX_LIST_DEPTH + 5) { i ->
            dir = File(dir, "d$i").apply { mkdirs() }
            File(dir, "f$i.txt").writeText("x")
        }

        val out = StringBuilder()
        val budget = ListBudget()
        listBounded(root, "", out, budget)

        assertTrue("depth cap must fire", budget.truncatedByDepth)
        assertFalse(budget.truncatedByEntries)
        // The listing stopped before the deepest levels.
        assertTrue(out.toString().contains("d0/"))
        assertFalse("beyond the depth cap nothing appears", out.toString().contains("d${MAX_LIST_DEPTH + 4}"))
    }

    @Test
    fun `entry count beyond the cap is truncated`() {
        val root = tempTree()
        repeat(MAX_LIST_ENTRIES + 50) { i ->
            File(root.uri.path!!, "f%04d.txt".format(i)).writeText("x")
        }

        val out = StringBuilder()
        val budget = ListBudget()
        listBounded(root, "", out, budget)

        assertTrue("entry cap must fire", budget.truncatedByEntries)
        assertEquals("exactly the cap was listed", MAX_LIST_ENTRIES, budget.entries)
        val lines = out.toString().lines().filter { it.isNotBlank() }
        assertEquals(MAX_LIST_ENTRIES, lines.size)
    }
}
