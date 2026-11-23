package host.vorv.llm.provider

import okhttp3.Request

/**
 * The two things every cloud call must agree on: how the key is presented, and
 * what a failure means.
 *
 * They used to be written at each call site, so the probe and the real call
 * could disagree about a header and the chat bubble printed a raw
 * "LLM HTTP 401: {...}". Both live here now, and are tested without a network.
 */
object BrainHttp {

    /** Adds the provider's own auth header. A blank key adds nothing at all. */
    fun applyAuth(builder: Request.Builder, provider: Provider?, apiKey: String): Request.Builder {
        // A value with an interior line break is not a header OkHttp will
        // build, and the sentence it throws names neither cause nor fix.
        val key = ApiKey.normalize(apiKey)
        if (key.isEmpty()) return builder
        val style = provider?.authStyle ?: Provider.AuthStyle.BEARER
        if (style == Provider.AuthStyle.NONE) return builder
        return builder.addHeader(style.header, style.prefix + key)
    }

    /** The name the owner sees in the header and in an error sentence. */
    fun labelFor(provider: Provider?, model: String): String =
        provider?.label?.takeIf { it.isNotBlank() }?.let { "$it · $model" } ?: model

    /**
     * One sentence the owner can act on. Never a raw body, never a status code
     * alone, and never a suggestion that would cost him money by accident.
     */
    fun explain(code: Int?, body: String, providerLabel: String, model: String): String {
        val b = body.lowercase()
        return when {
            code == null ->
                "$providerLabel could not be reached from the phone — check the network, then Test again."
            code == 401 || code == 403 ->
                "$providerLabel rejected the key — open its card and paste the key again."
            code == 404 || (code == 400 && (b.contains("model") || b.contains("not found"))) ->
                "$providerLabel does not serve \"$model\" — tap Refresh list and pick one it lists."
            code == 402 || b.contains("insufficient") || b.contains("quota") || b.contains("credit") ->
                "$providerLabel says this account has no credit for \"$model\" — choose a free model."
            code == 429 ->
                "$providerLabel is busy (rate limit) — this is not your key; try again shortly or pick another provider."
            code != null && code >= 500 ->
                "$providerLabel is having an outage (HTTP $code) — not your configuration; the next provider will be tried."
            code == 400 ->
                "$providerLabel refused the request (HTTP 400): ${body.take(160)}"
            code != null && code in 200..299 ->
                "$providerLabel accepted the request and returned no text — \"$model\" is probably retired."
            else ->
                "$providerLabel answered HTTP $code: ${body.take(160)}"
        }
    }
}
