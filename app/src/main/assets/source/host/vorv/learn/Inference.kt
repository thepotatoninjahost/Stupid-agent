package host.vorv.learn

import kotlin.math.exp
import kotlin.math.ln

/**
 * Step 8: what the phone does with a trained generation — answer and measure.
 *
 * Perplexity is the plainest honest quality number: exp(mean negative
 * log-likelihood) on text the generation was not trained on. The promotion
 * gate consumes it as validation loss, so the same measurement that chooses a
 * champion is the one printed on the Brain screen.
 */
object Inference {

    data class Speed(val tokensPerSecond: Double, val seconds: Double, val tokens: Int)

    /** exp(mean nats/token) over [ids], windows of the model's context. */
    fun perplexity(core: VorvCore, ids: IntArray, maxWindows: Int = 32): Double {
        require(ids.size >= 2) { "perplexity needs at least two tokens" }
        val ctx = core.config.ctx
        val step = maxOf(1, ctx / 2)
        var totalLoss = 0.0
        var totalTokens = 0
        var windows = 0
        var start = 0
        while (start < ids.size - 1 && windows < maxWindows) {
            val end = minOf(ids.size, start + ctx)
            if (end - start < 2) break
            val window = ids.copyOfRange(start, end)
            val loss = core.lossValue(window).toDouble()
            totalLoss += loss * (window.size - 1)
            totalTokens += window.size - 1
            windows++
            if (end == ids.size) break
            start += step
        }
        require(totalTokens > 0) { "no tokens to measure" }
        return exp(totalLoss / totalTokens)
    }

    /** Greedy answer to [prompt] as text, using the generation's own tokenizer. */
    fun answer(
        core: VorvCore,
        tokenizer: Tokenizer,
        prompt: String,
        maxTokens: Int = 64,
        temperature: Float = 0f,
        topK: Int = 40,
    ): String {
        require(tokenizer.vocabSize <= core.config.vocab) {
            "the tokenizer vocabulary is ${tokenizer.vocabSize} but this generation only knows ${core.config.vocab} tokens"
        }
        val ids = tokenizer.encode(prompt, addEos = false)
        if (ids.isEmpty()) return ""
        val out = core.generate(ids, maxTokens, temperature, topK, stopAt = Tokenizer.EOS)
        return tokenizer.decode(out)
    }

    /** Tokens per second on this device, for the silicon and speed records. */
    fun measureSpeed(core: VorvCore, prompt: IntArray, tokens: Int = 16): Speed {
        val started = System.nanoTime()
        core.generate(prompt, tokens)
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        return Speed(if (seconds > 0) tokens / seconds else Double.MAX_VALUE, seconds, tokens)
    }

    /** Bits per byte, the model-size-independent form of the same loss. */
    fun bitsPerByte(core: VorvCore, ids: IntArray): Double {
        val ppl = perplexity(core, ids)
        return if (ppl <= 0.0) 0.0 else ln(ppl) / ln(2.0)
    }
}
