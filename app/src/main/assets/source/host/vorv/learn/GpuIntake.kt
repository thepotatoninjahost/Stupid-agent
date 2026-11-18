package host.vorv.learn

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Step 10a: intake of a bundle the GPU run produced.
 *
 * [GpuBundleZip] unwraps and hashes; [GpuImport] judges; this class does the
 * moving — one folder per GPU generation beside the phone's own generations,
 * then a record in [GenerationStore] whatever the verdict was, so a refusal is
 * readable later instead of disappearing.
 */
object GpuIntake {

    data class Outcome(
        val accepted: Boolean,
        val generation: Int,
        val artifact: File,
        val message: String,
    )

    sealed class Result {
        data class Done(val outcome: Outcome) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * [zip] is a copy of the file the owner picked; [root] is the brain folder.
     * [champion] is the evaluation record of whatever is serving, or null when
     * nothing is serving yet.
     */
    fun import(
        zip: File,
        root: File,
        champion: PromotionGate.Report?,
        minModelBytes: Long = GpuImport.MIN_MODEL_BYTES,
    ): Result {
        val store = GenerationStore(root)
        val nextId = (store.generations().maxOfOrNull { it.id } ?: 0) + 1
        val target = File(File(root, "gpu"), "generation-$nextId")
        if (!target.isDirectory && !target.mkdirs()) {
            return Result.Failed("The brain folder could not be created at ${target.path}, so nothing was imported.")
        }
        val model = File(target, "model.gguf")

        val unwrapped = when (val read = GpuBundleZip.read(zip, model)) {
            is GpuBundleZip.Result.Ok -> read
            is GpuBundleZip.Result.Failed -> {
                model.delete()
                return Result.Failed(read.message)
            }
        }
        val verdict = GpuImport.check(unwrapped.bundle, champion, minModelBytes)
        val accepted = verdict is GpuImport.Result.Accepted
        val manifest = (verdict as? GpuImport.Result.Accepted)?.manifest
        val decisionText = when (verdict) {
            is GpuImport.Result.Accepted -> PromotionGate.explain(verdict.decision)
            is GpuImport.Result.Refused -> verdict.message
        }
        if (verdict is GpuImport.Result.Refused && verdict.structural) {
            // Nothing about this file can ever serve, so it is not kept: the
            // owner asked for no half pieces, and 4 GB is a fifth of the budget.
            model.delete()
            return Result.Failed(decisionText)
        }
        val record = GenerationStore.Generation(
            id = nextId,
            createdAt = System.currentTimeMillis(),
            origin = "gpu",
            artifactPath = model.relativeTo(root).path,
            artifactSha256 = unwrapped.bundle.modelSha256,
            sizeBytes = model.length(),
            valLoss = manifest?.valLoss ?: readLossFrom(unwrapped.bundle.manifestText),
            accepted = accepted,
            decision = decisionText,
            corpusSha256 = manifest?.corpusSha256 ?: readString(unwrapped.bundle.manifestText, "corpusSha256"),
            config = manifest?.config ?: readString(unwrapped.bundle.manifestText, "config"),
        )
        val added = store.add(record, accept = accepted)
        return when (added) {
            is GenerationStore.Result.Ok ->
                Result.Done(Outcome(accepted, nextId, model, added.message))

            is GenerationStore.Result.Refused -> {
                model.delete()
                Result.Failed(added.message)
            }
        }
    }

    private fun readLossFrom(manifestText: String): Double? =
        number(manifestText, "valLoss")

    private fun readString(manifestText: String, key: String): String = try {
        val parsed = JsonParser.parseString(manifestText)
        if (!parsed.isJsonObject) "" else {
            val obj: JsonObject = parsed.asJsonObject
            if (obj.has(key) && obj.get(key).isJsonPrimitive && obj.get(key).asJsonPrimitive.isString) {
                obj.get(key).asString
            } else {
                ""
            }
        }
    } catch (t: Throwable) {
        ""
    }

    private fun number(manifestText: String, key: String): Double? = try {
        val parsed = JsonParser.parseString(manifestText)
        if (!parsed.isJsonObject) null else {
            val obj = parsed.asJsonObject
            if (obj.has(key) && obj.get(key).isJsonPrimitive) obj.get(key).asDouble else null
        }
    } catch (t: Throwable) {
        null
    }
}
