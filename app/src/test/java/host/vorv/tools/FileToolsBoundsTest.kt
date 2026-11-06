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
 * Plain JVM: DocumentFile.fromFile wraps java.io.File (RawDocumentFile —
 * listFiles/isDirectory/name/length are pure File ops). The tests touch the
 * underlying File directly; DocumentFile.uri is NEVER read because that
 * would need the Android framework.
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

    private fun tempDir(): File = File.createTempFile("vorv-tree", "").let { f ->
        f.delete(); f.mkdirs(); f
    }

    @Test
    fun `listing a small tree is complete and untruncated`() {
        val dir = tempDir()
        File(dir, "a.txt").writeText("hi")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.txt").writeText("yo")

        val out = StringBuilder()
        val budget = ListBudget()
        listBounded(DocumentFile.fromFile(dir), "", out, budget)

        assertFalse(budget.truncatedByEntries)
        assertFalse(budget.truncatedByDepth)
        assertTrue(out.toString().contains("[dir] sub/"))
        assertTrue(out.toString().contains("a.txt"))
        assertTrue(out.toString().contains("sub/b.txt"))
    }

    @Test
    fun `depth beyond the cap is truncated with a flag`() {
        val dir = tempDir()
        // Build a chain deeper than MAX_LIST_DEPTH.
        var d = dir
        repeat(MAX_LIST_DEPTH + 5) { i ->
            d = File(d, "d$i").apply { mkdirs() }
            File(d, "f$i.txt").writeText("x")
        }

        val out = StringBuilder()
        val budget = ListBudget()
        listBounded(DocumentFile.fromFile(dir), "", out, budget)

        assertTrue("depth cap must fire", budget.truncatedByDepth)
        assertFalse(budget.truncatedByEntries)
        // The listing stopped before the deepest levels.
        assertTrue(out.toString().contains("d0/"))
        assertFalse("beyond the depth cap nothing appears", out.toString().contains("d${MAX_LIST_DEPTH + 4}"))
    }

    @Test
    fun `entry count beyond the cap is truncated`() {
        val dir = tempDir()
        repeat(MAX_LIST_ENTRIES + 50) { i ->
            File(dir, "f%04d.txt".format(i)).writeText("x")
        }

        val out = StringBuilder()
        val budget = ListBudget()
        listBounded(DocumentFile.fromFile(dir), "", out, budget)

        assertTrue("entry cap must fire", budget.truncatedByEntries)
        assertEquals("exactly the cap was listed", MAX_LIST_ENTRIES, budget.entries)
        val lines = out.toString().lines().filter { it.isNotBlank() }
        assertEquals(MAX_LIST_ENTRIES, lines.size)
    }
}
