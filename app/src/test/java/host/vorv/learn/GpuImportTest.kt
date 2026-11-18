package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GPU hand-off is where a phone can be told a lie by a file. These tests
 * fix the sentences: every refusal names what was wrong, and the one accepted
 * case names the axis that improved.
 */
class GpuImportTest {

    private val kinds = EvalSuite.ALL.groupBy { it.kind }.mapValues { it.value.size }

    private fun counts(allPass: Boolean = true): String =
        kinds.entries.joinToString(",") { "\"${it.key.name}\": ${if (allPass) it.value else 0}" }

    private fun manifest(
        suite: Int = EvalSuite.VERSION,
        valLoss: Double = 1.10,
        bytes: Long = 4_000_000_000L,
        sha: String = GOOD_SHA,
        passed: String = counts(),
        total: String = counts(),
        model: Boolean = true,
    ): String = buildString {
        append("{\n")
        append("  \"origin\": \"gpu\",\n")
        append("  \"baseModel\": \"Qwen/Qwen2.5-Coder-7B-Instruct\",\n")
        append("  \"corpusSha256\": \"abc123\",\n")
        append("  \"valLoss\": $valLoss,\n")
        append("  \"suiteVersion\": $suite,\n")
        append("  \"passed\": {$passed},\n")
        append("  \"total\": {$total},\n")
        append("  \"config\": \"{\\\"loraR\\\": 16}\",\n")
        append("  \"createdAt\": 1763500000000")
        if (model) append(",\n  \"model\": {\"file\": \"model.gguf\", \"bytes\": $bytes, \"sha256\": \"$sha\"}\n") else append("\n")
        append("}\n")
    }

    private fun bundle(
        manifestText: String = manifest(),
        modelName: String = "model.gguf",
        bytes: Long = 4_000_000_000L,
        sha: String = GOOD_SHA,
    ) = GpuImport.Bundle(manifestText, "{}", modelName, bytes, sha)

    private fun champion(valLoss: Double = 1.20): PromotionGate.Report {
        val total = kinds.mapKeys { it.key }
        val passed = kinds.mapValues { (_, n) -> (n * 0.8).toInt() }
        return PromotionGate.Report(1, passed, total, valLoss)
    }

    @Test
    fun a_bundle_that_wins_is_accepted_and_says_what_improved() {
        val result = GpuImport.check(bundle(), champion(valLoss = 1.20))
        assertTrue(result.toString(), result is GpuImport.Result.Accepted)
        val accepted = result as GpuImport.Result.Accepted
        assertEquals("Qwen/Qwen2.5-Coder-7B-Instruct", accepted.manifest.baseModel)
        assertTrue(PromotionGate.explain(accepted.decision).contains("validation loss"))
    }

    @Test
    fun a_bundle_that_loses_is_refused_with_the_axis_and_both_numbers() {
        val result = GpuImport.check(bundle(manifest(valLoss = 1.30)), champion(valLoss = 1.20))
        val refused = result as GpuImport.Result.Refused
        assertTrue(refused.message, refused.message.startsWith("Not promoted."))
        assertTrue(refused.message, refused.message.contains("validation loss"))
    }

    @Test
    fun a_corrupt_model_file_is_refused_by_its_fingerprint() {
        val refused = GpuImport.check(bundle(sha = "0".repeat(64)), null) as GpuImport.Result.Refused
        assertTrue(refused.message, refused.message.contains("fingerprint does not match"))
        assertTrue(refused.message, refused.message.contains("Nothing was imported"))
    }

    @Test
    fun a_half_downloaded_model_is_refused_by_size() {
        val refused = GpuImport.check(
            bundle(manifest(bytes = 4_000_000_000L), bytes = 2_000_000_000L),
            null,
        ) as GpuImport.Result.Refused
        assertTrue(refused.message, refused.message.contains("stopped early"))
    }

    @Test
    fun a_stub_model_cannot_pass_as_a_real_one() {
        val refused = GpuImport.check(
            bundle(manifest(bytes = 1024), bytes = 1024),
            null,
        ) as GpuImport.Result.Refused
        assertTrue(refused.message, refused.message.contains("stub or a half download"))
    }

    @Test
    fun numbers_from_another_evaluation_suite_are_refused_not_compared() {
        val refused = GpuImport.check(bundle(manifest(suite = 2)), champion()) as GpuImport.Result.Refused
        assertTrue(refused.message, refused.message.contains("cannot be compared"))
    }

    @Test
    fun a_bundle_missing_a_probe_kind_is_refused_and_names_it() {
        val onlyInstruction = kinds.filterKeys { it == EvalSuite.Kind.INSTRUCTION }
            .entries.joinToString(",") { "\"${it.key.name}\": ${it.value}" }
        val refused = GpuImport.check(
            bundle(manifest(passed = onlyInstruction, total = onlyInstruction)),
            champion(),
        ) as GpuImport.Result.Refused
        assertTrue(refused.message, refused.message.contains("did not report every probe kind"))
        assertTrue(refused.message, refused.message.contains("tool choice"))
    }

    @Test
    fun a_manifest_without_a_model_fingerprint_is_refused() {
        val refused = GpuImport.check(bundle(manifest(model = false), sha = GOOD_SHA), null) as GpuImport.Result.Refused
        assertTrue(refused.message, refused.message.contains("does not record the model's fingerprint"))
    }

    @Test
    fun a_zip_with_no_model_in_it_is_refused() {
        val refused = GpuImport.check(bundle(modelName = ""), null) as GpuImport.Result.Refused
        assertTrue(refused.message, refused.message.contains("no model file"))
    }

    @Test
    fun broken_json_is_refused_without_throwing() {
        val refused = GpuImport.check(bundle(manifestText = "{nope"), null) as GpuImport.Result.Refused
        assertTrue(refused.message, refused.message.contains("not valid JSON"))
    }

    @Test
    fun a_manifest_missing_what_was_trained_on_what_is_refused() {
        val text = "{\"suiteVersion\": 1, \"passed\": {}, \"total\": {}}"
        val refused = GpuImport.check(bundle(manifestText = text), null) as GpuImport.Result.Refused
        assertTrue(refused.message, refused.message.contains("base model"))
    }

    @Test
    fun the_first_gpu_generation_is_promoted_when_it_has_real_evidence() {
        val result = GpuImport.check(bundle(), null)
        assertTrue(result.toString(), result is GpuImport.Result.Accepted)
    }

    private companion object {
        const val GOOD_SHA = "9f2c4b7d1a0e3f5628a1c9d4b7e0f3a6c8d1e4b7f0a3c6d9e2b5f8a1c4d7e0b3"
    }
}
