package host.vorv.learn

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Step 10: the way back from the GPU.
 *
 * The quality path trains off-device and returns one zip: the merged model
 * plus the numbers that produced it. This class is the phone's side of that
 * hand-off, and it trusts none of it. A bundle is accepted only when
 *   - it names a model file, and the file on disk is the size the bundle says;
 *   - the file's SHA-256 is the one the bundle recorded (a truncated download
 *     cannot pass);
 *   - the metrics were measured with this app's own evaluation suite (numbers
 *     from a different suite are not comparable, so they are refused);
 *   - every probe kind the phone's suite asks is present, passed and counted;
 *   - and [PromotionGate] says the candidate is not worse than the champion.
 *
 * Pure: no files, no coroutines, no Android services. The zip is unwrapped by
 * [GpuBundleZip]; the caller supplies the already-hashed model file facts.
 */
object GpuImport {

    const val MANIFEST = "manifest.json"
    const val METRICS = "metrics.json"

    /** A 7B-class model at 4-bit is ~3.5-4.5 GB; anything this small is a stub. */
    const val MIN_MODEL_BYTES = 1L * 1024 * 1024 * 1024

    data class Bundle(
        val manifestText: String,
        val metricsText: String?,
        val modelName: String,
        val modelBytes: Long,
        val modelSha256: String,
    )

    data class Manifest(
        val baseModel: String,
        val corpusSha256: String,
        val valLoss: Double?,
        val suiteVersion: Int,
        val passed: Map<EvalSuite.Kind, Int>,
        val total: Map<EvalSuite.Kind, Int>,
        val config: String,
        val createdAt: Long,
    ) {
        fun report(generationId: Int) = PromotionGate.Report(generationId, passed, total, valLoss)
    }

    sealed class Result {
        data class Accepted(
            val manifest: Manifest,
            val decision: PromotionGate.Decision,
        ) : Result()

        /**
         * [structural] means the bundle itself is unusable (no model, a stub, a
         * stopped download, a corrupt file, the wrong suite, missing counts), so
         * the file is deleted and nothing is recorded. A gate refusal is not
         * structural: the file is real, it simply lost.
         */
        data class Refused(val message: String, val structural: Boolean = true) : Result()
    }

    fun check(
        bundle: Bundle,
        champion: PromotionGate.Report?,
        minModelBytes: Long = MIN_MODEL_BYTES,
    ): Result {
        val manifest = when (val parsed = readManifest(bundle.manifestText)) {
            is Parse.Failed -> return Result.Refused(parsed.message)
            is Parse.Ok -> parsed.manifest
        }

        if (bundle.modelName.isBlank()) {
            return Result.Refused(
                "This zip has no model file in it, so there is nothing to serve. " +
                    "The GPU run should have written model.gguf next to ${MANIFEST}."
            )
        }
        if (bundle.modelBytes < minModelBytes) {
            return Result.Refused(
                "The model file is ${megabytes(bundle.modelBytes)} MB. A real 7B model is " +
                    "over 1000 MB, so this is a stub or a half download — nothing was imported."
            )
        }
        val declared = declaredBytes(bundle.manifestText)
        if (declared != null && declared != bundle.modelBytes) {
            return Result.Refused(
                "The model file is ${bundle.modelBytes} bytes but the bundle says $declared — " +
                    "the download stopped early. Nothing was imported."
            )
        }
        val declaredSha = declaredSha256(bundle.manifestText)
        if (declaredSha == null) {
            return Result.Refused(
                "The bundle does not record the model's fingerprint (sha256), so a corrupt " +
                    "file could pass as good. Nothing was imported."
            )
        }
        if (!declaredSha.equals(bundle.modelSha256, ignoreCase = true)) {
            return Result.Refused(
                "The model file's fingerprint does not match the bundle " +
                    "(${short(declaredSha)} expected, ${short(bundle.modelSha256)} measured), so the " +
                    "file is corrupt or was changed. Nothing was imported."
            )
        }

        if (manifest.suiteVersion != EvalSuite.VERSION) {
            return Result.Refused(
                "The GPU run measured with evaluation suite ${manifest.suiteVersion}; this app " +
                    "compares with suite ${EvalSuite.VERSION}. Numbers from two suites cannot be " +
                    "compared, so the candidate was not promoted."
            )
        }
        val missing = EvalSuite.ALL.map { it.kind }.distinct().filter {
            (manifest.total[it] ?: 0) <= 0 || !manifest.passed.containsKey(it)
        }
        if (missing.isNotEmpty()) {
            return Result.Refused(
                "The GPU run did not report every probe kind this app grades (" +
                    missing.joinToString(", ") { it.name.lowercase().replace('_', ' ') } +
                    "), so its numbers cannot be checked. Nothing was imported."
            )
        }

        val decision = PromotionGate.decide(manifest.report(0), champion)
        return if (decision.isPromote) {
            Result.Accepted(manifest, decision)
        } else {
            Result.Refused(
                "Not promoted. " + PromotionGate.explain(decision),
                structural = false,
            )
        }
    }

