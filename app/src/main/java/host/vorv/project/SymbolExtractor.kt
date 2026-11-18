package host.vorv.project

/** One declaration found in a file, with the lines it occupies. */
data class Symbol(
    val path: String,
    val kind: Kind,
    val name: String,
    val signature: String,
    val startLine: Int,
    val endLine: Int,
    val packageName: String?,
) {
    enum class Kind { CLASS, OBJECT, INTERFACE, ENUM, FUNCTION, PROPERTY, IMPORT, TASK, KEY, HEADING }

    val lines: IntRange get() = startLine..endLine
}

/**
 * Declaration extraction without a compiler.
 *
 * Two rules keep this honest: line numbers are 1-based and inclusive, and an
 * extractor that cannot find a block's end reports the declaration line rather
 * than a guessed range. The index is a ranking hint, never an authority; the
 * parser is unit-tested on fixtures so a mistaken range is caught there.
 */
object SymbolExtractor {

    fun extract(path: String, text: String): List<Symbol> = when (languageOf(path)) {
        Language.KOTLIN, Language.JAVA -> kotlinLike(path, text)
        Language.PYTHON -> python(path, text)
        Language.JS -> jsLike(path, text)
        Language.GRADLE -> braceDeclarations(path, text, extra = TASKS)
        Language.XML -> headings(path, text, Symbol.Kind.KEY, Regex("""^\s*<([A-Za-z_][\w.\-]*)"""), 1)
        Language.JSON, Language.YAML -> headings(path, text, Symbol.Kind.KEY, KEY_LINE, 1)
        Language.MARKDOWN -> headings(path, text, Symbol.Kind.HEADING, Regex("""^(#{1,6})\s+(.+)$"""), 2)
        Language.OTHER -> emptyList()
    }

    enum class Language { KOTLIN, JAVA, PYTHON, JS, GRADLE, XML, JSON, YAML, MARKDOWN, OTHER }

