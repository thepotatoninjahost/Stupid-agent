package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End to end inside the app: a zip in, one recorded generation out, and the
 * generation store's own checksum check re-run on the file that landed on disk.
 */
class GpuIntakeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = ByteArray(64 * 1024) { (it * 31).toByte() }
    private val kinds = EvalSuite.ALL.groupBy { it.kind }.mapValues { it.value.size }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun counts(pass: Boolean) =
        kinds.entries.joinToString(",") { "\"${it.key.name}\": ${if (pass) it.value else 0}" }

    private fun bundleZip(valLoss: Double, suite: Int = EvalSuite.VERSION): File {
        val manifest = """
            {
              "origin": "gpu",
              "baseModel": "Qwen/Qwen2.5-Coder-7B-Instruct",
              "corpusSha256": "corpus-fingerprint",
              "valLoss": $valLoss,
              "suiteVersion": $suite,
              "passed": {${counts(true)}},
              "total": {${counts(true)}},
              "config": "{\"loraR\": 16}",
              "createdAt": 1763500000000,
              "model": {"file": "model.gguf", "bytes": ${payload.size}, "sha256": "${sha(payload)}"}
            }
        """.trimIndent()
        val zip = tmp.newFile("bundle-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("vorv/manifest.json"))
            out.write(manifest.toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("vorv/model.gguf"))
            out.write(payload)
            out.closeEntry()
        }
        return zip
    }

    private fun champion(valLoss: Double) = PromotionGate.Report(
        1,
        kinds.mapValues { (_, n) -> (n * 0.5).toInt() },
        kinds,
        valLoss,
    )

    @Test
    fun an_accepted_bundle_becomes_a_recorded_generation_with_its_file_on_disk() {
        val root = tmp.newFolder("brain")
        val result = GpuIntake.import(bundleZip(0.80), root, champion(1.20), minModelBytes = 1024)
        val done = result as GpuIntake.Result.Done
        assertTrue(done.outcome.message, done.outcome.accepted)
        assertEquals(1, done.outcome.generation)
        assertTrue(done.outcome.artifact.isFile)
        assertEquals(payload.size.toLong(), done.outcome.artifact.length())

        val store = GenerationStore(root)
        val record = store.generations().single()
        assertEquals("gpu", record.origin)
        assertTrue(record.accepted)
        assertEquals(sha(payload), record.artifactSha256)
        assertEquals(0.80, record.valLoss!!, 1e-9)
        assertEquals("corpus-fingerprint", record.corpusSha256)
        assertEquals(1, store.champion()!!.id)
    }

    @Test
    fun a_refused_bundle_is_still_recorded_so_the_refusal_is_readable() {
        val root = tmp.newFolder("brain")
        val result = GpuIntake.import(bundleZip(1.90), root, champion(1.20), minModelBytes = 1024)
        val done = result as GpuIntake.Result.Done
        assertFalse(done.outcome.accepted)
        val record = GenerationStore(root).generations().single()
        assertFalse(record.accepted)
        assertTrue(record.decision, record.decision.contains("Not promoted"))
        assertTrue(record.decision, record.decision.contains("validation loss"))
    }

    @Test
    fun nothing_serves_after_a_refusal() {
        val root = tmp.newFolder("brain")
        GpuIntake.import(bundleZip(1.90), root, champion(1.20), minModelBytes = 1024)
        assertEquals(null, GenerationStore(root).champion())
    }

    @Test
    fun a_bundle_from_another_suite_is_refused_and_its_file_is_deleted() {
        val root = tmp.newFolder("brain")
        val failed = GpuIntake.import(bundleZip(0.5, suite = 99), root, champion(1.2), minModelBytes = 1024)
            as GpuIntake.Result.Failed
        assertTrue(failed.message, failed.message.contains("cannot be compared"))
        assertTrue(GenerationStore(root).generations().isEmpty())
        assertFalse(java.io.File(root, "gpu/generation-1/model.gguf").exists())
    }

    @Test
    fun a_truncated_zip_leaves_no_generation_and_names_the_problem() {
        val root = tmp.newFolder("brain")
        val broken = tmp.newFile("broken.zip").apply { writeText("PK-not-really") }
        val result = GpuIntake.import(broken, root, null, minModelBytes = 1024)
        val failed = result as GpuIntake.Result.Failed
        assertTrue(failed.message, failed.message.contains("not a zip file"))
        assertTrue(GenerationStore(root).generations().isEmpty())
    }

    @Test
    fun the_second_bundle_gets_the_next_number_and_promotes_only_if_it_wins() {
        val root = tmp.newFolder("brain")
        GpuIntake.import(bundleZip(0.80), root, champion(1.20), minModelBytes = 1024)
        val second = GpuIntake.import(bundleZip(1.10), root, champion(0.80), minModelBytes = 1024)
            as GpuIntake.Result.Done
        assertEquals(2, second.outcome.generation)
        assertFalse(second.outcome.accepted)
        assertEquals(1, GenerationStore(root).champion()!!.id)
    }

    @Test
    fun a_bundle_whose_file_is_smaller_than_the_floor_is_refused_and_not_kept() {
        val root = tmp.newFolder("brain")
        val failed = GpuIntake.import(bundleZip(0.5), root, null, minModelBytes = 10L * 1024 * 1024)
            as GpuIntake.Result.Failed
        assertTrue(failed.message, failed.message.contains("stub"))
        assertTrue(GenerationStore(root).generations().isEmpty())
    }

    @Test
    fun a_corrupt_model_is_refused_and_its_file_is_deleted() {
        val root = tmp.newFolder("brain")
        val zip = bundleZip(0.5)
        // Rewrite the model bytes so the manifest's fingerprint no longer holds.
        val tampered = tmp.newFile("tampered.zip")
        java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            java.util.zip.ZipOutputStream(tampered.outputStream()).use { out ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    val body = input.readBytes()
                    out.write(if (entry.name.endsWith(".gguf")) body.reversedArray() else body)
                    out.closeEntry()
                }
            }
        }
        val failed = GpuIntake.import(tampered, root, null, minModelBytes = 1024)
            as GpuIntake.Result.Failed
        assertTrue(failed.message, failed.message.contains("fingerprint does not match"))
        assertTrue(GenerationStore(root).generations().isEmpty())
        assertFalse(java.io.File(root, "gpu/generation-1/model.gguf").exists())
    }
}
