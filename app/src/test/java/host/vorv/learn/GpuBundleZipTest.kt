package host.vorv.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The unzip step. It must hash exactly the bytes it wrote, and leave nothing
 * behind when a copy fails — a half model on disk is the failure this test exists
 * to prevent.
 */
class GpuBundleZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = ByteArray(4096) { (it * 7).toByte() }

    private fun zip(entries: Map<String, ByteArray>): File {
        val file = tmp.newFile("bundle.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, body) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(body)
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun a_bundle_is_unwrapped_with_a_fingerprint_of_what_was_written() {
        val file = zip(
            mapOf(
                "vorv/manifest.json" to "{\"suiteVersion\":1}".toByteArray(),
                "vorv/metrics.json" to "{\"baseLoss\":1.2}".toByteArray(),
                "vorv/model.gguf" to payload,
            )
        )
        val out = File(tmp.newFolder(), "model.gguf")
        val result = GpuBundleZip.read(file, out) as GpuBundleZip.Result.Ok
        assertEquals("model.gguf", result.bundle.modelName)
        assertEquals(payload.size.toLong(), result.bundle.modelBytes)
        assertTrue(out.isFile)
        assertEquals(payload.size.toLong(), out.length())

        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload).joinToString("") { "%02x".format(it) }
        assertEquals(expected, result.bundle.modelSha256)
        assertTrue(result.bundle.manifestText.contains("suiteVersion"))
        assertTrue(result.bundle.metricsText!!.contains("baseLoss"))
    }

    @Test
    fun m2_an_oversized_manifest_is_refused_and_nothing_is_written() {
        val file = zip(
            mapOf(
                "vorv/manifest.json" to ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() },
                "vorv/model.gguf" to payload,
            )
        )
        val out = File(tmp.newFolder(), "model.gguf")
        val result = GpuBundleZip.read(file, out) as GpuBundleZip.Result.Failed
        assertTrue(result.message, result.message.contains("oversized or unreadable manifest"))
        assertTrue("no model may be left behind", !out.exists())
    }

    @Test
    fun m2_an_oversized_archive_is_refused_in_words() {
        val file = tmp.newFile("huge.zip")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(13L * 1024 * 1024 * 1024) }
        val result = GpuBundleZip.read(file, File(tmp.newFolder(), "m.gguf")) as GpuBundleZip.Result.Failed
        assertTrue(result.message, result.message.contains("the largest bundle Vorv imports is 12 GB"))
    }

    @Test
    fun a_zip_without_a_manifest_is_refused_in_words() {
        val file = zip(mapOf("vorv/model.gguf" to payload))
        val result = GpuBundleZip.read(file, File(tmp.newFolder(), "m.gguf")) as GpuBundleZip.Result.Failed
        assertTrue(result.message, result.message.contains("no manifest.json"))
    }

    @Test
    fun a_zip_without_a_model_is_refused_in_words() {
        val file = zip(mapOf("vorv/manifest.json" to "{}".toByteArray()))
        val result = GpuBundleZip.read(file, File(tmp.newFolder(), "m.gguf")) as GpuBundleZip.Result.Failed
        assertTrue(result.message, result.message.contains("no .gguf model"))
    }

    @Test
    fun a_file_that_is_not_a_zip_is_refused_rather_than_thrown() {
        val notZip = tmp.newFile("bundle.zip").apply { writeText("this is not a zip") }
        val result = GpuBundleZip.read(notZip, File(tmp.newFolder(), "m.gguf")) as GpuBundleZip.Result.Failed
        assertTrue(result.message, result.message.contains("is not a zip file"))
    }

    @Test
    fun an_empty_file_is_refused() {
        val empty = tmp.newFile("bundle.zip")
        val result = GpuBundleZip.read(empty, File(tmp.newFolder(), "m.gguf")) as GpuBundleZip.Result.Failed
        assertTrue(result.message, result.message.contains("holds nothing"))
    }
}
