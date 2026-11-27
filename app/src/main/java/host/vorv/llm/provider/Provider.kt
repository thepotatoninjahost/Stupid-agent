package host.vorv.llm.provider

/**
 * A cloud provider, as data.
 *
 * One vendor's model list used to be compiled into the brain: one base URL, one enum of model ids,
 * one key slot. When that provider was over-subscribed the app had nothing —
 * which is exactly what the owner reported. Nothing here is an enum that gates
 * routing: a provider is an address, a way of presenting a key, and where its
 * model list lives. Presets below are suggestions; the owner's own entry is a
 * provider too.
 */
data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    /** Presets with a free tier are named as such so Setup can say it. */
    val freeTier: Boolean = false,
    /** Not used to route; shown in Setup and by the router as a tie-breaker. */
    val capability: Capability = Capability.GENERAL,
    /** Relative path appended to [baseUrl] for the model list. */
    val modelsPath: String = "/models",
    /** Whether the provider is known to accept OpenAI-style tool schemas. */
    val supportsTools: Boolean = true,
    /**
     * The id this provider serves when the owner has not chosen one. A default,
     * not a catalogue: no other ids are compiled in.
     */
    val defaultModel: String = "",
    /** Suggested strong coder/reasoner ids; only suggestions, never trusted. */
    val suggestions: List<String> = emptyList(),
    val notes: String = "",
    /** How this provider wants the key presented. Sent only when it is not BEARER. */
    val authStyle: AuthStyle = AuthStyle.BEARER,
) {
    enum class Capability { CODE, REASONING, GENERAL }

    /** A provider that wants a different header is not a special case in code. */
    enum class AuthStyle(val header: String, val prefix: String) {
        BEARER("Authorization", "Bearer "),
        X_API_KEY("x-api-key", ""),
        API_KEY("api-key", ""),
        NONE("", ""),
    }

    val keyId: String get() = "key.$id"

    companion object {
        fun custom(baseUrl: String, label: String = "your provider") = Provider(
            id = "custom",
            label = label,
            baseUrl = baseUrl,
            capability = Capability.GENERAL,
            notes = "Any address that speaks /chat/completions",
        )
    }
}

/**
 * The routes the app ships with: free or bring-your-own, and only those.
 *
 * The owner does not pay for a model and does not want a wall of provider
 * buttons, so this list is deliberately short — OpenRouter (free ids), Hugging
 * Face (a router, honestly labelled as paid), and the owner's own address.
 * Anything else is added as Custom, not compiled in.
 */
object ProviderRegistry {

    /** The owner's route: OpenRouter's own alias for whichever free model is up. */
    const val DEFAULT_ID = "openrouter"

    /**
     * Ids the owner does not want offered, whatever the provider's live list
     * says. GLM is a family (owner decision: "all of the GLM models need to be
     * removed"); the rest were named one by one in a screenshot of the model
     * picker and are pinned exactly, so a similarly named id is not caught by
     * accident.
     */
    private val removedFamilies = listOf("glm")

    private val removedIds = setOf(
        "openai/gpt-oss-20b",
        "openai/gpt-oss-safeguard-20b",
        "openai/gpt-oss-120b",
        "meta-llama/llama-prompt-guard-2-22m",
        "meta-llama/llama-prompt-guard-2-86m",
        "allam-2-7b",
        "canopylabs/orpheus-arabic-saudi",
    )

    fun isRemovedModelId(id: String): Boolean {
        val lower = id.trim().lowercase()
        return lower in removedIds || removedFamilies.any { lower.contains(it) }
    }

    /** The count Setup reports as hidden, so the list never lies by omission. */
    fun hiddenModelCount(ids: List<String>): Int = ids.count { isRemovedModelId(it) }

    val presets: List<Provider> = listOf(
        Provider(
            id = DEFAULT_ID,
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            freeTier = true,
            capability = Provider.Capability.CODE,
            modelsPath = "/models",
            // The owner does not pay for a model, so the default and the
            // suggestions are free ids only. `openrouter/free` is OpenRouter's
            // own alias that routes to whichever free model is up.
            defaultModel = "openrouter/free",
            suggestions = listOf(
                "openrouter/free",
                "google/gemma-4-31b-it:free",
                "nvidia/nemotron-3-ultra-550b-a55b:free",
            ),
            notes = "One key, many vendors. Model ids are vendor/model. Free models are capped at " +
                "20 requests a minute and 50 a day until $10 of credit has been bought once, so a long " +
                "run will hit the cap.",
        ),
        Provider(
            id = "huggingface",
            label = "Hugging Face",
            baseUrl = "https://router.huggingface.co/v1",
            capability = Provider.Capability.CODE,
            modelsPath = "/models",
            // Their docs: base_url "https://router.huggingface.co/v1", the key
            // is an HF token, and the model id is "<repo>:<provider>" or
            // "<repo>:fastest". Not an owner-free route: free accounts get no
            // monthly credits, PRO (paid) gets $2 of them.
            suggestions = listOf(
                "deepseek-ai/DeepSeek-V4.1-Flash:fastest",
                "deepseek-ai/DeepSeek-V4.1-Flash:cheapest",
            ),
            notes = "OpenAI-compatible router over many providers; the key is a Hugging Face token. " +
                "Not a no-cost route: free accounts get no monthly credits — you buy credits, or PRO " +
                "gets $2 of them a month.",
        ),
        Provider(
            id = "custom",
            label = "Custom / self-hosted",
            baseUrl = "",
            capability = Provider.Capability.GENERAL,
            notes = "llama.cpp, vLLM, LM Studio on the LAN — anything with /chat/completions.",
        ),
    )

    fun byId(id: String): Provider? = presets.firstOrNull { it.id == id }

    /** The preset the app starts on; an unknown or legacy id lands here too. */
    fun orDefault(id: String): Provider = byId(id) ?: byId(DEFAULT_ID)!!

    /** A preset, or an ad-hoc provider at the address the owner typed. */
    fun byIdOrCustom(id: String, baseUrl: String): Provider =
        byId(id) ?: Provider.custom(baseUrl, if (id.isBlank()) "your provider" else id)

    /** Presets first, then any provider the owner added. */
    fun all(custom: Provider? = null): List<Provider> =
        if (custom == null) presets else presets + custom

    /**
     * The one address builder. The old code appended "/chat/completions" in
     * two places and trimmed a trailing slash in only one of them, so an
     * address typed with a trailing slash passed the test call and failed at
     * task time. Everything goes through here now.
     */
    fun chatCompletionsUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        // The owner may paste the full endpoint; never double the path.
        return if (base.endsWith("/chat/completions")) base else base + "/chat/completions"
    }

    fun modelsUrl(baseUrl: String, modelsPath: String = "/models"): String {
        val base = baseUrl.trim().trimEnd('/')
        val path = modelsPath.trim().let { if (it.startsWith("/")) it else "/$it" }
        // OpenRouter and friends already include /v1; modelsPath is relative to it.
        return base + path
    }
}
