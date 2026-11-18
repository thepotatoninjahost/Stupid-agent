package host.vorv.learn

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/**
 * Step 10: generations on disk, and the pointer that decides which one serves.
 *
 * Rules this class enforces rather than documents:
 *   * a generation is a file plus its hash; promotion is refused if the file is
 *     gone or its bytes changed, so "roll back" is always a pointer swap to a
 *     byte-identical artifact — never a re-download and never a rebuild;
 *   * the champion pointer only ever moves to a generation the gate accepted;
 *   * rollback moves back one accepted generation and says which one, or says
 *     there is nothing to go back to;
 *   * every write is followed by a read of what was written, because a file
 *     that claims to be saved and is not is worse than an error.
 */
class GenerationStore(private val root: File) {

    private val indexFile = File(root, "generations.json")

    data class Generation(
        val id: Int,
        val createdAt: Long,
        val origin: String,          // "phone" | "gpu"
        val artifactPath: String,
        val artifactSha256: String,
        val sizeBytes: Long,
        val valLoss: Double?,
        val accepted: Boolean,
        val decision: String,
        val corpusSha256: String,
        val config: String,
    )

    private data class Index(val generations: List<Generation>, val championId: Int?)

    sealed class Result {
        data class Ok(val message: String, val generation: Generation?) : Result()
        data class Refused(val message: String) : Result()
    }

    fun generations(): List<Generation> = read().generations

    fun champion(): Generation? {
        val index = read()
        return index.generations.firstOrNull { it.id == index.championId }
    }

    /**
     * Adds a candidate. [accepted] comes from [PromotionGate] — this class does
     * not decide quality, it records the verdict and moves the pointer only
     * when the verdict was a win.
     */
    fun add(generation: Generation, accept: Boolean): Result {
        val artifact = File(root, generation.artifactPath)
        if (!artifact.isFile) {
            return Result.Refused("The model file for generation ${generation.id} is missing, so it was not recorded.")
        }
        val actual = sha256(artifact)
        if (actual != generation.artifactSha256) {
            return Result.Refused(
                "The model file for generation ${generation.id} does not match its hash, so it was not recorded."
            )
        }
        val index = read()
        if (index.generations.any { it.id == generation.id }) {
            return Result.Refused("Generation ${generation.id} is already recorded.")
        }
        val record = generation.copy(
            sizeBytes = artifact.length(),
            accepted = accept,
            artifactSha256 = actual,
        )
        val next = Index(index.generations + record, if (accept) record.id else index.championId)
        write(next)
        if (!accept) {
            return Result.Ok("Generation ${record.id} kept, not promoted: ${record.decision}", record)
        }
        return Result.Ok("Generation ${record.id} is now the champion.", record)
    }

    /** Moves the pointer to [id] if that generation exists, is accepted, and its file still verifies. */
    fun promote(id: Int): Result {
        val index = read()
        val target = index.generations.firstOrNull { it.id == id }
            ?: return Result.Refused("There is no generation $id.")
        if (!target.accepted) {
            return Result.Refused("Generation $id was refused by the gate when it was made, so it cannot serve.")
        }
        val verify = verify(target)
        if (verify != null) return Result.Refused(verify)
        write(Index(index.generations, id))
        return Result.Ok("Now serving generation $id.", target)
    }

    /** One tap back: the newest accepted generation older than the champion. */
    fun rollback(): Result {
        val index = read()
        val current = index.championId ?: return Result.Refused("No generation is serving yet, so there is nothing to roll back.")
        val previous = index.generations
            .filter { it.accepted && it.id < current }
            .maxByOrNull { it.id }
            ?: return Result.Refused("Generation $current is the oldest one kept, so there is nothing to go back to.")
        val verify = verify(previous)
        if (verify != null) return Result.Refused(verify)
        write(Index(index.generations, previous.id))
        return Result.Ok("Went back to generation ${previous.id}. Generation $current is still kept.", previous)
    }

    /** Why rollback to [g] would fail, or null when it is safe. */
    private fun verify(g: Generation): String? {
        val file = File(root, g.artifactPath)
        if (!file.isFile) return "Generation ${g.id}: its file is missing, so it cannot serve."
        if (sha256(file) != g.artifactSha256) {
            return "Generation ${g.id}: its file changed since it was recorded, so it cannot serve."
        }
        return null
    }

    private fun read(): Index {
        if (!indexFile.isFile) return Index(emptyList(), null)
        return try {
            val parsed = JsonParser.parseString(indexFile.readText())
            if (!parsed.isJsonObject) return Index(emptyList(), null)
            val obj = parsed.asJsonObject
            val list = obj.getAsJsonArray("generations") ?: JsonArray()
            val generations = list.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val o = element.asJsonObject
                Generation(
                    id = o.int("id") ?: return@mapNotNull null,
                    createdAt = o.long("createdAt") ?: 0L,
                    origin = o.string("origin") ?: "unknown",
                    artifactPath = o.string("artifactPath") ?: return@mapNotNull null,
                    artifactSha256 = o.string("artifactSha256") ?: "",
                    sizeBytes = o.long("sizeBytes") ?: 0L,
                    valLoss = o.double("valLoss"),
                    accepted = o.get("accepted")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    decision = o.string("decision") ?: "",
                    corpusSha256 = o.string("corpusSha256") ?: "",
                    config = o.string("config") ?: "",
                )
            }
            val champion = obj.int("championId")
            Index(generations, champion)
        } catch (t: Throwable) {
            // A broken index must not be silently replaced with an empty one:
            // the caller sees no champion, and the file stays on disk for repair.
            Index(emptyList(), null)
        }
    }

    private fun write(index: Index) {
        if (!root.isDirectory && !root.mkdirs()) {
            throw IllegalStateException("cannot create ${root.path}")
        }
        val list = JsonArray()
        for (g in index.generations) {
            list.add(JsonObject().apply {
                addProperty("id", g.id)
                addProperty("createdAt", g.createdAt)
                addProperty("origin", g.origin)
                addProperty("artifactPath", g.artifactPath)
                addProperty("artifactSha256", g.artifactSha256)
                addProperty("sizeBytes", g.sizeBytes)
                if (g.valLoss == null) add("valLoss", null) else addProperty("valLoss", g.valLoss)
                addProperty("accepted", g.accepted)
                addProperty("decision", g.decision)
                addProperty("corpusSha256", g.corpusSha256)
                addProperty("config", g.config)
            })
        }
        val obj = JsonObject().apply {
            add("generations", list)
            if (index.championId == null) add("championId", null) else addProperty("championId", index.championId)
        }
        val text = obj.toString()
        indexFile.writeText(text)
        val back = indexFile.readText()
        if (back != text) throw IllegalStateException("the generation index did not survive being written")
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.double(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
