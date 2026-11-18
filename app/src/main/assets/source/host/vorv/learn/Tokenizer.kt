package host.vorv.learn

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Step 8: a byte-level BPE tokenizer trained from this owner's own corpus.
 *
 * Byte level means no text can ever be unrepresentable: any UTF-8 — code,
 * emoji, a language the merges have never seen — encodes to bytes and decodes
 * back exactly. The merges are learned on the phone from the ledger's text, so
 * the vocabulary follows this owner's work instead of someone else's corpus.
 */
class Tokenizer(private val merges: List<Pair<Int, Int>>, val name: String = "vorv-bpe") {

    companion object {
        const val EOS = 256
        const val FIRST_MERGE = 257
        const val VERSION = 1
        const val MAX_TRAIN_CHARS = 400_000

        fun train(text: String, targetVocab: Int, name: String = "vorv-bpe"): Tokenizer {
            require(targetVocab in FIRST_MERGE..20_000) { "target vocab $targetVocab outside $FIRST_MERGE..20000" }
            val sample = if (text.length > MAX_TRAIN_CHARS) text.substring(0, MAX_TRAIN_CHARS) else text
            val words = splitWords(sample)
            val sequences = words.map { it.toMutableList() }
            val counts = HashMap<Long, Int>()
            fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

            fun countPairs() {
                counts.clear()
                for (seq in sequences) {
                    for (i in 0 until seq.size - 1) {
                        val k = pairKey(seq[i], seq[i + 1])
                        counts[k] = (counts[k] ?: 0) + 1
                    }
                }
            }

            val learned = ArrayList<Pair<Int, Int>>()
            countPairs()
            while (FIRST_MERGE + learned.size < targetVocab) {
                var bestKey = -1L
                var bestCount = 0
                for ((k, c) in counts) {
                    if (c > bestCount || (c == bestCount && bestKey >= 0 && k < bestKey)) {
                        bestCount = c
                        bestKey = k
                    }
                }
                if (bestKey < 0 || bestCount < 2) break
                val a = (bestKey ushr 32).toInt()
                val b = (bestKey and 0xFFFFFFFFL).toInt()
                val newId = FIRST_MERGE + learned.size
                learned.add(a to b)
                for (seq in sequences) {
                    var i = 0
                    while (i < seq.size - 1) {
                        if (seq[i] == a && seq[i + 1] == b) {
                            seq[i] = newId
                            seq.removeAt(i + 1)
                        } else {
                            i++
                        }
                    }
                }
                countPairs()
            }
            return Tokenizer(learned, name)
        }

        private fun splitWords(text: String): List<IntArray> {
            // Whitespace and ASCII punctuation are their own chunks; a run of
            // other bytes is one chunk. This keeps merges from spanning words.
            val out = ArrayList<IntArray>()
            val current = ArrayList<Byte>()
            fun flush() {
                if (current.isNotEmpty()) {
                    out.add(current.toByteArray().map { it.toInt() and 0xFF }.toIntArray())
                    current.clear()
                }
            }
            for (b in text.toByteArray(StandardCharsets.UTF_8)) {
                val v = b.toInt() and 0xFF
                val sep = v == 0x20 || v == 0x0A || v == 0x09 || v == 0x0D ||
                    (v in 0x21..0x2F) || (v in 0x3A..0x40) || (v in 0x5B..0x60) || (v in 0x7B..0x7E)
                if (sep) {
                    flush()
                    out.add(intArrayOf(v))
                } else {
                    current.add(b)
                }
            }
            flush()
            return out
        }

        fun load(file: File): Tokenizer {
            require(file.exists()) { "no tokenizer at ${file.path}" }
            val lines = file.readLines()
            require(lines.isNotEmpty() && lines[0] == "vorv-bpe $VERSION") {
                "not a Vorv tokenizer (header: ${lines.firstOrNull()})"
            }
            val name = lines.getOrNull(1)?.removePrefix("name ") ?: "vorv-bpe"
            val merges = ArrayList<Pair<Int, Int>>()
            for (line in lines.drop(2)) {
                if (line.isBlank()) continue
                val parts = line.trim().split(" ")
                require(parts.size == 2) { "bad merge line: $line" }
                val a = parts[0].toInt()
                val b = parts[1].toInt()
                require(a in 0 until FIRST_MERGE + merges.size && b in 0 until FIRST_MERGE + merges.size) {
                    "merge $line refers to a token that does not exist yet"
                }
                merges.add(a to b)
            }
            return Tokenizer(merges, name)
        }
    }

    val vocabSize: Int get() = FIRST_MERGE + merges.size

    private val ranks = HashMap<Long, Int>().apply {
        merges.forEachIndexed { i, (a, b) -> put((a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL), i) }
    }

    /** UTF-8 bytes of [text] after the learned merges, with an EOS appended. */
    fun encode(text: String, addEos: Boolean = true): IntArray {
        val ids = ArrayList<Int>()
        for (chunk in splitWords(text)) {
            val seq = chunk.toMutableList()
            while (seq.size > 1) {
                var bestRank = Int.MAX_VALUE
                var bestAt = -1
                for (i in 0 until seq.size - 1) {
                    val r = ranks[(seq[i].toLong() shl 32) or (seq[i + 1].toLong() and 0xFFFFFFFFL)]
                    if (r != null && r < bestRank) {
                        bestRank = r
                        bestAt = i
                    }
                }
                if (bestAt < 0) break
                seq[bestAt] = FIRST_MERGE + bestRank
                seq.removeAt(bestAt + 1)
            }
            ids.addAll(seq)
        }
        if (addEos) ids.add(EOS)
        return ids.toIntArray()
    }

    /** Inverse of [encode]; EOS and anything outside the byte range are skipped. */
    fun decode(ids: IntArray): String {
        val bytes = ArrayList<Byte>(ids.size * 2)
        val stack = ArrayList<Int>()
        for (id in ids) {
            stack.add(id)
            while (stack.isNotEmpty()) {
                val next = stack.removeAt(stack.size - 1)
                if (next < 256) {
                    bytes.add(next.toByte())
                } else if (next != EOS) {
                    val index = next - FIRST_MERGE
                    require(index in merges.indices) {
                        "token $next is not in this vocabulary (size $vocabSize)"
                    }
                    val (a, b) = merges[index]
                    stack.add(b)
                    stack.add(a)
                }
            }
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    fun save(file: File) {
        val body = buildString {
            append("vorv-bpe $VERSION\n")
            append("name $name\n")
            for ((a, b) in merges) append("$a $b\n")
        }
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(body)
        if (file.exists() && !file.delete()) throw java.io.IOException("cannot replace ${file.path}")
        if (!part.renameTo(file)) throw java.io.IOException("cannot rename ${part.path}")
    }
}
