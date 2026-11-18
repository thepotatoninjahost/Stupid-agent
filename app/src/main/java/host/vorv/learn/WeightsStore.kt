package host.vorv.learn

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Step 8: generations on disk, one directory per generation.
 *
 * A generation is a byte-identical set of weight floats plus the config that
 * produced them. Loading verifies the magic, the format, the parameter count
 * and a CRC32 of the payload, so a truncated or partial file cannot be served
 * as if it were a real model. Every write lands in a `.part` file and is
 * renamed only after it is complete and verified.
 */
object WeightsStore {

    const val MAGIC = 0x564F5256 // "VORV"
    const val FORMAT = 1
    const val WEIGHTS = "weights.bin"
    const val MANIFEST = "manifest.json"
    const val ACTIVE = "active"

    data class Meta(
        val generation: Int,
        val created: Long,
        val trainLoss: Double,
        val valLoss: Double,
        val corpusRows: Int,
        val steps: Int,
        val seed: Long,
        val note: String = "",
    )

    data class Saved(val dir: File, val bytes: Long, val sha256: String)

    class BadGeneration(message: String) : Exception(message)

    fun dirFor(root: File, generation: Int): File {
        require(generation >= 1) { "generation must be at least 1" }
        return File(root, "gen-$generation")
    }

    fun save(root: File, core: VorvCore, meta: Meta): Saved {
        require(meta.generation >= 1) { "generation must be at least 1" }
        val dir = dirFor(root, meta.generation)
        if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("cannot create ${dir.path}")
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT)
            val c = core.config
            out.writeUTF(c.name)
            for (v in intArrayOf(c.vocab, c.d, c.layers, c.heads, c.kvHeads, c.ff, c.ctx)) out.writeInt(v)
            out.writeInt(core.params.size)
            for (p in core.params) {
                out.writeInt(p.size)
                for (v in p.data) out.writeFloat(v)
            }
            out.flush()
        }
        val body = payload.toByteArray()
        val crc = CRC32().also { it.update(body) }.value
        val part = File(dir, "$WEIGHTS.part")
        FileOutputStream(part).use { fos ->
            val out = DataOutputStream(fos)
            out.write(body)
            out.writeLong(crc)
            out.flush()
            fos.fd.sync()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(part.readBytes())
            .joinToString("") { "%02x".format(it) }
        val target = File(dir, WEIGHTS)
        if (target.exists() && !target.delete()) throw java.io.IOException("cannot replace ${target.path}")
        if (!part.renameTo(target)) throw java.io.IOException("cannot rename ${part.path} to ${target.path}")
        File(dir, MANIFEST).writeText(manifestJson(core, meta, target.length(), digest))
        return Saved(dir, target.length(), digest)
    }

    fun load(root: File, generation: Int): VorvCore {
        val file = File(dirFor(root, generation), WEIGHTS)
        if (!file.exists()) throw BadGeneration("generation $generation has no $WEIGHTS at ${file.path}")
        val raw = file.readBytes()
        if (raw.size < 32) throw BadGeneration("generation $generation is only ${raw.size} bytes: not a model")
        val body = raw.copyOfRange(0, raw.size - 8)
        var storedCrc = 0L
        for (i in raw.size - 8 until raw.size) storedCrc = (storedCrc shl 8) or (raw[i].toLong() and 0xFF)
        val actualCrc = CRC32().also { it.update(body) }.value
        if (storedCrc != actualCrc) {
            throw BadGeneration("generation $generation failed its checksum (stored $storedCrc, computed $actualCrc)")
        }
        DataInputStream(body.inputStream()).use { input ->
            if (input.readInt() != MAGIC) throw BadGeneration("generation $generation is not a Vorv model (bad magic)")
            val format = input.readInt()
            if (format != FORMAT) throw BadGeneration("generation $generation uses format $format, this build reads $FORMAT")
            val name = input.readUTF()
            val vocab = input.readInt()
            val d = input.readInt()
            val layers = input.readInt()
            val heads = input.readInt()
            val kvHeads = input.readInt()
            val ff = input.readInt()
            val ctx = input.readInt()
            val config = VorvCore.CoreConfig(vocab, d, layers, heads, kvHeads, ff, ctx, name)
            val count = input.readInt()
            val core = VorvCore(config)
            if (count != core.params.size) {
                throw BadGeneration("generation $generation stores $count tensors, config ${config.name} needs ${core.params.size}")
            }
            for (p in core.params) {
                val n = input.readInt()
                if (n != p.size) throw BadGeneration("tensor size $n does not match ${p.size} for ${config.name}")
                for (i in 0 until n) p.data[i] = input.readFloat()
            }
            for (p in core.params) for (v in p.data) {
                if (!v.isFinite()) throw BadGeneration("generation $generation contains a non-finite weight")
            }
            return core
        }
    }

    fun setActive(root: File, generation: Int) {
        val part = File(root, "$ACTIVE.part")
        part.writeText(generation.toString())
        val target = File(root, ACTIVE)
        if (target.exists() && !target.delete()) throw java.io.IOException("cannot replace ${target.path}")
        if (!part.renameTo(target)) throw java.io.IOException("cannot rename ${part.path}")
    }

    fun active(root: File): Int? {
        val f = File(root, ACTIVE)
        if (!f.exists()) return null
        val n = f.readText().trim().toIntOrNull() ?: return null
        return if (File(dirFor(root, n), WEIGHTS).exists()) n else null
    }

    fun generations(root: File): List<Int> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("gen-") && File(it, WEIGHTS).exists() }
            .mapNotNull { it.name.removePrefix("gen-").toIntOrNull() }
            .sorted()

    private fun manifestJson(core: VorvCore, meta: Meta, bytes: Long, sha: String): String {
        val c = core.config
        val note = meta.note.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            append("{\n")
            append("  \"generation\": ${meta.generation},\n")
            append("  \"created\": ${meta.created},\n")
            append("  \"config\": {\"name\": \"${c.name}\", \"vocab\": ${c.vocab}, \"d\": ${c.d}, \"layers\": ${c.layers}, ")
            append("\"heads\": ${c.heads}, \"kvHeads\": ${c.kvHeads}, \"ff\": ${c.ff}, \"ctx\": ${c.ctx}},\n")
            append("  \"params\": ${core.parameterCount},\n")
            append("  \"trainLoss\": ${meta.trainLoss},\n")
            append("  \"valLoss\": ${meta.valLoss},\n")
            append("  \"corpusRows\": ${meta.corpusRows},\n")
            append("  \"steps\": ${meta.steps},\n")
            append("  \"seed\": ${meta.seed},\n")
            append("  \"bytes\": $bytes,\n")
            append("  \"sha256\": \"$sha\",\n")
            append("  \"note\": \"$note\"\n")
            append("}\n")
        }
    }
}
