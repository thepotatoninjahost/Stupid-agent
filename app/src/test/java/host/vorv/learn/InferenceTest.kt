package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceTest {

    private val cfg = VorvCore.CoreConfig(vocab = 16, d = 16, layers = 1, heads = 2, kvHeads = 1, ff = 32, ctx = 8, name = "TINY")
    private val ids = intArrayOf(3, 9, 1, 4, 3, 9, 1, 4)

    @Test
    fun perplexityFallsWhenTheModelLearnsTheText() {
        val core = VorvCore(cfg, seed = 7)
        val before = Inference.perplexity(core, ids)
        assertTrue("perplexity must be finite and above 1: $before", before.isFinite() && before > 1.0)
        val opt = AdamW(core.params, lr = 0.01f)
        repeat(60) { core.trainStep(ids, opt) }
        val after = Inference.perplexity(core, ids)
        assertTrue("perplexity did not fall: $before -> $after", after < before)
        assertTrue("a learned sequence should be near-certain: $after", after < 2.0)
    }

    @Test
    fun perplexityRefusesASingleToken() {
        val failure = runCatching { Inference.perplexity(VorvCore(cfg, 1), intArrayOf(4)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun bitsPerByteAgreesWithPerplexity() {
        val core = VorvCore(cfg, seed = 2)
        val ppl = Inference.perplexity(core, ids)
        val bpb = Inference.bitsPerByte(core, ids)
        assertEquals(kotlin.math.ln(ppl) / kotlin.math.ln(2.0), bpb, 1e-9)
    }

    @Test
    fun answerDecodesWithTheGenerationsOwnTokenizer() {
        val tok = Tokenizer.train("the quick brown fox jumps over the lazy dog\n".repeat(20), 300)
        val core = VorvCore(cfg.copy(vocab = tok.vocabSize), seed = 4)
        val answer = Inference.answer(core, tok, "the quick", maxTokens = 8)
        assertTrue(answer.length >= 0)
        // whatever it says must be decodable text, never an exception or a raw id dump
        assertTrue(answer.none { it.code in 0xE000..0xF8FF })
    }

    @Test
    fun aTokenizerWiderThanTheModelIsRefusedNotCrashed() {
        val core = VorvCore(cfg, seed = 4)
        val tok = Tokenizer.train("hello world\n".repeat(20), 300)
        val failure = runCatching { Inference.answer(core, tok, "hello", maxTokens = 4) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("vocabulary"))
    }

    @Test
    fun speedIsMeasuredAndPositive() {
        val core = VorvCore(cfg, seed = 6)
        val speed = Inference.measureSpeed(core, intArrayOf(1, 2, 3), tokens = 4)
        assertEquals(4, speed.tokens)
        assertTrue(speed.tokensPerSecond > 0)
        assertTrue(speed.seconds >= 0)
    }
}