    fun languageOf(path: String): Language = when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> if (path.endsWith(".gradle.kts")) Language.GRADLE else Language.KOTLIN
        "java" -> Language.JAVA
        "py" -> Language.PYTHON
        "js", "jsx", "ts", "tsx", "mjs", "cjs" -> Language.JS
        "gradle" -> Language.GRADLE
        "xml", "html" -> Language.XML
        "json" -> Language.JSON
        "yaml", "yml" -> Language.YAML
        "md", "markdown" -> Language.MARKDOWN
        else -> Language.OTHER
    }

    // ---- Kotlin / Java -----------------------------------------------------

    private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val IMPORT_K = Regex("""^\s*import\s+([\w.*`]+)""")
    private val TYPE_K = Regex("""(?:^|\s)(?:data\s+|sealed\s+|abstract\s+|open\s+|internal\s+|private\s+|public\s+|value\s+)*(class|object|interface|enum\s+class)\s+([A-Za-z_]\w*)""")
    private val FUN_K = Regex("""(?:^|\s)(?:suspend\s+|inline\s+|private\s+|internal\s+|public\s+|override\s+|open\s+|operator\s+|infix\s+|tailrec\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)\s*\(""")
    private val PROP_K = Regex("""(?:^|\s)(?:private\s+|internal\s+|public\s+|override\s+|const\s+|lateinit\s+)*(val|var)\s+([A-Za-z_]\w*)""")

    private fun kotlinLike(path: String, text: String): List<Symbol> {
        val clean = blankComments(text)
        val lines = clean.lines()
        val pkg = PACKAGE.find(text)?.groupValues?.get(1)
        val out = mutableListOf<Symbol>()

        defs(lines).forEach { (lineIdx, kind, name) ->
            val start = lineIdx + 1
            val end = blockEnd(lines, lineIdx)
            var signature = lines[lineIdx].trim()
            if (signature.endsWith("{") || signature.endsWith("(") || signature.endsWith(",")) {
                signature = signature.trimEnd('{', '(', ',', ' ').trim()
            }
            out += Symbol(path, kind, name, signature.take(240), start, end, pkg)
        }

        // imports are declarations too: a Java file's only real dependency is
        // often the import, and the retriever leans on them for proximity.
        lines.forEachIndexed { i, l ->
            IMPORT_K.matchEntire(l)?.let {
                out += Symbol(path, Symbol.Kind.IMPORT, it.groupValues[1], l.trim(), i + 1, i + 1, pkg)
            }
        }
        return out
    }

    /** (0-based line, kind, name) for each declaration in [lines]. */
    private fun defs(lines: List<String>): List<Triple<Int, Symbol.Kind, String>> {
        val out = mutableListOf<Triple<Int, Symbol.Kind, String>>()
        lines.forEachIndexed { i, line ->
            if (line.isBlank()) return@forEachIndexed
            val t = TYPE_K.find(line)
            if (t != null) {
                val kind = when {
                    t.groupValues[1].startsWith("object") -> Symbol.Kind.OBJECT
                    t.groupValues[1].startsWith("interface") -> Symbol.Kind.INTERFACE
                    t.groupValues[1].startsWith("enum") -> Symbol.Kind.ENUM
                    else -> Symbol.Kind.CLASS
                }
                out += Triple(i, kind, t.groupValues[2])
                return@forEachIndexed
            }
            val f = FUN_K.find(line)
            if (f != null) {
                out += Triple(i, Symbol.Kind.FUNCTION, f.groupValues[1])
                return@forEachIndexed
            }
            // properties only where they are top-level-ish: a `val` inside an
            // expression is noise, and every local variable would drown the map.
            val p = PROP_K.find(line)
            if (p != null && line.trimStart().startsWith(p.groupValues[0].trimStart())) {
                out += Triple(i, Symbol.Kind.PROPERTY, p.groupValues[2])
            }
        }
        return out
    }

    /**
     * Brace matching from [start]. A declaration without a body (an abstract
     * function, an expression-bodied `fun x() = …`) ends on its own line.
     */
    private fun blockEnd(lines: List<String>, start: Int): Int {
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            var inString = false
            var escape = false
            for (ch in lines[i]) {
                if (escape) { escape = false; continue }
                if (ch == '\\' && inString) { escape = true; continue }
                if (ch == '"') { inString = !inString; continue }
                if (inString) continue
                if (ch == '{') { depth++; opened = true }
                if (ch == '}') {
                    depth--
                    if (opened && depth == 0) return i + 1
                    if (depth < 0) return i + 1
                }
            }
            if (!opened && i == start) {
                // a declaration whose head does not end in "(" or "," is one line
                val head = lines[start].trimEnd()
                val continues = head.endsWith("(") || head.endsWith(",") || head.endsWith("->")
                if (!continues) return start + 1
            }
        }
        return start + 1
    }

    // ---- Python ------------------------------------------------------------

    private fun python(path: String, text: String): List<Symbol> {
        val lines = text.lines()
        val out = mutableListOf<Symbol>()
        for (i in lines.indices) {
            val line = lines[i]
            val m = Regex("""^(\s*)(?:async\s+)?(class|def)\s+([A-Za-z_]\w*)""").find(line) ?: continue
            // decorators belong to the declaration they decorate
            val indent = m.groupValues[1].length
            val name = m.groupValues[3]
            var end = i + 1
            var j = i + 1
            while (j < lines.size) {
                val l = lines[j]
                if (l.isBlank()) { j++; continue }
                val ind = l.length - l.trimStart().length
                if (ind <= indent) break
                end = j + 1
                j++
            }
            out += Symbol(
                path,
                if (m.groupValues[2] == "class") Symbol.Kind.CLASS else Symbol.Kind.FUNCTION,
                name,
                line.trim().take(240),
                i + 1,
                maxOf(end, i + 1),
                null,
            )
        }
        return out
    }

    // ---- JS / TS -----------------------------------------------------------

    private val JS_DECL = Regex("""(?:^|\s)(?:export\s+)?(?:default\s+)?(?:async\s+)?(class|function)\s+([A-Za-z_$][\w$]*)""")
    private val JS_CONST = Regex("""(?:^|\s)(?:export\s+)?const\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>""")

    private fun jsLike(path: String, text: String): List<Symbol> {
        val clean = blankComments(text)
        val lines = clean.lines()
        val out = mutableListOf<Symbol>()
        lines.forEachIndexed { i, line ->
            val d = JS_DECL.find(line)
            val c = JS_CONST.find(line)
            when {
                d != null -> out += Symbol(
                    path,
                    if (d.groupValues[1] == "class") Symbol.Kind.CLASS else Symbol.Kind.FUNCTION,
                    d.groupValues[2], line.trim().take(240), i + 1, blockEnd(lines, i), null,
                )
                c != null -> out += Symbol(
                    path, Symbol.Kind.FUNCTION, c.groupValues[1], line.trim().take(240),
                    i + 1, blockEnd(lines, i), null,
                )
            }
        }
        return out
    }

    // ---- Gradle ------------------------------------------------------------

    private val TASKS = Regex("""(?:^|\s)(?:tasks\.)?(?:register|create|named)\s*[(<]?\s*["']([\w.:\-]+)["']""")

    private fun braceDeclarations(path: String, text: String, extra: Regex): List<Symbol> {
        val clean = blankComments(text)
        val lines = clean.lines()
        val out = mutableListOf<Symbol>()
        lines.forEachIndexed { i, line ->
            val e = extra.find(line) ?: return@forEachIndexed
            out += Symbol(path, Symbol.Kind.TASK, e.groupValues[1], line.trim().take(240), i + 1, blockEnd(lines, i), null)
        }
        return out
    }

    // ---- headings / keys ---------------------------------------------------

    private val KEY_LINE = Regex("""^\s*"?([A-Za-z_][\w.\-]*)"?\s*:""")

    private fun headings(path: String, text: String, kind: Symbol.Kind, pattern: Regex, group: Int): List<Symbol> {
        val out = mutableListOf<Symbol>()
        text.lines().forEachIndexed { i, line ->
            val m = pattern.find(line) ?: return@forEachIndexed
            val name = m.groupValues.getOrNull(group)?.trim()?.take(120) ?: return@forEachIndexed
            if (name.isEmpty()) return@forEachIndexed
            out += Symbol(path, kind, name, line.trim().take(240), i + 1, i + 1, null)
        }
        return out
    }

    /**
     * Replaces comment text with spaces, keeping newlines and offsets intact so
     * a line number computed afterwards is still the real one.
     */
    internal fun blankComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        var inChar = false
        var inRaw = false
        var escape = false
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            if (inBlock) {
                if (c == '*' && next == '/') { out.append("  "); i += 2; inBlock = false; continue }
                out.append(if (c == '\n') '\n' else ' '); i++; continue
            }
            if (inLine) {
                if (c == '\n') { inLine = false; out.append('\n') } else out.append(' ')
                i++; continue
            }
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                out.append(c); i++; continue
            }
            if (inChar) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '\'') inChar = false
                out.append(c); i++; continue
            }
            if (inRaw) {
                if (c == '"' && text.startsWith("\"\"\"", i)) { inRaw = false; out.append("\"\"\""); i += 3; continue }
                out.append(c); i++; continue
            }
            when {
                c == '/' && next == '/' -> { inLine = true; out.append("  "); i += 2 }
                c == '/' && next == '*' -> { inBlock = true; out.append("  "); i += 2 }
                c == '"' && text.startsWith("\"\"\"", i) -> { inRaw = true; out.append("\"\"\""); i += 3 }
                c == '"' -> { inString = true; out.append(c); i++ }
                c == '\'' -> { inChar = true; out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
