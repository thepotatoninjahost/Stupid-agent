package host.vorv.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolExtractorTest {

    @Test
    fun `kotlin declarations carry inclusive line ranges`() {
        val src = """
            package host.vorv.demo

            import java.io.File

            class Alpha {
                fun one() {
                    println("}")
                }
            }

            object Beta {
                val name = "beta"
                fun two(): Int = 2
            }
        """.trimIndent()
        val symbols = SymbolExtractor.extract("a/A.kt", src)
        val alpha = symbols.first { it.name == "Alpha" }
        assertEquals(Symbol.Kind.CLASS, alpha.kind)
        assertEquals(5, alpha.startLine)
        assertEquals(9, alpha.endLine)
        assertEquals("host.vorv.demo", alpha.packageName)

        val one = symbols.first { it.name == "one" }
        assertEquals(6, one.startLine)
        // the closing brace of the string must not close the function early
        assertEquals(8, one.endLine)

        val beta = symbols.first { it.name == "Beta" }
        assertEquals(Symbol.Kind.OBJECT, beta.kind)
        assertEquals(11, beta.startLine)
        assertEquals(14, beta.endLine)

        val two = symbols.first { it.name == "two" }
        assertEquals(13, two.startLine)
        assertEquals(13, two.endLine)

        val imp = symbols.first { it.kind == Symbol.Kind.IMPORT }
        assertEquals("java.io.File", imp.name)
    }

    @Test
    fun `python declarations end at the dedent`() {
        val src = """
            import os


            class Engine:
                def build(self):
                    value = 1
                    return value

                def stop(self):
                    return None


            def main():
                return 0
        """.trimIndent()
        val symbols = SymbolExtractor.extract("tools/engine.py", src)
        val engine = symbols.first { it.name == "Engine" }
        assertEquals(4, engine.startLine)
        assertEquals(10, engine.endLine)
        val build = symbols.first { it.name == "build" }
        assertEquals(5, build.startLine)
        assertEquals(7, build.endLine)
        val main = symbols.first { it.name == "main" }
        assertEquals(13, main.startLine)
        assertEquals(14, main.endLine)
    }

    @Test
    fun `js, gradle, json and markdown produce declarations`() {
        val js = SymbolExtractor.extract("web/app.js", "export function render(node) {\n  return node;\n}\n")
        assertEquals(listOf("render"), js.map { it.name })
        assertEquals(3, js.first().endLine)

        val gradle = SymbolExtractor.extract(
            "app/build.gradle.kts",
            "tasks.register(\"smoke\") {\n    doLast { }\n}\n",
        )
        assertEquals(Symbol.Kind.TASK, gradle.first().kind)
        assertEquals("smoke", gradle.first().name)

        val json = SymbolExtractor.extract("conf.json", "{\n  \"model\": \"x\",\n  \"steps\": 3\n}\n")
        assertEquals(listOf("model", "steps"), json.map { it.name })

        val md = SymbolExtractor.extract("README.md", "# Title\n\ntext\n\n## Section\n")
        assertEquals(listOf("Title", "Section"), md.map { it.name })
    }

    @Test
    fun `a comment or string containing a declaration is not a declaration`() {
        val src = """
            // fun ghost() { }
            /* class Phantom { } */
            val label = "fun fake() {"
        """.trimIndent()
        val names = SymbolExtractor.extract("x.kt", src).map { it.name }
        assertFalse(names.contains("ghost"))
        assertFalse(names.contains("Phantom"))
        assertFalse(names.contains("fake"))
    }
}

class ProjectIndexTest {

    private val kotlinUrl = """
        package host.vorv.net

        class BrainHttpClient {
            fun chatCompletionsUrl(baseUrl: String): String {
                return baseUrl.trimEnd('/') + "/chat/completions"
            }
        }
    """.trimIndent()

    private val pythonJob = """
        def build_dataset(rows):
            kept = []
            for row in rows:
                kept.append(row)
            return kept
    """.trimIndent()

    private fun workspace(): MutableMap<String, String> = mutableMapOf(
        ".gitignore" to "*.log\nignored/\n",
        "app/src/main/java/host/vorv/net/BrainHttpClient.kt" to kotlinUrl,
        "tools/train/build.py" to pythonJob,
        "README.md" to "# Vorv\n\nBuild the thing.\n",
        "build/output.txt" to "generated, must not be indexed",
        "ignored/secret.txt" to "ignored by gitignore",
        "debug.log" to "noise",
    )

