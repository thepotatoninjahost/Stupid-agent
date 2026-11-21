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
 *
 * Two rules the earlier version did not keep. The token budget is enforced in
 * characters (four per token) instead of integer-divided tokens, so a map can
 * not quietly overrun it. And a cut map SAYS it was cut: a truncated outline
 * that looks complete is the same class of lie as a green build that failed.
 */
object RepoMap {

    @Volatile private var cachedRevision = Int.MIN_VALUE
    @Volatile private var cachedBudget = -1
    @Volatile private var cachedMap: String? = null

    /** Cached per index revision and budget: built once per request, not per message. */
    fun map(snapshot: IndexSnapshot, budgetTokens: Int, maxFilesPerDir: Int = 6): String {
        val cached = cachedMap
        if (cached != null && snapshot.revision == cachedRevision &&
            budgetTokens == cachedBudget && maxFilesPerDir == CACHED_MAX_FILES
        ) return cached
        val text = build(snapshot, budgetTokens, maxFilesPerDir)
        cachedRevision = snapshot.revision
        cachedBudget = budgetTokens
        CACHED_MAX_FILES = maxFilesPerDir
        cachedMap = text
        return text
    }

    fun build(snapshot: IndexSnapshot, budgetTokens: Int, maxFilesPerDir: Int = 6): String {
        if (snapshot.files.isEmpty()) return "(empty workspace)"
        val note = "… map cut at the token budget; ask for a directory to see the rest."
        val budgetChars = budgetTokens * CHARS_PER_TOKEN
        // Reserve the marker's room from the start: a cut that cannot say it was
        // cut is the failure this class exists to prevent.
        val contentBudget = maxOf(0, budgetChars - note.length - 1)
        val byDir = snapshot.files.groupBy { it.path.substringBeforeLast('/', "") }
        val sb = StringBuilder()
        var used = 0
        var cut = false
        for ((dir, files) in byDir.entries.sortedBy { it.key }) {
            val head = if (dir.isEmpty()) "./" else "$dir/"
            val line = "## $head (${files.size} files)\n"
            if (used + line.length > contentBudget) { cut = true; break }
            sb.append(line)
            used += line.length
            for (f in files.sortedBy { it.path }.take(maxFilesPerDir)) {
                val names = f.symbols
                    .filter { it.kind != Symbol.Kind.IMPORT && it.kind != Symbol.Kind.KEY && it.kind != Symbol.Kind.HEADING }
                    .map { it.name }
                    .distinct()
                    .take(MAX_SYMBOLS)
                val base = f.path.substringAfterLast('/')
                val entry = "  - $base" + if (names.isEmpty()) " (no declarations found)" else ": " + names.joinToString(", ")
                if (used + entry.length > contentBudget) { cut = true; break }
                sb.append(entry).append('\n')
                used += entry.length
            }
            if (files.size > maxFilesPerDir) {
                val more = "  … and ${files.size - maxFilesPerDir} more\n"
                if (used + more.length > contentBudget) { cut = true; break }
                sb.append(more)
                used += more.length
            }
            if (cut) break
        }
        val out = sb.toString().trimEnd()
        if (!cut) return out
        return "$out\n$note"
    }

    @Volatile private var CACHED_MAX_FILES = -1

    private const val CHARS_PER_TOKEN = 4
    private const val MAX_SYMBOLS = 8
}

/**
 * What the agent remembers about a project between sessions (plan Step 7, item
 * 6): how to build it, how to test it, the conventions it was told or found, and
 * the plan it is part-way through.
 *
 * Discovery reads file names only — nothing is executed — so it is safe on a
 * phone. A command that was not found stays null and the screen says "not found"
 * instead of inventing one that would fail later.
 */
