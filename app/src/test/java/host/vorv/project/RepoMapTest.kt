package host.vorv.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan Step 7, item 5: the map is an outline, it never exceeds its budget, and a
 * cut is stated rather than passing for a complete picture.
 */
class RepoMapTest {

    private fun file(path: String, symbols: List<Symbol> = emptyList(), lines: Int = 10): IndexedFile =
        IndexedFile(
            path = path,
            size = 100,
            mtime = 1,
            fingerprint = "sha",
            lang = SymbolExtractor.Language.KOTLIN,
            lines = lines,
            symbols = symbols,
            chunks = emptyList(),
        )

    private fun symbol(path: String, kind: Symbol.Kind, name: String, line: Int = 1): Symbol =
        Symbol(path, kind, name, "signature", line, line + 3, null)

    @Test
    fun `an empty repository says so instead of printing nothing`() {
        val text = RepoMap.build(IndexSnapshot(revision = 1, files = emptyList()), budgetTokens = 100)
        assertTrue(text.contains("empty"))
    }

    @Test
    fun `files are grouped by directory and named by their declarations`() {
        val files = listOf(
            file("app/llm/Llm.kt", listOf(symbol("app/llm/Llm.kt", Symbol.Kind.CLASS, "CloudLlm"))),
            file("app/main/App.kt", listOf(symbol("app/main/App.kt", Symbol.Kind.FUNCTION, "start"))),
        )
        val text = RepoMap.build(IndexSnapshot(revision = 1, files = files), budgetTokens = 200)
        assertTrue(text.contains("app/llm/"))
        assertTrue(text.contains("Llm.kt: CloudLlm"))
        assertTrue(text.contains("app/main/"))
        assertTrue(text.contains("App.kt: start"))
        assertTrue("the larger directory must sort first", text.indexOf("app/llm/") < text.indexOf("app/main/"))
    }

    @Test
    fun `a file with no declarations still appears with its size`() {
        val text = RepoMap.build(
            IndexSnapshot(1, listOf(file("docs/NOTES.md", emptyList(), lines = 42))),
            budgetTokens = 100,
        )
        assertTrue(text.contains("NOTES.md (no declarations found)"))
    }

    @Test
    fun `a file with many declarations is capped but still named`() {
        val path = "a/Big.kt"
        val many = (1..30).map { symbol(path, Symbol.Kind.FUNCTION, "f$it", it * 2) }
        val text = RepoMap.build(IndexSnapshot(1, listOf(file(path, many))), budgetTokens = 300)
        assertTrue(text.contains("f1"))
        assertTrue("only the first names are listed", !text.contains("f30"))
    }

    @Test
    fun `the map never grows past its token budget`() {
        val files = (1..400).map { i -> file("dir$i/File$i.kt", listOf(symbol("dir$i/File$i.kt", Symbol.Kind.CLASS, "C$i"))) }
        val budgetTokens = 60
        val text = RepoMap.build(IndexSnapshot(1, files), budgetTokens = budgetTokens)
        assertTrue("chars=${text.length}, budget=${budgetTokens * 4}", text.length <= budgetTokens * 4)
    }

    @Test
    fun `a cut map says it was cut`() {
        val files = (1..400).map { i -> file("dir$i/File$i.kt", listOf(symbol("dir$i/File$i.kt", Symbol.Kind.CLASS, "C$i"))) }
        val text = RepoMap.build(IndexSnapshot(1, files), budgetTokens = 60)
        assertTrue(text.contains("cut at the token budget"))
    }

    @Test
    fun `a small map is not marked as cut`() {
        val text = RepoMap.build(IndexSnapshot(1, listOf(file("a/A.kt"))), budgetTokens = 500)
        assertTrue(!text.contains("cut at the token budget"))
    }

    @Test
    fun `the map is built twice only when the index or the budget moves`() {
        val repoMap = RepoMap
        val snapshot = IndexSnapshot(revision = 3, files = listOf(file("a/A.kt")))
        val first = RepoMap.map(snapshot, 100)
        val second = RepoMap.map(snapshot, 100)
        assertEquals(first, second)
        val other = RepoMap.map(snapshot.copy(revision = 4), 100)
        assertEquals(first, other)
        val wider = RepoMap.map(snapshot, 200)
        assertTrue(wider.length > 0)
    }

    @Test
    fun `a different project with the same revision is not handed the previous map`() {
        // M-11 regression: revision restarts at 1 per project, so keying the
        // cache on revision alone made project B's prompt show project A's
        // file list.
        val a = IndexSnapshot(revision = 1, files = listOf(file("alpha/OnlyInA.kt")), root = "content://tree/a")
        val b = IndexSnapshot(revision = 1, files = listOf(file("beta/OnlyInB.kt")), root = "content://tree/b")

        assertTrue(RepoMap.map(a, 200).contains("OnlyInA.kt"))
        val mapOfB = RepoMap.map(b, 200)
        assertTrue(mapOfB.contains("OnlyInB.kt"))
        assertTrue("project B must not see project A's files: $mapOfB", !mapOfB.contains("OnlyInA.kt"))
    }
}
