package host.vorv.llm.provider

/**
 * H-5 (code review, 2026-11): a provider address is where the prompt and the
 * key are sent, so it must be TLS unless it is a loopback test server. A
 * cleartext address typed into Setup used to be accepted and used.
 */
object ProviderUrl {

    /** Null when the address is acceptable, otherwise the sentence to show. */
    fun rejectionReason(baseUrl: String): String? {
        val base = baseUrl.trim()
        if (base.isEmpty()) return null // Step 22 resolves blank to the owner's default
        if (base.startsWith("https://", ignoreCase = true)) return null
        if (isLoopbackTest(base)) return null
        return "the provider address must start with https:// (got $base)"
    }

    /**
     * `http://127.0.0.1:8080` and `http://localhost:8080` are allowed so a
     * local test server can be pointed at; the host must be exactly that, not
     * a name that merely starts with it.
     */
    private fun isLoopbackTest(base: String): Boolean {
        val lower = base.lowercase()
        if (!lower.startsWith("http://")) return false
        val authority = lower.removePrefix("http://").substringBefore('/').substringBefore('?')
        val host = authority.substringBefore(':')
        return host == "127.0.0.1" || host == "localhost"
    }
}