    @Test
    fun `the walk skips build output, git-ignored paths and binaries`() {
        val files = workspace()
        files["assets/blob.bin"] = "abc\u0000def"
        val source = MapFileSource(files)
        val snap = ProjectIndexer(source).build()
        val paths = snap.files.map { it.path }

        assertTrue(paths.contains("app/src/main/java/host/vorv/net/BrainHttpClient.kt"))
        assertFalse(paths.contains("build/output.txt"))
        assertFalse(paths.contains("ignored/secret.txt"))
        assertFalse(paths.contains("debug.log"))
        assertFalse(paths.contains("assets/blob.bin"))
        assertEquals("binary", snap.skipped["assets/blob.bin"])
        assertEquals("ignored directory: build", snap.skipped["build/output.txt"])
    }

    @Test
    fun `a described change finds the right file in the top three`() {
        val snap = ProjectIndexer(MapFileSource(workspace())).build()
        val hits = Retriever().retrieve(snap, "the chat completions URL is built with a double slash", 3_000, limit = 3)
        assertTrue(hits.isNotEmpty())
        assertEquals("app/src/main/java/host/vorv/net/BrainHttpClient.kt", hits.first().chunk.path)
    }

    @Test
    fun `the repo map stays inside its token budget and names declarations`() {
        val snap = ProjectIndexer(MapFileSource(workspace())).build()
        val map = RepoMap.build(snap, budgetTokens = 120)
        assertTrue(map.length / 4 <= 130)
        assertTrue(map.contains("BrainHttpClient"))
    }

    @Test
    fun `editing one file re-parses exactly one file`() {
        val files = workspace()
        val source = MapFileSource(files)
        val indexer = ProjectIndexer(source)
        val first = indexer.build()
        val readsAfterFirst = source.reads
        assertTrue(readsAfterFirst > 0)

        files["tools/train/build.py"] = pythonJob.replace("kept", "surviving")
        val second = indexer.build(first)
        assertEquals(1, source.reads - readsAfterFirst)
        assertEquals(2, second.revision)
        // the untouched file is the same object, not a re-parse that happens to match
        val before = first.files.first { it.path.endsWith("BrainHttpClient.kt") }
        val after = second.files.first { it.path.endsWith("BrainHttpClient.kt") }
        assertTrue(before === after)
    }

    @Test
    fun `a deleted file disappears from retrieval`() {
        val files = workspace()
        val indexer = ProjectIndexer(MapFileSource(files))
        val first = indexer.build()
        assertTrue(Retriever().retrieve(first, "build dataset rows", 2_000).any { it.chunk.path.contains("build.py") })

        files.remove("tools/train/build.py")
        val second = ProjectIndexer(MapFileSource(files)).build()
        assertFalse(second.files.any { it.path.contains("build.py") })
        assertFalse(Retriever().retrieve(second, "build dataset rows", 2_000).any { it.chunk.path.contains("build.py") })
    }

    @Test
    fun `chunks respect the token budget and overlap`() {
        val body = (1..400).joinToString("\n") { "val value$it = $it" }
        val text = "class Big {\n$body\n}\n"
        val symbols = SymbolExtractor.extract("Big.kt", text)
        val chunks = Chunker.chunk("Big.kt", text, symbols, HashedEmbedder())
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue("chunk ${it.id} over budget", it.tokens <= Chunker.CHUNK_TOKENS + 10) }
        for (i in 1 until chunks.size) {
            assertTrue(chunks[i].startLine <= chunks[i - 1].endLine + 1)
            assertTrue(chunks[i].startLine > chunks[i - 1].startLine)
        }
    }

    @Test
    fun `the embedder is deterministic and ranks related text higher`() {
        val e = HashedEmbedder()
        assertTrue(e.embed("chat completions url").contentEquals(e.embed("chat completions url")))
        val a = cosine(e.embed("build the chat completions url"), e.embed("the chat completions url has a slash"))
        val b = cosine(e.embed("build the chat completions url"), e.embed("banana bread recipe"))
        assertTrue(a > b)
    }

    @Test
    fun `project commands come from the tree`() {
        val gradle = ProjectRecord.discover("/w", listOf("gradlew", "app/build.gradle.kts"))
        assertEquals("./gradlew test", gradle.testCommand)
        val npm = ProjectRecord.discover("/w", listOf("package.json"))
        assertEquals("npm test", npm.testCommand)
        val py = ProjectRecord.discover("/w", listOf("pyproject.toml"))
        assertEquals("python3 -m pytest", py.testCommand)
        val none = ProjectRecord.discover("/w", listOf("notes.txt"))
        assertNotNull(none)
        assertEquals(null, none.testCommand)
    }
}