    private sealed class Parse {
        class Ok(val manifest: Manifest) : Parse()
        class Failed(val message: String) : Parse()
    }

    private fun readManifest(text: String): Parse {
        val obj = runCatchingJson(text)
            ?: return Parse.Failed("${MANIFEST} is not valid JSON, so the bundle was refused.")
        val suite = obj.int("suiteVersion", -1)
        val base = obj.string("baseModel")
        val corpusSha = obj.string("corpusSha256")
        if (suite <= 0 || base.isBlank() || corpusSha.isBlank()) {
            return Parse.Failed(
                "${MANIFEST} is missing the suite version, the base model or the corpus " +
                    "fingerprint, so there is no way to tell what was trained on what."
            )
        }
        val passed = kindCounts(obj.objectOrNull("passed"))
        val total = kindCounts(obj.objectOrNull("total"))
        val valLoss = if (obj.has("valLoss") && obj.get("valLoss").isJsonPrimitive) {
            obj.get("valLoss").asDouble.takeIf { !it.isNaN() }
        } else {
            null
        }
        return Parse.Ok(
            Manifest(
                baseModel = base,
                corpusSha256 = corpusSha,
                valLoss = valLoss,
                suiteVersion = suite,
                passed = passed,
                total = total,
                config = obj.string("config"),
                createdAt = obj.long("createdAt", 0L),
            )
        )
    }

    private fun kindCounts(obj: JsonObject?): Map<EvalSuite.Kind, Int> {
        if (obj == null) return emptyMap()
        val out = mutableMapOf<EvalSuite.Kind, Int>()
        for (kind in EvalSuite.Kind.entries) {
            if (obj.has(kind.name)) out[kind] = obj.int(kind.name, 0)
        }
        return out
    }

    private fun declaredBytes(text: String): Long? {
        val model = runCatchingJson(text)?.objectOrNull("model") ?: return null
        return if (model.has("bytes")) model.long("bytes", -1).takeIf { it >= 0 } else null
    }

    private fun declaredSha256(text: String): String? {
        val obj = runCatchingJson(text) ?: return null
        val fromModel = obj.objectOrNull("model")?.string("sha256")?.takeIf { it.isNotBlank() }
        return fromModel ?: obj.string("modelSha256").takeIf { it.isNotBlank() }
    }

    private fun runCatchingJson(text: String): JsonObject? = try {
        val parsed = JsonParser.parseString(text)
        if (parsed.isJsonObject) parsed.asJsonObject else null
    } catch (t: Throwable) {
        null
    }

    // Small readers so a number stored as a string, or a missing key, cannot
    // throw out of a verification path that must always answer in words.
    private fun JsonObject.int(key: String, fallback: Int): Int =
        if (has(key) && get(key).isJsonPrimitive) get(key).asInt else fallback

    private fun JsonObject.long(key: String, fallback: Long): Long =
        if (has(key) && get(key).isJsonPrimitive) get(key).asLong else fallback

    private fun JsonObject.string(key: String): String =
        if (has(key) && get(key).isJsonPrimitive && get(key).asJsonPrimitive.isString) {
            get(key).asString
        } else {
            ""
        }

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun short(sha: String): String = if (sha.length <= 12) sha else sha.take(12) + "…"

    private fun megabytes(bytes: Long): Long = bytes / (1024 * 1024)
}
