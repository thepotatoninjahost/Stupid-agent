package host.vorv.learn

import java.io.File
import java.security.MessageDigest
import kotlin.math.ln
import kotlin.random.Random

/**
 * Step 9: the on-phone trainer.
 *
 * It reads the learning ledger, tokenises it with the owner's own merges,
 * trains a [VorvCore] with AdamW, measures the held-out slice, runs the frozen
 * [EvalSuite] against the candidate, asks [PromotionGate] whether it may serve,
 * and only then records it in [GenerationStore]. A candidate that loses is
 * still written and still described in the Brain screen: a refusal is a
 * result, never a silence.
 *
 * Nothing here is Android-specific, so the whole loop is exercised by unit
 * tests on tiny models before a phone ever runs it.
 */
class Trainer(private val brainDir: File) {

    data class Settings(
        val budgetBytes: Long = 900L * 1024 * 1024,
        val maxSteps: Int = 240,
        val lr: Float = 3e-3f,
        val batchSequences: Int = 2,
        val evalEvery: Int = 40,
        val earlyStopPatience: Int = 3,
        val seed: Long = 1234,
        val tokenizerTarget: Int = 1024,
        /** Tests pin a tiny architecture; the phone passes null and gets the measured rung. */
        val rungOverride: VorvCore.CoreConfig? = null,
        /** Refuse to keep training a step that would take longer than this. */
        val maxSecondsPerStep: Double = 6.0,
    )

