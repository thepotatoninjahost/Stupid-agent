package host.vorv.llm.provider

/**
 * Which engine a cloud request actually uses, decided in one place.
 *
 * Owner decision (2026-11): the default cloud route is the owner's Qwen3
 * Coder, and a blank address means that default. There is no Z.ai model
 * chain any more — one request goes to exactly one address and one model,
 * so a provider that answers "no such model" says so instead of being
 * silently replaced by a different family's id.
 */
object BrainTarget {

    /** The default route a blank address resolves to: the owner's trainer model. */
    const val DEFAULT_BASE = "https://openrouter.ai/api/v1"
    const val DEFAULT_MODEL = "qwen/qwen3-coder-next"

    data class Engine(val baseUrl: String, val modelId: String)

    /**
     * Resolution rules, in this order, and nothing else:
     *  1. an address and a model → that route, unchanged;
     *  2. a blank address → [DEFAULT_BASE] + [DEFAULT_MODEL] ("the owner's
     *     default", whatever the model field says);
     *  3. an address and a blank model → [DEFAULT_MODEL] must NOT be sent to
     *     an address the owner chose (that provider may not serve it), so
     *     [refusal] reports it and this returns the blank id for callers that
     *     ask anyway.
     */
    fun resolve(baseUrl: String, model: String): Engine {
        val base = baseUrl.trim().trimEnd('/')
        val id = model.trim()
        return when {
            base.isBlank() -> Engine(DEFAULT_BASE, DEFAULT_MODEL)
            id.isBlank() -> Engine(base, "")
            else -> Engine(base, id)
        }
    }

    /**
     * The address a chosen provider uses when the owner typed none: that
     * provider's own preset, never another vendor's. An id with no preset
     * (a legacy or custom one) lands on the default route.
     */
    fun baseFor(baseUrl: String, providerId: String): String =
        baseUrl.trim().ifBlank {
            ProviderRegistry.orDefault(providerId).baseUrl.trim()
                .ifBlank { DEFAULT_BASE }
        }

    /** The model a chosen provider serves when the owner typed none. */
    fun modelFor(model: String, providerId: String): String =
        model.trim().ifBlank { ProviderRegistry.orDefault(providerId).defaultModel }

    /** [resolve] with the provider's own address and default model filled in. */
    fun resolveFor(baseUrl: String, model: String, providerId: String): Engine =
        resolve(baseFor(baseUrl, providerId), modelFor(model, providerId))

    /** Null when the engine can be built, otherwise the sentence to show. */
    fun refusal(baseUrl: String, model: String): String? {
        // H-5: Setup shows this before the user can save a cleartext address.
        ProviderUrl.rejectionReason(baseUrl)?.let { return it }
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return null // the default route is complete
        if (model.trim().isBlank()) {
            return "This provider needs a model name — Setup → Refresh list, then pick one."
        }
        return null
    }
}
