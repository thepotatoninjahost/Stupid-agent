package host.vorv.learn

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Unwraps the zip the GPU run hands back.
 *
 * The model file is copied out while it is being hashed in the same pass, so
 * the fingerprint describes exactly the bytes that ended up on disk. A copy
 * that fails halfway deletes what it wrote: a partial model must never sit
 * where it could be loaded, and the owner must never be told "imported".
 */
object GpuBundleZip {

    private const val MANIFEST_WORDS = "/manifest.json"

    /**
     * M-2 (code review, 2026-11): an import is a file the owner picked, so its
     * size is a ceiling to check, not a promise. An entry can lie about its
     * size, so the model copy also stops counting past [MAX_MODEL_BYTES].
     */
    private const val MAX_ARCHIVE_BYTES = 12L * 1024 * 1024 * 1024
    private const val MAX_MODEL_BYTES = 12L * 1024 * 1024 * 1024
    private const val MAX_SIDECAR_BYTES = 1L * 1024 * 1024

    sealed class Result {
        class Ok(val bundle: GpuImport.Bundle, val model: File) : Result()
        class Failed(val message: String) : Result()
    }

    fun read(zip: File, modelOut: File): Result {
        if (!zip.isFile) return Result.Failed("There is no file at ${zip.path} to import.")
        if (zip.length() == 0L) return Result.Failed("${zip.name} is empty (0 bytes), so it holds nothing.")
        if (zip.length() > MAX_ARCHIVE_BYTES) {
            val gb = "%.1f".format(zip.length() / (1024.0 * 1024.0 * 1024.0))
            return Result.Failed(
                "${zip.name} is $gb GB; the largest bundle Vorv imports is " +
                    "${MAX_ARCHIVE_BYTES / (1024 * 1024 * 1024)} GB. Nothing was imported.",
            )
        }
        val archive = try {
            ZipFile(zip)
        } catch (t: Throwable) {
            return Result.Failed("${zip.name} is not a zip file (${t.message}). Nothing was imported.")
        }
        archive.use { open ->
            var manifestText: String? = null
            var metricsText: String? = null
            var modelEntry: String? = null
            var modelBytes = 0L
            var digestHex: String? = null

            for (entry in open.entries()) {
                val name = entry.name
                if (entry.isDirectory) continue
                val base = name.substringAfterLast('/')
                when {
                    base == GpuImport.MANIFEST && manifestText == null -> {
                        if (entry.size > MAX_SIDECAR_BYTES || entry.size < 0) {
                            return Result.Failed(
                                "${zip.name} has an oversized or unreadable manifest " +
                                    "(${entry.size} bytes); the limit is ${MAX_SIDECAR_BYTES / (1024 * 1024)} MB. " +
                                    "Nothing was imported.",
                            )
                        }
                        manifestText = open.getInputStream(entry).bufferedReader().use { it.readText() }
                    }

                    base == GpuImport.METRICS && metricsText == null -> {
                        if (entry.size > MAX_SIDECAR_BYTES || entry.size < 0) {
                            return Result.Failed(
                                "${zip.name} has oversized or unreadable metrics " +
                                    "(${entry.size} bytes); the limit is ${MAX_SIDECAR_BYTES / (1024 * 1024)} MB. " +
                                    "Nothing was imported.",
                            )
                        }
                        metricsText = open.getInputStream(entry).bufferedReader().use { it.readText() }
                    }

                    base.endsWith(".gguf") && modelEntry == null -> {
                        if (entry.size > MAX_MODEL_BYTES) {
                            return Result.Failed(
                                "The model inside ${zip.name} claims ${entry.size} bytes; the largest " +
                                    "Vorv imports is ${MAX_MODEL_BYTES / (1024 * 1024 * 1024)} GB. " +
                                    "Nothing was imported.",
                            )
                        }
                        modelEntry = name
                        modelOut.parentFile?.mkdirs()
                        val digest = MessageDigest.getInstance("SHA-256")
                        val counted = try {
                            open.getInputStream(entry).use { input ->
                                modelOut.outputStream().use { out ->
                                    val buffer = ByteArray(1 shl 16)
                                    var written = 0L
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        digest.update(buffer, 0, read)
                                        out.write(buffer, 0, read)
                                        written += read
                                        if (written > MAX_MODEL_BYTES) {
                                            // The entry lied about its size (or
                                            // is a zip bomb): stop, don't fill
                                            // the phone's storage.
                                            throw java.io.IOException(
                                                "the model is larger than ${MAX_MODEL_BYTES / (1024 * 1024 * 1024)} GB"
                                            )
                                        }
                                    }
                                    written
                                }
                            }
                        } catch (t: Throwable) {
                            modelOut.delete()
                            return Result.Failed(
                                "The model inside ${zip.name} could not be written " +
                                    "(${t.javaClass.simpleName}: ${t.message}). Nothing was imported."
                            )
                        }
                        modelBytes = counted
                        digestHex = digest.digest().joinToString("") { "%02x".format(it) }
                    }
                }
            }

            val manifest = manifestText
                ?: return Result.Failed(
                    "${zip.name} has no ${GpuImport.MANIFEST} in it, so it is not a Vorv GPU bundle."
                )
            val model = modelEntry
                ?: return Result.Failed(
                    "${zip.name} has no .gguf model in it, so there is nothing to serve."
                )
            return Result.Ok(
                GpuImport.Bundle(
                    manifestText = manifest,
                    metricsText = metricsText,
                    modelName = model.substringAfterLast('/'),
                    modelBytes = modelBytes,
                    modelSha256 = digestHex ?: "",
                ),
                modelOut,
            )
        }
    }
}
