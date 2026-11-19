package host.vorv.project

/** A file as the walker sees it: metadata only, no bytes yet. */
data class FileMeta(val path: String, val size: Long, val mtime: Long)

/**
 * Where the indexer reads from.
 *
 * The indexer never touches Android: the SAF implementation lives in
 * `ProjectSources.kt` and a map-backed one serves the tests, so the ranking
 * rules are testable on a laptop and the walk is testable without a device.
 */
interface FileSource {
    fun list(): List<FileMeta>

    /** File text, or null when the file is binary or over the size cap. */
    fun read(meta: FileMeta): String?

    /**
     * The first [bytes] bytes, for the change fingerprint. Reading 4 KB must
     * not cost a full file read, or a re-index of 10,000 files would be as
     * expensive as the first one.
     */
    fun readHead(meta: FileMeta, bytes: Int): ByteArray? =
        read(meta)?.toByteArray(Charsets.UTF_8)?.let { if (it.size <= bytes) it else it.copyOf(bytes) }
}

/** In-memory source for tests and for re-indexing an export. */
class MapFileSource(private val files: Map<String, String>, private val mtimes: Map<String, Long> = emptyMap()) : FileSource {
    /** Full reads, so a test can assert that one edit re-parses one file. */
    var reads = 0
        private set

    override fun list(): List<FileMeta> = files.map { (path, text) ->
        FileMeta(path, text.toByteArray().size.toLong(), mtimes[path] ?: 1L)
    }.sortedBy { it.path }

    override fun read(meta: FileMeta): String? {
        reads++
        return files[meta.path]
    }

    override fun readHead(meta: FileMeta, bytes: Int): ByteArray? {
        val text = files[meta.path] ?: return null
        val all = text.toByteArray(Charsets.UTF_8)
        return if (all.size <= bytes) all else all.copyOf(bytes)
    }
}

data class IndexedFile(
    val path: String,
    val size: Long,
    val mtime: Long,
    val fingerprint: String,
    val lang: SymbolExtractor.Language,
    val lines: Int,
    val symbols: List<Symbol>,
    val chunks: List<Chunk>,
)

/** A retrievable slice of a file, aligned to a declaration where possible. */
data class Chunk(
    val id: String,
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val tokens: Int,
    val vector: FloatArray,
) {
    override fun equals(other: Any?): Boolean = other is Chunk && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

data class IndexSnapshot(
    val revision: Int,
    val files: List<IndexedFile>,
    val skipped: Map<String, String> = emptyMap(),
    val builtAt: Long = System.currentTimeMillis(),
    /** Where the walk started, so the prompt can name the project it describes. */
    val root: String? = null,
) {
    val chunks: List<Chunk> get() = files.flatMap { it.chunks }
    val symbols: List<Symbol> get() = files.flatMap { it.symbols }
}

/** A scored chunk, with the reasons kept so an answer can be traced. */
data class Hit(val chunk: Chunk, val score: Double, val why: String)

/**
 * Splits a file into symbol-aligned, overlapping windows.
 *
 * 400 tokens at ~4 characters per token is the working budget: small enough
 * that eight chunks plus a repo map fit a 4k context, large enough that a
 * 400-line file arrives whole in three reads instead of thirty.
 */
object Chunker {
    const val CHUNK_TOKENS = 400
    private const val OVERLAP = 0.20
    private const val CHARS_PER_TOKEN = 4

    fun chunk(path: String, text: String, symbols: List<Symbol>, embedder: Embedder): List<Chunk> {
        val lines = text.lines()
        if (lines.isEmpty() || text.isBlank()) return emptyList()
        val windows = mutableListOf<IntRange>()

        val blocks = symbols
            .filter { it.kind != Symbol.Kind.IMPORT && it.startLine <= lines.size }
            .sortedBy { it.startLine }
            .map { it.startLine..minOf(it.endLine, lines.size) }
            .filter { it.first <= it.last }

        var cursor = 1
        for (b in blocks) {
            // a nested declaration is already inside its enclosing block's window
            if (b.last < cursor) continue
            if (b.first > cursor) windows += cursor..(b.first - 1)
            windows += b
            cursor = b.last + 1
        }
        if (cursor <= lines.size) windows += cursor..lines.size

        val out = mutableListOf<Chunk>()
        val maxLines = maxOf(1, CHUNK_TOKENS * CHARS_PER_TOKEN / 40)
        for (w in windows) {
            if (w.first > w.last) continue
            var start = w.first
            while (start <= w.last) {
                var end = minOf(w.last, start + maxLines - 1)
                // never open a window wider than the token budget
                while (end > start && (end - start + 1) * 40 / CHARS_PER_TOKEN > CHUNK_TOKENS) end--
                val slice = lines.subList(start - 1, end).joinToString("\n")
                if (slice.isNotBlank()) {
                    out += Chunk(
                        id = "$path#$start-$end",
                        path = path,
                        startLine = start,
                        endLine = end,
                        text = slice,
                        tokens = maxOf(1, slice.length / CHARS_PER_TOKEN),
                        vector = embedder.embed(slice),
                    )
                }
                if (end >= w.last) break
                val overlap = maxOf(1, ((end - start + 1) * OVERLAP).toInt())
                start = maxOf(start + 1, end - overlap + 1)
            }
        }
        return out
    }
}

/** Deterministic text vector. v1 hashes token unigrams and bigrams; no download. */
interface Embedder {
    val dimensions: Int
    fun embed(text: String): FloatArray
}

class HashedEmbedder(override val dimensions: Int = 256) : Embedder {
    override fun embed(text: String): FloatArray {
        val v = FloatArray(dimensions)
        val words = text.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length > 1 }
        var prev: String? = null
        for (w in words) {
            add(v, w, 1.0f)
            if (prev != null) add(v, "$prev $w", 0.5f)
            prev = w
        }
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        val n = kotlin.math.sqrt(norm).toFloat()
        if (n > 0f) for (i in v.indices) v[i] /= n
        return v
    }

    private fun add(v: FloatArray, term: String, weight: Float) {
        var h = -0x340d631b
        for (c in term) h = h * 31 + c.code
        val idx = Math.floorMod(h, dimensions)
        v[idx] += weight
    }
}

/** Cosine similarity of two L2-normalised vectors. */
fun cosine(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0
    for (i in 0 until minOf(a.size, b.size)) dot += a[i].toDouble() * b[i]
    return dot
}
