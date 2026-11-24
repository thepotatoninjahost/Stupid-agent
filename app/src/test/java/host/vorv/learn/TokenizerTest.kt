package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TokenizerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val corpus = buildString {
        repeat(30) {
            append("fun main() { println(\"hello world\") }\n")
            append("val answer = 42 // the answer\n")
        }
    }

    @Test
    fun everyStringRoundTripsExactly() {
        val tok = Tokenizer.train(corpus, 400)
        for (text in listOf("hello world", "fun main() { }", "emoji 🙂 and 中文", "", "line\nbreak\ttab", "1234567890")) {
            val ids = tok.encode(text, addEos = false)
            assertEquals(text, tok.decode(ids))
        }
    }

    @Test
    fun mergesShrinkTheText() {
        val tok = Tokenizer.train(corpus, 600)
        val ids = tok.encode("fun main() { println(\"hello world\") }", addEos = false)
        val rawBytes = "fun main() { println(\"hello world\") }".toByteArray().size
        assertTrue("expected fewer than $rawBytes tokens, got ${ids.size}", ids.size < rawBytes)
        assertTrue(tok.vocabSize <= 600)
    }

    @Test
    fun savingAndLoadingKeepsTheSameEncoding() {
        val tok = Tokenizer.train(corpus, 500, name = "owner-bpe")
        val file = File(tmp.newFolder("tok"), "tokenizer.txt")
        tok.save(file)
        val loaded = Tokenizer.load(file)
        assertEquals(tok.vocabSize, loaded.vocabSize)
        val sample = "val answer = 42 // the answer"
        assertEquals(tok.encode(sample).toList(), loaded.encode(sample).toList())
        assertTrue(file.readText().startsWith("vorv-bpe 1"))
    }

    @Test
    fun eosIsAppendedOnlyWhenAsked() {
        val tok = Tokenizer.train(corpus, 300)
        assertTrue(tok.encode("hello").last() == Tokenizer.EOS)
        assertTrue(tok.encode("hello", addEos = false).none { it == Tokenizer.EOS })
    }

    @Test
    fun idsOutsideTheVocabularyAreRefused() {
        val tok = Tokenizer.train(corpus, 300)
        val failure = runCatching { tok.decode(intArrayOf(900)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("900"))
    }

    @Test
    fun aFileThatIsNotATokenizerIsRefused() {
        val file = File(tmp.newFolder("tok2"), "tokenizer.txt")
        file.writeText("some other format\n")
        val failure = runCatching { Tokenizer.load(file) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun trainingIsDeterministic() {
        val a = Tokenizer.train(corpus, 350)
        val b = Tokenizer.train(corpus, 350)
        assertEquals(a.encode("hello world").toList(), b.encode("hello world").toList())
        assertEquals(a.vocabSize, b.vocabSize)
    }

    @Test
    fun textOfOnlyUnseenBytesStillEncodes() {
        val tok = Tokenizer.train(corpus, 280)
        val ids = tok.encode("ẞЖ", addEos = false)
        assertEquals("ẞЖ", tok.decode(ids))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anImpossibleVocabularySizeIsRefused() {
        Tokenizer.train(corpus, 10)
    }

    @Test
    fun `a stop during training keeps the merges already learned`() {
        val full = Tokenizer.train(corpus, 400)
        var rounds = 0
        val cut = Tokenizer.train(corpus, 400, shouldStop = { rounds++ >= 3 })
        assertTrue("it must stop early: ${cut.vocabSize}", cut.vocabSize < full.vocabSize)
        assertTrue("and keep some learning: ${cut.vocabSize}", cut.vocabSize > 256)
        // A smaller vocabulary is still a valid one: the ids still round-trip.
        val ids = cut.encode("hello world", addEos = false)
        assertEquals("hello world", cut.decode(ids))
    }
}
