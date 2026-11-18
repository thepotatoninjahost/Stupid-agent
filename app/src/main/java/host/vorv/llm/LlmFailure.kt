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
        http.code == 402 -> "this account has no credit for that model (HTTP 402). Pick a free one."
        http.code == 429 -> "the provider is rate-limiting right now (HTTP 429). It is not your key — try again shortly."
        http.code >= 500 -> "the provider is having an outage (HTTP ${http.code}). Not your configuration."
        else -> "HTTP ${http.code}: ${body.take(160)}"
    }
}
