package host.vorv.project

/**
 * Ranks chunks for a task. Four signals, all cheap and all explainable:
 * BM25-ish term overlap, embedding similarity, path/symbol name proximity to
 * the words in the task, and recency of the file.
 *
 * The score is fitted by hand rather than learned: a ranking an owner cannot
 * inspect is a ranking nobody can debug when the wrong file comes back.
 */
class Retriever(private val embedder: Embedder = HashedEmbedder()) {

    fun retrieve(snapshot: IndexSnapshot, task: String, budgetTokens: Int, limit: Int = 12): List<Hit> {
        if (task.isBlank() || snapshot.chunks.isEmpty()) return emptyList()
        val terms = terms(task)
        if (terms.isEmpty()) return emptyList()
        val qv = embedder.embed(task)
        val newest = snapshot.files.maxOfOrNull { it.mtime } ?: 0L
        val hits = snapshot.chunks.map { c ->
            val hay = c.text.lowercase()
            val path = c.path.lowercase()
            var tf = 0
            for (t in terms) {
                var i = hay.indexOf(t)
                while (i >= 0) { tf++; i = hay.indexOf(t, i + t.length) }
            }
            val bm25 = tf.toDouble() / (tf + 1.2)
            val pathHits = terms.count { path.contains(it) }
            val cos = cosine(qv, c.vector)
            val file = snapshot.files.firstOrNull { it.path == c.path }
            val recent = if (file != null && newest > 0) file.mtime.toDouble() / newest else 0.0
            val score = 3.0 * bm25 + 4.0 * cos + 2.0 * pathHits + 0.1 * recent
            val why = buildString {
                append("terms=").append(tf)
                append(" cos=").append(String.format(java.util.Locale.US, "%.2f", cos))
                if (pathHits > 0) append(" path=").append(pathHits)
            }
            Hit(c, score, why)
        }
        var used = 0
        val out = mutableListOf<Hit>()
        for (h in hits.sortedByDescending { it.score }) {
            if (h.score <= 0.0) continue
            if (used + h.chunk.tokens > budgetTokens) continue
            out += h
            used += h.chunk.tokens
            if (out.size >= limit) break
        }
        return out
    }

    internal fun terms(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .filter { it.length > 2 && it !in STOP }
            .distinct()
            .take(24)

    private companion object {
        val STOP = setOf(
            "the", "and", "for", "that", "this", "with", "from", "into", "your", "you",
            "are", "was", "were", "have", "has", "had", "not", "but", "its", "it's",
            "add", "make", "use", "can", "should", "would", "could", "when", "then",
        )
    }
}

/**
 * The repository outline every prompt starts with: directories, and the
 * declarations in their top-level files. Never the file list, which is the
 * thing that stops fitting at a thousand files.
 */
object RepoMap {
    fun build(snapshot: IndexSnapshot, budgetTokens: Int, maxFilesPerDir: Int = 6): String {
        if (snapshot.files.isEmpty()) return "(empty workspace)"
        val byDir = snapshot.files.groupBy { it.path.substringBeforeLast('/', "") }
        val sb = StringBuilder()
        var used = 0
        for ((dir, files) in byDir.entries.sortedBy { it.key }) {
            val head = if (dir.isEmpty()) "./" else "$dir/"
            val line = "## $head (${files.size} files)\n"
            if (used + line.length / 4 > budgetTokens) break
            sb.append(line)
            used += line.length / 4
            for (f in files.sortedBy { it.path }.take(maxFilesPerDir)) {
                val names = f.symbols
                    .filter { it.kind != Symbol.Kind.IMPORT && it.kind != Symbol.Kind.KEY && it.kind != Symbol.Kind.HEADING }
                    .map { it.name }
                    .distinct()
                    .take(8)
                val base = f.path.substringAfterLast('/')
                val entry = "  - $base" + if (names.isEmpty()) "" else ": " + names.joinToString(", ")
                if (used + entry.length / 4 > budgetTokens) break
                sb.append(entry).append('\n')
                used += entry.length / 4
            }
            if (files.size > maxFilesPerDir) {
                sb.append("  … and ").append(files.size - maxFilesPerDir).append(" more\n")
            }
        }
        return sb.toString().trimEnd()
    }
}

/**
 * What the agent remembers about a project between sessions: how to build it,
 * how to test it, and the conventions it was told or discovered.
 */
data class ProjectRecord(
    val root: String,
    val name: String,
    val buildCommand: String?,
    val testCommand: String?,
    val conventions: List<String> = emptyList(),
    val lastPlan: String? = null,
) {
    companion object {
        /**
         * Commands come from the tree, not from a hardcoded Android assumption:
         * a Gradle wrapper, an npm script and a Python project each have their
         * own answer, and a project with none of them must not inherit one.
         */
        fun discover(root: String, paths: Collection<String>, conventions: List<String> = emptyList()): ProjectRecord {
            val set = paths.map { it.removePrefix("/") }.toSet()
            val name = root.trimEnd('/').substringAfterLast('/').ifEmpty { "workspace" }
            val build: String?
            val test: String?
            when {
                "gradlew" in set || set.any { it.endsWith(".gradle.kts") || it.endsWith(".gradle") } -> {
                    val wrapper = if ("gradlew" in set) "./gradlew" else "gradle"
                    build = "$wrapper assembleDebug"
                    test = "$wrapper test"
                }
                "package.json" in set -> {
                    build = "npm run build"
                    test = "npm test"
                }
                "pyproject.toml" in set || "setup.py" in set -> {
                    build = "python -m build"
                    test = "python -m pytest"
                }
                else -> { build = null; test = null }
            }
            return ProjectRecord(root, name, build, test, conventions, null)
        }
    }
}
