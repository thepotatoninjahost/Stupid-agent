package host.vorv.forge

import java.io.File

/**
 * The pinned inventory of the app's own toolchain (docs/OWN-COMPUTER.md §5).
 *
 * Every payload file is listed here with its exact size and SHA-256, and
 * nothing in [ForgeCompiler] executes a byte that this class has not verified.
 * The compiler is the highest-privilege code in the app — it produces code
 * that runs inside the app's own process — so "the file is where we put it"
 * is not evidence: the hash is.
 */
object ToolchainManifest {

    data class Entry(val name: String, val sha256: String, val size: Long)

    sealed class Parsed {
        data class Ok(val entries: List<Entry>) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * Manifest grammar: one entry per line, `<name> <sha256> <size>`, `#`
     * comments and blank lines ignored. Deliberately trivial — an inventory
     * format clever enough to have bugs is the wrong instrument here.
     */
    fun parse(text: String): Parsed {
        val entries = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            if (parts.size != 3) {
                return Parsed.Invalid("line ${i + 1}: expected '<name> <sha256> <size>', got '$line'")
            }
            val (name, sha, sizeText) = parts
            if (!isSafeName(name)) {
                return Parsed.Invalid("line ${i + 1}: unsafe payload name '$name'")
            }
            if (!seen.add(name)) {
                return Parsed.Invalid("line ${i + 1}: duplicate payload '$name'")
            }
            if (!HEX64.matches(sha)) {
                return Parsed.Invalid("line ${i + 1}: sha256 must be 64 lowercase hex chars")
            }
            val size = sizeText.toLongOrNull()
            if (size == null || size <= 0) {
                return Parsed.Invalid("line ${i + 1}: size must be a positive integer")
            }
            entries += Entry(name, sha, size)
        }
        if (entries.isEmpty()) return Parsed.Invalid("manifest lists no payload files")
        return Parsed.Ok(entries)
    }

    /**
     * A payload name must stay inside the toolchain root: plain relative path
     * segments only. `../`, absolute paths, backslashes and NUL are rejected
     * here rather than being "handled" later.
     */
    fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 200) return false
        if (name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) return false
        if (name.contains("//")) return false
        return name.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /**
     * Returns the problems found, empty when the tree is exactly what the
     * manifest says. [digest] is injected so the pure logic is testable
     * without touching the filesystem.
     */
    fun verify(
        entries: List<Entry>,
        root: File,
        digest: (File) -> String,
    ): List<String> {
        val problems = mutableListOf<String>()
        for (e in entries) {
            val f = File(root, e.name)
            if (!f.isFile) {
                problems += "missing: ${e.name}"
                continue
            }
            if (f.length() != e.size) {
                problems += "size mismatch: ${e.name} (expected ${e.size}, found ${f.length()})"
                continue
            }
            val actual = digest(f)
            if (!actual.equals(e.sha256, ignoreCase = true)) {
                problems += "sha256 mismatch: ${e.name}"
            }
        }
        return problems
    }
}
