package host.vorv.project

import java.security.MessageDigest

/**
 * Walks a workspace and produces an index it can rebuild for milliseconds.
 *
 * Two decisions matter. First, a re-index reads only files whose (size, mtime,
 * first-4KB hash) changed; everything else is reused by value, so editing one
 * file re-parses one file. Second, the walk is bounded by the storage caps in
 * the plan — skipping a file is recorded with the reason, never silent.
 */
class ProjectIndexer(
    private val source: FileSource,
    private val embedder: Embedder = HashedEmbedder(),
) {

    fun build(previous: IndexSnapshot? = null): IndexSnapshot {
        val metas = source.list().sortedBy { it.path }
        val ignores = Ignores.from(source)
        val prior = previous?.files?.associateBy { it.path }.orEmpty()
        val files = mutableListOf<IndexedFile>()
        val skipped = mutableMapOf<String, String>()
        var totalChunks = 0

        for (meta in metas) {
            val ignored = ignores.reason(meta.path)
            if (ignored != null) { skipped[meta.path] = ignored; continue }
            if (meta.size > MAX_FILE_BYTES) { skipped[meta.path] = "larger than ${MAX_FILE_BYTES / 1024} KB"; continue }
            if (files.size >= MAX_FILES) { skipped[meta.path] = "file cap reached"; continue }

            val old = prior[meta.path]
            val head = source.readHead(meta, 4096)
            if (head == null) { skipped[meta.path] = "unreadable"; continue }
            if (head.any { it == 0.toByte() }) { skipped[meta.path] = "binary"; continue }
            val fp = fingerprint(meta, head)

            if (old != null && old.fingerprint == fp) { files += old; continue }

            val text = source.read(meta)
            if (text == null) { skipped[meta.path] = "unreadable"; continue }
            val symbols = SymbolExtractor.extract(meta.path, text)
            val all = Chunker.chunk(meta.path, text, symbols, embedder)
            val room = MAX_CHUNKS - totalChunks
            val chunks = if (all.size > room) all.take(room) else all
            if (all.size > room) skipped[meta.path] = "chunk cap reached"
            totalChunks += chunks.size
            files += IndexedFile(
                path = meta.path,
                size = meta.size,
                mtime = meta.mtime,
                fingerprint = fp,
                lang = SymbolExtractor.languageOf(meta.path),
                lines = text.count { it == '\n' } + if (text.endsWith("\n") || text.isEmpty()) 0 else 1,
                symbols = symbols,
                chunks = chunks,
            )
        }
        return IndexSnapshot(
            revision = (previous?.revision ?: 0) + 1,
            files = files,
            skipped = skipped,
        )
    }

    private fun fingerprint(meta: FileMeta, head: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(head)
        md.update("|${meta.size}|${meta.mtime}".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(20)
    }

    companion object {
        const val MAX_FILE_BYTES = 512L * 1024
        const val MAX_FILES = 20_000
        const val MAX_CHUNKS = 50_000
    }
}

/**
 * The ignore rules the plan names, plus whatever `.gitignore` says.
 *
 * `.gitignore` support is deliberately small — comments, negations, directory
 * rules and `*` globs — because a half-implemented git matcher is worse than a
 * documented subset, and the generated-file directories are excluded by name
 * regardless of what a repository's ignore file says.
 */
class Ignores private constructor(
    private val dirNames: Set<String>,
    private val patterns: List<Regex>,
    private val dirPatterns: List<Regex>,
    private val negations: List<Regex>,
) {
    /** Non-null when the path must not be indexed, holding the reason. */
    fun reason(path: String): String? {
        val segments = path.split('/')
        for (s in segments.dropLast(1)) {
            if (s in dirNames) return "ignored directory: $s"
            if (dirPatterns.any { it.matches(s) }) return "ignored directory: $s"
        }
        val normalised = path.removePrefix("./")
        if (negations.any { it.matches(normalised) }) return null
        for (p in patterns) if (p.matches(normalised) || p.matches(normalised.substringAfterLast('/'))) {
            return "ignored by .gitignore"
        }
        return null
    }

    companion object {
        val ALWAYS = setOf(".git", "build", "node_modules", ".gradle", ".idea", "dist", "__pycache__", ".venv", "venv")

        fun from(source: FileSource): Ignores {
            // read through readHead: an ignore file is small, and it must not
            // count as a re-parse of the project on an incremental index
            val gitignore = source.list().firstOrNull { it.path == ".gitignore" }
                ?.let { source.readHead(it, 64 * 1024) }
                ?.toString(Charsets.UTF_8)
                .orEmpty()
            val patterns = mutableListOf<Regex>()
            val dirPatterns = mutableListOf<Regex>()
            val negations = mutableListOf<Regex>()
            for (raw in gitignore.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val negated = line.startsWith("!")
                val isDir = line.endsWith("/")
                val body = line.removePrefix("!").removePrefix("/").removeSuffix("/")
                if (body.isEmpty()) continue
                val regex = glob(body)
                when {
                    negated -> negations += regex
                    isDir -> dirPatterns += regex
                    else -> patterns += regex
                }
            }
            return Ignores(ALWAYS, patterns, dirPatterns, negations)
        }

        private fun glob(body: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < body.length) {
                when {
                    body.startsWith("**", i) -> { sb.append(".*"); i += 2 }
                    body[i] == '*' -> { sb.append("[^/]*"); i++ }
                    body[i] == '?' -> { sb.append("[^/]"); i++ }
                    else -> { sb.append(Regex.escape(body[i].toString())); i++ }
                }
            }
            return Regex(sb.toString())
        }
    }
}
