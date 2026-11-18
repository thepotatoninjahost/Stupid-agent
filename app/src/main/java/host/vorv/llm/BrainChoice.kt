package host.vorv.llm

/**
 * One resolution point for "which brain answers this message".
 *
 * 0.9.13 fixed the three-input case (mode, key, download). The screenshots of
 * 2026-11-17 showed the remaining hole: the header was rendered from *prefs*
 * while the send gate judged something else, so a custom model with no key was
 * labelled "NVIDIA: Nemotron 3 Ultra (cloud)" and every message came back
 * "I have no brain to think with yet". Header and gate now come from
 * [resolve], which reads [BrainFacts] — the same values the HTTP path uses.
 */
object BrainChoice {

    /** A brain, named, with the single sentence explaining it. */
    data class Resolved(
        /** "local" or "cloud" — what the send path will use. */
        val mode: String,
        /** Text for the header chip. Never claims a brain that is not there. */
        val label: String,
        /** Extra sentence under the header, when something must be said. */
        val notice: String? = null,
        /** True only when a message can actually be answered right now. */
        val usable: Boolean,
        /** Why not, in the owner's words, when [usable] is false. */
        val reason: String? = null,
    )

    fun resolve(f: BrainFacts): Resolved {
        val local = localBrain(f)
        val cloud = cloudBrain(f)

        if (f.llmMode == "local") {
            return if (f.localDownloaded) local
            else local.copy(
                usable = false,
                reason = "${f.localModelName.ifBlank { "The on-phone coder" }} is not downloaded yet — " +
                    "Setup → download it, or choose the cloud.",
            )
        }

        // Cloud chosen. A cloud brain that cannot answer never hides an
        // on-phone one that can.
        if (cloud.usable) return cloud
        if (f.localDownloaded) {
            return local.copy(
                notice = cloud.reason?.let { "cloud: $it — running on the on-phone coder" }
                    ?: "no cloud key — running on the on-phone coder",
            )
        }
        return Resolved(
            mode = "cloud",
            label = "No brain yet",
            notice = "nothing can answer: no key for ${f.providerLabel.ifBlank { "the cloud provider" }}, " +
                "and no on-phone coder downloaded",
            usable = false,
            reason = cloud.reason,
        )
    }

    private fun localBrain(f: BrainFacts): Resolved {
        val name = f.localModelName.ifBlank { f.localModelId.ifBlank { "on-phone coder" } }
        val unit = f.localComputeUnit?.let { ", $it" } ?: ""
        return Resolved(
            mode = "local",
            label = "$name (on-phone, offline$unit)",
            usable = f.localDownloaded,
        )
    }

    private fun cloudBrain(f: BrainFacts): Resolved {
        val provider = f.providerLabel.ifBlank { f.providerId.ifBlank { "cloud" } }
        val model = f.modelId.ifBlank { f.lastExchange?.model.orEmpty() }

        if (f.keyReadError != null) {
            return Resolved(
                "cloud",
                "$provider · key unreadable",
                "key stored but unreadable — re-enter it",
                usable = false,
                reason = "the encrypted store could not be read (${f.keyReadError}) — re-enter the key in Setup",
            )
        }
        if (!f.keyPresent) {
            return Resolved(
                "cloud",
                if (model.isBlank()) "$provider — no key" else "$model (cloud — no key)",
                null,
                usable = false,
                reason = "no key saved for $provider — Setup → provider key → Save & Test",
            )
        }

        // A hard failure in the most recent request or test is a fact, not a
        // guess: 401/403 (key refused), 404 (model not served), or a 200 with no
        // text (retired model). Anything else (429, 5xx, timeouts) is worth a try.
        val latest = listOfNotNull(f.lastProbe, f.lastExchange).maxByOrNull { it.at }
        val hardFailure = latest?.let { e ->
            when {
                !e.authorizationSent && e.code in 400..499 -> "the request went out without the key header"
                e.code == 401 || e.code == 403 -> "HTTP ${e.code}: the provider refused the key (${e.body.take(120)})"
                e.code == 404 -> "HTTP 404: model \"${e.model}\" is not served there"
                e.code in 200..299 && !e.answered ->
                    "HTTP 200 with no text: model \"${e.model}\" is probably retired"
                else -> null
            }
        }

        val label = if (model.isBlank()) provider else "$model (cloud)"
        if (hardFailure != null) {
            return Resolved("cloud", label, hardFailure, usable = false, reason = hardFailure)
        }
        return Resolved("cloud", label, usable = true)
    }

    // ---- The 0.9.13 three-input API, kept so existing callers and tests hold.

    fun mode(llmMode: String, hasApiKey: Boolean, localModelReady: Boolean): String = when {
        llmMode == "local" -> "local"
        hasApiKey -> "cloud"
        localModelReady -> "local"
        else -> "cloud"
    }

    fun usable(llmMode: String, hasApiKey: Boolean, localModelReady: Boolean): Boolean =
        if (mode(llmMode, hasApiKey, localModelReady) == "local") localModelReady else hasApiKey

    fun notice(llmMode: String, hasApiKey: Boolean, localModelReady: Boolean): String? = when {
        llmMode == "cloud" && !hasApiKey && localModelReady ->
            "no cloud key — running on the on-phone coder"
        llmMode == "cloud" && !hasApiKey && !localModelReady -> null
        else -> null
    }
}
