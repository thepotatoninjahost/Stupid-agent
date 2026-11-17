package host.vorv.llm

/**
 * Which brain a message actually runs on.
 *
 * 0.9.13 — the header said "Cloud — needs API key" while an on-phone coder could
 * be sitting in storage, so the owner read the app as having no coder at all.
 * The chosen mode and the header name now come from the same answer:
 *
 *  - "local" chosen → the on-phone coder, ready or not (a missing download is
 *    reported as missing, never quietly replaced by the cloud);
 *  - "cloud" chosen with a key → the cloud model;
 *  - "cloud" chosen with no key but the on-phone coder downloaded → the
 *    on-phone coder, and the header says there is no cloud key;
 *  - nothing usable → "cloud", so the caller reports both missing pieces.
 */
object BrainChoice {

    fun mode(llmMode: String, hasApiKey: Boolean, localModelReady: Boolean): String = when {
        llmMode == "local" -> "local"
        hasApiKey -> "cloud"
        localModelReady -> "local"
        else -> "cloud"
    }

    /** True when some brain can answer right now. */
    fun usable(llmMode: String, hasApiKey: Boolean, localModelReady: Boolean): Boolean =
        if (mode(llmMode, hasApiKey, localModelReady) == "local") localModelReady else hasApiKey

    /** The sentence the header shows when the cloud is not the one answering. */
    fun notice(llmMode: String, hasApiKey: Boolean, localModelReady: Boolean): String? = when {
        llmMode == "cloud" && !hasApiKey && localModelReady ->
            "no cloud key — running on the on-phone coder"
        llmMode == "cloud" && !hasApiKey && !localModelReady -> null
        else -> null
    }
}
