package host.vorv.llm.provider

/**
 * A model that answers HTTP 200 with no text is a dead end for that id, not a
 * broken key. Leaving the owner to read "pick another" and do it by hand is how
 * a red line survived a hundred fixes: nothing in the app ever tried another
 * model. This tries the ones the provider actually lists, and reports which one
 * answered.
 */
object ModelRescue {

    /** Enough to find a live coder; each try costs a fraction of a cent. */
    const val MAX_TRIES = 4

    /**
     * True for the two failures that are about the model rather than the key:
     * an empty body under HTTP 200, and a model that spent its whole allowance
     * before speaking. Every other failure (401, 429, no route) is not a reason
     * to change models.
     */
    fun isModelFault(e: Throwable?): Boolean {
        val m = e?.message ?: return false
        return m.startsWith("HTTP 200, but model") || m.contains("was still cut off at")
    }

    /**
     * Coder ids first, then the rest, never one already tried. Providers list
     * alphabetically, so the order inside each group is theirs, not a favourite.
     */
    fun candidates(entries: List<ModelCatalog.Entry>, tried: Set<String>): List<String> =
        entries.asSequence()
            .map { it.id }
            .filter { it.isNotBlank() && it !in tried }
            .distinct()
            .sortedByDescending { if (it.contains("coder", ignoreCase = true)) 1 else 0 }
            .take(MAX_TRIES)
            .toList()

    /** [model] answered; [failed] is the id that did not. */
    data class Healed(val model: String, val reply: String, val failed: String)

    /**
     * Probes candidates until one answers. Null means none did, and the caller
     * keeps the original failure so nothing is hidden.
     */
    suspend fun heal(
        current: String,
        entries: List<ModelCatalog.Entry>,
        probe: suspend (String) -> Result<String>,
    ): Healed? {
        for (id in candidates(entries, setOf(current))) {
            val r = probe(id)
            if (r.isSuccess) return Healed(id, r.getOrThrow(), current)
        }
        return null
    }
}
