package host.vorv.llm

/**
 * Everything the app can actually observe about its brains, and nothing else.
 *
 * The owner's complaint was that the app said "no brain" without ever naming a
 * cause, or said "key works" for an empty reply. Both are fixed by judging a
 * brain only on facts: a redacted record of the last HTTP exchange, the last
 * probe, what is on disk, and the last error each engine produced.
 *
 * No Android types live here, so every rule below is unit-tested off-device.
 */
data class BrainFacts(
    val llmMode: String = "cloud",
    val providerId: String = "",
    val providerLabel: String = "",
    val baseUrl: String = "",
    val modelId: String = "",
    val keyPresent: Boolean = false,
    /** Set when the encrypted store could NOT be read — never confused with "no key". */
    val keyReadError: String? = null,
    val lastProbe: Exchange? = null,
    /** The most recent chat request, redacted: never the key, only whether it was sent. */
    val lastExchange: Exchange? = null,
    val localModelId: String = "",
    val localModelName: String = "",
    val localDownloaded: Boolean = false,
    val localBytes: Long = 0,
    val localExpectedBytes: Long = 0,
    val localInitError: String? = null,
    val localEngineError: String? = null,
    val localComputeUnit: String? = null,
    val lastRuntimeError: String? = null,
    /** The file the local engine actually loaded (not what the menu thinks). */
    val localModelFile: String = "",
    /** The window the engine was actually built with, and the last prompt size. */
    val localWindowTokens: Int = 0,
    val lastPromptTokens: Int = 0,
    /** "0.9.30+abc1234" — which build produced these facts. */
    val buildLabel: String = "",
    val abis: List<String> = emptyList(),
    val freeStorageBytes: Long = -1,
) {
    /**
     * One request/response, reduced to what diagnoses a failure.
     * `authorizationSent` is a boolean on purpose: the key itself is never kept.
     */
    data class Exchange(
        val url: String,
        val model: String,
        val authorizationSent: Boolean,
        val code: Int,
        val body: String,
        val at: Long = System.currentTimeMillis(),
        val toolsSent: Boolean = false,
    ) {
        /** The text a model answered with, when the body carries any. */
        val replyText: String
            get() = runCatching {
                val choice = com.google.gson.JsonParser.parseString(body).asJsonObject
                    .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                // Same shapes the LLM code reads (content parts, reasoning,
                // legacy "text"), so health and chat never disagree.
                CompletionText.of(choice?.get("message"))
                    .ifBlank { choice?.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty() }
            }.getOrDefault("")

        val toolCalls: Int
            get() = runCatching {
                com.google.gson.JsonParser.parseString(body).asJsonObject
                    .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.getAsJsonArray("tool_calls")?.size() ?: 0
            }.getOrDefault(0)

        /** True only when the answer is real: text or a tool call, and a 2xx. */
        val answered: Boolean get() = code in 200..299 && (replyText.isNotBlank() || toolCalls > 0)
    }
}