data class ProjectRecord(
    val root: String,
    val name: String,
    val buildCommand: String?,
    val testCommand: String?,
    val lintCommand: String? = null,
    val conventions: List<String> = emptyList(),
    val lastPlan: String? = null,
) {
    fun summary(): String = buildString {
        append("Project ").append(name).append(" at ").append(root)
        append("\nbuild: ").append(buildCommand ?: "not found")
        append("\ntest: ").append(testCommand ?: "not found")
        append("\nlint: ").append(lintCommand ?: "not found")
        if (conventions.isNotEmpty()) append("\nconventions: ").append(conventions.joinToString("; "))
        lastPlan?.let { append("\nplan: ").append(it) }
    }

    companion object {
        const val FILE_NAME = "project-record.tsv"

        /**
         * Commands come from the tree, not from a hardcoded Android assumption:
         * a Gradle wrapper, an npm script and a Python project each have their
         * own answer, and a project with none of them must not inherit one.
         */
        fun discover(root: String, paths: Collection<String>, conventions: List<String> = emptyList()): ProjectRecord {
            val set = paths.map { it.replace('\\', '/').removePrefix("./").removePrefix("/") }.toSet()
            val gradle = set.firstOrNull { it.endsWith("build.gradle.kts") || it.endsWith("build.gradle") }
            val gradleDir = gradle?.substringBeforeLast('/', "")
                ?.takeIf { it.isNotEmpty() }
            val wrapper = when {
                "gradlew" in set -> "./gradlew"
                gradleDir != null && "$gradleDir/gradlew" in set -> "./$gradleDir/gradlew"
                else -> null
            }
            val name = gradleDir?.substringAfterLast('/')
                ?: root.trimEnd('/').substringAfterLast('/').ifEmpty { "workspace" }
            val build: String?
            val test: String?
            val lint: String?
            when {
                gradle != null -> {
                    val tool = wrapper ?: "gradle"
                    build = "$tool assembleDebug"
                    test = "$tool test"
                    lint = "$tool lint"
                }
                "package.json" in set -> {
                    build = "npm run build"
                    test = "npm test"
                    lint = "npm run lint"
                }
                "pyproject.toml" in set || "setup.py" in set -> {
                    build = "python3 -m build"
                    test = "python3 -m pytest"
                    lint = "python3 -m ruff check ."
                }
                "Makefile" in set -> {
                    build = "make"
                    test = "make test"
                    lint = null
                }
                else -> { build = null; test = null; lint = null }
            }
            val found = buildList {
                addAll(conventions)
                if (gradle != null) add("Kotlin/Java project built with Gradle")
                if ("package.json" in set) add("JavaScript project using npm")
                if ("pyproject.toml" in set || "setup.py" in set) add("Python project")
                if (set.any { it.endsWith(".kt") }) add("Kotlin sources")
                if ("AGENTS.md" in set) add("AGENTS.md is the project's own instruction file and is honoured")
            }.distinct()
            return ProjectRecord(root, name, build, test, lint, found, null)
        }

        /** Written once per project; a failure is the caller's to report, never swallowed. */
        fun save(record: ProjectRecord, directory: java.io.File): java.io.File {
            val file = java.io.File(directory, FILE_NAME)
            val text = buildString {
                append("root\t").append(oneLine(record.root)).append('\n')
                append("name\t").append(oneLine(record.name)).append('\n')
                append("build\t").append(oneLine(record.buildCommand ?: "")).append('\n')
                append("test\t").append(oneLine(record.testCommand ?: "")).append('\n')
                append("lint\t").append(oneLine(record.lintCommand ?: "")).append('\n')
                record.conventions.forEach { append("convention\t").append(oneLine(it)).append('\n') }
                record.lastPlan?.let { append("plan\t").append(oneLine(it)).append('\n') }
            }
            file.writeText(text)
            return file
        }

        fun load(directory: java.io.File): ProjectRecord? {
            val file = java.io.File(directory, FILE_NAME)
            if (!file.isFile) return null
            val rows = file.readLines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split('\t')
                parts[0] to (parts.getOrNull(1) ?: "")
            }
            fun first(key: String): String? = rows.firstOrNull { it.first == key }?.second?.takeIf { it.isNotEmpty() }
            return ProjectRecord(
                root = first("root") ?: return null,
                name = first("name") ?: "",
                buildCommand = first("build"),
                testCommand = first("test"),
                lintCommand = first("lint"),
                conventions = rows.filter { it.first == "convention" }.map { it.second },
                lastPlan = first("plan"),
            )
        }

        /** A tab or newline in a value would break the file; collapse it instead. */
        private fun oneLine(text: String): String = text.replace('\t', ' ').replace('\n', ' ').trim()
    }
}