    data class Outcome(
        val ran: Boolean,
        val message: String,
        val generation: Int,
        val promoted: Boolean,
        val trainLoss: Double,
        val valLoss: Double,
        val steps: Int,
        val tokens: Int,
        val seconds: Double,
        val rung: VorvCore.Rung?,
        val config: VorvCore.CoreConfig?,
        val decision: PromotionGate.Decision?,
        val eval: EvalSuite.Vector?,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun run(
        rows: List<LearningLedger.Row>,
        settings: Settings = Settings(),
        onProgress: (String) -> Unit = {},
    ): Outcome {
        fun stopped(why: String) = Outcome(
            false, why, 0, false, Double.NaN, Double.NaN, 0, 0, 0.0, null, null, null, null,
        )

        if (rows.isEmpty()) {
            return stopped("The ledger is empty, so there is nothing to learn from yet. Talk to Vorv first, then train.")
        }
        val corpus = rows.joinToString("\n\n") { row ->
            buildString {
                if (row.inputText.isNotBlank()) append(row.inputText.trim()).append('\n')
                if (row.outputText.isNotBlank()) append(row.outputText.trim())
            }
        }.trim()
        if (corpus.length < 32) {
            return stopped("The ledger holds only ${corpus.length} characters: too little to train on without inventing a result.")
        }
        val startedAt = System.nanoTime()
        val rung = settings.rungOverride?.let { VorvCore.Rung(it, VorvCore.trainingBytes(it), "pinned by the caller") }
            ?: VorvCore.chooseTrainingRung(settings.budgetBytes)
        if (rung.reason.contains("cannot train here")) return stopped(rung.reason)

        val tokenizer = loadOrTrainTokenizer(corpus, settings.tokenizerTarget, rung.config.vocab)
        val config = rung.config.copy(vocab = tokenizer.vocabSize)
        onProgress("tokenizer ${tokenizer.vocabSize} tokens; ${config.name} ${VorvCore.paramCount(config)} parameters")

        val examples = examplesFrom(rows, tokenizer, config.ctx)
        if (examples.train.size < 2 || examples.heldOut.isEmpty()) {
            return stopped(
                "Only ${examples.train.size} training windows and ${examples.heldOut.size} held-out windows fit; " +
                    "that is too little to measure a change honestly.",
            )
        }

        val core = VorvCore(config, settings.seed)
        val optimiser = AdamW(core.params, lr = settings.lr)
        var bestVal = Double.MAX_VALUE
        var bestStep = 0
        var staleEvals = 0
        var lastTrain = Double.NaN
        var steps = 0
        var tokens = 0
        val rng = Random(settings.seed)
        var stepStarted = System.nanoTime()
        for (step in 0 until settings.maxSteps) {
            val batch = List(settings.batchSequences) { examples.train[rng.nextInt(examples.train.size)] }
            var total = 0.0f
            for (seq in batch) total += core.loss(seq).data[0]
            val averaged = Tensor.mul(sumLoss(core, batch), 1f / batch.size)
            averaged.backward()
            optimiser.step()
            lastTrain = averaged.data[0].toDouble()
            steps++
            tokens += batch.sumOf { it.size }
            val perStep = (System.nanoTime() - stepStarted) / 1_000_000_000.0
            if (steps > 2 && perStep > settings.maxSecondsPerStep) {
                return stopped(
                    "${config.name} needs ${"%.1f".format(perStep)} s per step on this phone, over the " +
                        "${settings.maxSecondsPerStep} s allowed: stopped at step $steps without saving a partial generation.",
                )
            }
            stepStarted = System.nanoTime()
            if (step % settings.evalEvery == settings.evalEvery - 1 || step == settings.maxSteps - 1) {
                val valLoss = averageLoss(core, examples.heldOut)
                onProgress("step $steps: train ${"%.4f".format(lastTrain)} val ${"%.4f".format(valLoss)}")
                if (valLoss < bestVal) {
                    bestVal = valLoss
                    bestStep = steps
                    staleEvals = 0
                } else {
                    staleEvals++
                }
                if (staleEvals >= settings.earlyStopPatience) break
            }
        }
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        if (!bestVal.isFinite()) {
            return stopped("No held-out measurement was produced, so the run cannot be judged and was not saved.")
        }

        val store = GenerationStore(brainDir)
        val previous = store.champion()
        val previousReport = previous?.let { evalRecord(it.id) }

        val candidateReport = EvalSuite.evaluate(
            complete = { prompt -> Inference.answer(core, tokenizer, prompt, maxTokens = 32) },
            valLoss = bestVal,
        )
        val decision = PromotionGate.decide(candidateReport.toGateReport(0, bestVal), previousReport)

        val id = (store.generations().maxOfOrNull { it.id } ?: 0) + 1
        val meta = WeightsStore.Meta(
            generation = id,
            created = System.currentTimeMillis(),
            trainLoss = lastTrain,
            valLoss = bestVal,
            corpusRows = rows.size,
            steps = steps,
            seed = settings.seed,
            note = "${config.name} rung; ${rung.reason}",
        )
        val saved = WeightsStore.save(brainDir, core, meta)
        writeEval(brainDir, id, candidateReport)
        val recorded = store.add(
            GenerationStore.Generation(
                id = id,
                createdAt = meta.created,
                origin = "phone",
                artifactPath = saved.dir.name + "/" + WeightsStore.WEIGHTS,
                artifactSha256 = saved.sha256,
                sizeBytes = saved.bytes,
                valLoss = bestVal,
                accepted = decision.isPromote,
                decision = describe(decision),
                corpusSha256 = sha256(corpus.toByteArray()),
                config = "${config.name} d=${config.d} l=${config.layers} ctx=${config.ctx}",
            ),
            accept = decision.isPromote,
        )
        if (recorded is GenerationStore.Result.Refused) {
            return stopped("Generation $id was trained but refused by the store: ${recorded.message}")
        }
        if (decision.isPromote) WeightsStore.setActive(brainDir, id)

        val message = buildString {
            append("Generation $id trained on ${rows.size} ledger rows for $steps steps in ${"%.1f".format(seconds)} s. ")
            append("Validation loss ${"%.4f".format(bestVal)} (best at step $bestStep). ")
            append(describe(decision))
        }
        return Outcome(
            ran = true, message = message, generation = id, promoted = decision.isPromote,
            trainLoss = lastTrain, valLoss = bestVal, steps = steps, tokens = tokens, seconds = seconds,
            rung = rung, config = config, decision = decision, eval = candidateReport,
        )
    }

    private fun sumLoss(core: VorvCore, batch: List<IntArray>): Tensor {
        var acc: Tensor? = null
        for (seq in batch) {
            val l = core.loss(seq)
            acc = if (acc == null) l else Tensor.add(acc, l)
        }
        return acc ?: throw IllegalArgumentException("empty batch")
    }

    private fun averageLoss(core: VorvCore, windows: List<IntArray>): Double {
        var total = 0.0
        var n = 0
        for (seq in windows.take(8)) {
            total += core.lossValue(seq)
            n++
        }
        return if (n == 0) Double.NaN else total / n
    }

    private data class Examples(val train: List<IntArray>, val heldOut: List<IntArray>)

    private fun examplesFrom(rows: List<LearningLedger.Row>, tokenizer: Tokenizer, ctx: Int): Examples {
        val train = ArrayList<IntArray>()
        val held = ArrayList<IntArray>()
        rows.forEachIndexed { index, row ->
            val text = buildString {
                if (row.inputText.isNotBlank()) append(row.inputText.trim()).append('\n')
                if (row.outputText.isNotBlank()) append(row.outputText.trim())
            }
            if (text.isBlank()) return@forEachIndexed
            val ids = tokenizer.encode(text)
            if (ids.size < 3) return@forEachIndexed
            var start = 0
            while (start < ids.size - 1) {
                val end = minOf(ids.size, start + ctx)
                if (end - start >= 3) {
                    val window = ids.copyOfRange(start, end)
                    // Every seventh row is held out, and its windows never train.
                    if (index % 7 == 6) held.add(window) else train.add(window)
                }
                if (end == ids.size) break
                start += maxOf(1, (end - start) / 2)
            }
        }
        return Examples(train, held)
    }

    private fun loadOrTrainTokenizer(corpus: String, target: Int, modelVocab: Int): Tokenizer {
        val file = File(brainDir, "tokenizer.txt")
        val existing = if (file.exists()) {
            host.vorv.tools.Attempt.of("loading the tokenizer at ${file.path}") { Tokenizer.load(file) }
        } else {
            null
        }
        if (existing != null && existing.vocabSize in 257..modelVocab) return existing
        val tok = Tokenizer.train(corpus, minOf(target, modelVocab), name = "owner-bpe")
        if (!brainDir.isDirectory && !brainDir.mkdirs()) throw java.io.IOException("cannot create ${brainDir.path}")
        tok.save(file)
        return tok
    }

    private fun describe(decision: PromotionGate.Decision): String = when (decision) {
        is PromotionGate.Decision.Promote -> "Promoted: " + decision.improvements.joinToString("; ")
        is PromotionGate.Decision.Reject -> "Not promoted: " + decision.reasons.joinToString("; ") {
            "${it.axis.label} champion ${it.champion} vs candidate ${it.candidate}"
        }
    }

    private fun writeEval(dir: File, generation: Int, vector: EvalSuite.Vector) {
        val kindTotals = EvalSuite.ALL.groupBy { it.kind }.mapValues { it.value.size }
        val kindPassed = vector.results.groupBy { it.kind }.mapValues { entry -> entry.value.count { it.passed } }
        val body = buildString {
            append("{\n  \"suiteVersion\": ${vector.suiteVersion},\n")
            append("  \"languageScore\": ${vector.languageScore},\n")
            append("  \"projectScore\": ${vector.projectScore},\n")
            append("  \"stillKnowsScore\": ${vector.stillKnowsScore},\n")
            append("  \"refusalScore\": ${vector.refusalScore},\n")
            append("  \"valLoss\": ${vector.valLoss},\n")
            append("  \"outputHash\": \"${vector.outputHash}\",\n")
            append("  \"passed\": {")
            append(kindPassed.entries.joinToString(", ") { "\"${it.key.name}\": ${it.value}" })
            append("},\n  \"total\": {")
            append(kindTotals.entries.joinToString(", ") { "\"${it.key.name}\": ${it.value}" })
            append("}\n}\n")
        }
        val f = File(WeightsStore.dirFor(dir, generation), "eval.json")
        f.writeText(body)
        if (!f.exists()) throw java.io.IOException("eval record for generation $generation was not written")
    }

    /** The stored evaluation for [generation], or null when there is none this build can read. */
    fun evalRecord(generation: Int): PromotionGate.Report? {
        val file = File(WeightsStore.dirFor(brainDir, generation), "eval.json")
        if (!file.isFile) return null
        return try {
            val text = file.readText()
            val suite = Regex("\"suiteVersion\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
            val loss = Regex("\"valLoss\"\\s*:\\s*([0-9.]+)").find(text)?.groupValues?.get(1)?.toDouble()
            val passed = parseCounts(text, "passed")
            val total = parseCounts(text, "total")
            if (suite != EvalSuite.VERSION) {
                android.util.Log.w("Vorv/Train", "eval record for generation $generation is suite ${suite}, expected ${EvalSuite.VERSION}: ignored")
                null
            } else {
                val report = PromotionGate.Report(generation, passed, total, loss)
                if (total.values.sum() == 0) null else report
            }
        } catch (t: Throwable) {
            android.util.Log.w("Vorv/Train", "eval record for generation $generation could not be read: ${t.message}")
            null
        }
    }

    private fun parseCounts(text: String, key: String): Map<EvalSuite.Kind, Int> {
        val block = Regex("\"$key\"\\s*:\\s*\\{([^}]*)}").find(text)?.groupValues?.get(1) ?: return emptyMap()
        return Regex("\"([A-Z_]+)\"\\s*:\\s*(\\d+)").findAll(block).mapNotNull { m ->
            val kind = try {
                EvalSuite.Kind.valueOf(m.groupValues[1])
            } catch (t: IllegalArgumentException) {
                android.util.Log.w("Vorv/Train", "eval record names an unknown kind '${m.groupValues[1]}'")
                return@mapNotNull null
            }
            kind to m.groupValues[2].toInt()
        }.toMap()
    }

    private fun EvalSuite.Vector.toGateReport(id: Int, loss: Double): PromotionGate.Report {
        val passed = results.groupBy { it.kind }.mapValues { e -> e.value.count { it.passed } }
        val total = results.groupBy { it.kind }.mapValues { e -> e.value.size }
        return PromotionGate.Report(id, passed, total, loss)
    }

    /** Plain-language quality, for the Brain screen. */
    fun describeScores(vector: EvalSuite.Vector): String {
        val ppl = vector.valLoss?.let { Math.exp(it) } ?: Double.NaN
        return "language ${(vector.languageScore * 100).toInt()}%, project ${(vector.projectScore * 100).toInt()}%, " +
            "still-knows ${(vector.stillKnowsScore * 100).toInt()}%, refusals ${(vector.refusalScore * 100).toInt()}%, " +
            "perplexity ${"%.1f".format(ppl)}"
    }

    companion object {
        /** nats/token → the human-facing perplexity printed next to a generation. */
        fun perplexityOf(valLoss: Double): Double = if (valLoss.isFinite()) Math.exp(valLoss) else Double.NaN

        fun bytesToMb(bytes: Long): String = "%.0f MB".format(bytes / (1024.0 * 1024.0))

        fun bitsPerByte(valLoss: Double): Double = ln(perplexityOf(valLoss)) / ln(2.0)
    }
}
