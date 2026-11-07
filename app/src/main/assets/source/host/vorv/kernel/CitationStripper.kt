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

    /**
     * Night session 2026-11-08 (acceptance-prep audit): the forged-inline-
     * citation gate. strip() numbers ANY [cite:type:ref] the model types —
     * nothing checked that a tool actually produced that ref, so a
     * hallucinated [cite:memory:42] rendered as a tappable proof chip and
     * laundered a guess into "verified" (a single fake tag also defeated
     * the [uncited] flag: an empty-evidence answer looked cited).
     *
     * stripValidated() numbers BACKED tags as before and, for tags the
     * predicate rejects, swaps the tag for the system's own honesty marker
     * [uncited] (hard rule 4's vocabulary: a fact you cannot cite is
     * [uncited]) and collects them in [Validated.forged]. The kernel
     * decides what "backed" means — a tool-captured citation, or the ref
     * appearing in a same-family tool's real output — so this class stays
     * dependency-free and unit-testable.
     */
    data class Validated(
        val text: String,
        val citations: List<Citation>,
        val forged: List<Citation>,
    )

    fun stripValidated(
        text: String,
        isBacked: (type: String, ref: String) -> Boolean,
    ): Validated {
        val kept = mutableListOf<Citation>()
        val forged = mutableListOf<Citation>()
        var i = 0
        val stripped = pattern.replace(text) { mr ->
            val type = mr.groupValues[1]
            val ref = mr.groupValues[2]
            if (isBacked(type, ref)) {
                i++
                kept.add(Citation(type, ref, "$type#$ref".take(20)))
                "[$i]"
            } else {
                forged.add(Citation(type, ref, "$type#$ref".take(20)))
                "[uncited]"
            }
        }
        return Validated(stripped, kept, forged)
    }
}
