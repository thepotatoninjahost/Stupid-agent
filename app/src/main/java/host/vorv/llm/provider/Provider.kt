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
 * The addresses the app ships with. Ordered strongest-coder-first, because the
 * owner's complaint was "I still don't have a good coder", not "I need more
 * buttons".
 */
object ProviderRegistry {

    const val ZAI_ID = "z.ai"

    val presets: List<Provider> = listOf(
        Provider(
            id = ZAI_ID,
            label = "Z.ai",
            baseUrl = "https://api.z.ai/api/paas/v4",
            freeTier = true,
            capability = Provider.Capability.CODE,
            // No suggestions (owner decision): GLM ids are not used, and the
            // catalog's own /models listing is the honest source of ids.
            suggestions = emptyList(),
            notes = "This is the provider the owner already had; it is one option now, not the only one.",
        ),
        Provider(
            id = "openrouter",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            capability = Provider.Capability.CODE,
            modelsPath = "/models",
            suggestions = listOf("qwen/qwen3-coder", "deepseek/deepseek-chat-v3.1"),
            notes = "One key, many vendors. Model ids are vendor/model.",
        ),
        Provider(
            id = "deepseek",
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            capability = Provider.Capability.CODE,
            suggestions = listOf("deepseek-coder", "deepseek-chat"),
        ),
        Provider(
            id = "groq",
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            freeTier = true,
            capability = Provider.Capability.GENERAL,
            suggestions = listOf("qwen/qwen3-32b", "llama-3.3-70b-versatile"),
        ),
        Provider(
            id = "cerebras",
            label = "Cerebras",
            baseUrl = "https://api.cerebras.ai/v1",
            freeTier = true,
            capability = Provider.Capability.GENERAL,
            suggestions = listOf("qwen-3-coder-480b", "llama3.3-70b"),
        ),
        Provider(
            id = "together",
            label = "Together",
            baseUrl = "https://api.together.xyz/v1",
            capability = Provider.Capability.CODE,
            suggestions = listOf("Qwen/Qwen3-Coder-480B-A35B-Instruct-FP8"),
        ),
        Provider(
            id = "fireworks",
            label = "Fireworks",
            baseUrl = "https://api.fireworks.ai/inference/v1",
            capability = Provider.Capability.CODE,
            suggestions = listOf("accounts/fireworks/models/qwen3-coder-480b-a35b-instruct"),
        ),
        Provider(
            id = "mistral",
            label = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            freeTier = true,
            capability = Provider.Capability.CODE,
            suggestions = listOf("codestral-latest", "mistral-large-latest"),
        ),
        Provider(
            id = "google",
            label = "Google AI Studio",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            freeTier = true,
            capability = Provider.Capability.REASONING,
            suggestions = listOf("gemini-2.5-pro", "gemini-2.5-flash"),
            notes = "Google's OpenAI-compatible endpoint; the key goes in the bearer header.",
        ),
        Provider(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            capability = Provider.Capability.REASONING,
            suggestions = listOf("gpt-5", "gpt-5-mini"),
        ),
        Provider(
            id = "anthropic",
            authStyle = Provider.AuthStyle.X_API_KEY,
            label = "Anthropic (OpenAI-compatible gateway)",
            baseUrl = "https://api.anthropic.com/v1",
            capability = Provider.Capability.REASONING,
            supportsTools = false,
            suggestions = listOf("claude-sonnet-4-5"),
            notes = "Native Anthropic uses a different body shape; point this at a gateway that speaks /chat/completions.",
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
