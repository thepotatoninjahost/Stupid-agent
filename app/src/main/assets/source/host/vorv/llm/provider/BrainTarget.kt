package host.vorv.llm.provider

/**
 * Which engine a cloud request actually uses, decided in one place.
 *
 * 0.9.13 let the owner paste any address, but the default (no model typed) still
 * fell through to the Z.ai model chain, so a different provider answered 401
 * with a key that was fine. 0.9.18 keeps the chain only for Z.ai; every other
 * address gets exactly the model chosen for it.
 */
object BrainTarget {

    const val ZAI_BASE = "https://api.z.ai/api/paas/v4"

    data class Engine(
        /** True: the Z.ai catalog chain, which tries several models in turn. */
        val useChain: Boolean,
        val baseUrl: String,
        val modelId: String,
    )

    fun resolve(baseUrl: String, model: String, zaiDefaultModel: String): Engine {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.isBlank() || base == ZAI_BASE ->
                if (model.isBlank()) Engine(true, ZAI_BASE, zaiDefaultModel)
                else Engine(false, base.ifBlank { ZAI_BASE }, model)
            else -> Engine(false, base, model)
        }
    }

    /** Null when the engine can be built, otherwise the sentence to show. */
    fun refusal(baseUrl: String, model: String): String? {
        // H-5: Setup shows this before the user can save a cleartext address.
        ProviderUrl.rejectionReason(baseUrl)?.let { return it }
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank() || base == ZAI_BASE || model.isNotBlank()) return null
        return "This provider needs a model name — Setup → Refresh list, then pick one."
    }
}
