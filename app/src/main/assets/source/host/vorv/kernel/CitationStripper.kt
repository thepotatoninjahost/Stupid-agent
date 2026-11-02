package host.vorv.kernel

import host.vorv.tools.Citation

/**
 * Extracts [cite:<type>:<ref>] tags from a final answer, replacing them with
 * numbered markers and collecting the Citation objects (audit finding H-6:
 * extracted from CognitiveKernel so it is unit-testable).
 *
 * The kernel keeps tool-captured citations and inline citations in SEPARATE
 * lists; an answer is flagged [uncited] only when BOTH are empty, regardless
 * of iteration count (audit finding H-10 — the old `iterations == 1` check
 * let iterations-2+ answers pass uncited).
 */
object CitationStripper {

    private val pattern = Regex("""\[cite:(\w+):([^\]]+)\]""")

    data class Result(val text: String, val citations: List<Citation>)

    fun strip(text: String): Result {
        val out = mutableListOf<Citation>()
        var i = 0
        val stripped = pattern.replace(text) { mr ->
            val type = mr.groupValues[1]
            val ref = mr.groupValues[2]
            i++
            out.add(Citation(type, ref, "$type#$ref".take(20)))
            "[$i]"
        }
        return Result(stripped, out)
    }
}
