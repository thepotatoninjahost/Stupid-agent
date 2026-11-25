package host.vorv.llm

/**
 * Turns a failed call into one sentence the owner can act on. Kept out of the
 * view model so it can be tested without Android: these sentences are what the
 * owner sees instead of "something went wrong".
 */
fun explainLlmFailure(e: Throwable): String {
    val http = e as? LlmHttpException ?: return (e.message ?: "").ifBlank { e.javaClass.simpleName }
    val body = e.message ?: ""
    return when {
        http.code == 401 || http.code == 403 ->
            "the provider refused the key (HTTP ${http.code}). Re-copy it from the provider's dashboard."
        http.code == 404 || (http.code == 400 && body.lowercase().contains("model")) ->
            "that model name does not exist at this provider (HTTP ${http.code}). Tap Refresh list and pick one it serves."
        // Free tiers are small, and an agent step is a request: one run can
        // spend a whole day's allowance. Say the real numbers instead of
        // "try again shortly", which is never true for this owner.
        http.code == 402 ->
            "this account has no credit for that model (HTTP 402). Pick a free one, or use the local " +
                "model, which needs no account and has no quota."
        http.code == 429 ->
            "the provider's rate limit is used up (HTTP 429), and it is not your key. Free plans are small — " +
                "OpenRouter's free models allow 20 requests a minute and 50 a day until $10 of credit has " +
                "been bought once. For no limit, switch to the local model: it needs no key and no quota."
        http.code >= 500 -> "the provider is having an outage (HTTP ${http.code}). Not your configuration."
        else -> "HTTP ${http.code}: ${body.take(160)}"
    }
}
