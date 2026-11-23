package host.vorv.llm.provider

/**
 * One key, or a refusal that says what is wrong.
 *
 * Setup's key field is a paste target, and a paste from a provider page, a
 * terminal or a password manager often carries a trailing newline — or several
 * keys at once. OkHttp rejects such a header with "Unexpected char 0x0a at 80 in
 * Authorization value", which names neither the cause nor the fix, and the
 * engine used to retry that deterministic failure four times before reporting
 * "the call failed". The value is therefore checked once, before a request is
 * built, and a bad one is refused in words the owner can act on.
 */
object ApiKey {

    /**
     * The key with surrounding whitespace removed.
     *
     * Interior whitespace is refused rather than trimmed: a value like
     * "sk-aaa\nsk-bbb" is two keys, and picking one of them would be a guess
     * about which one is live.
     */
    fun normalize(raw: String): String {
        val key = raw.trim()
        if (key.isEmpty()) return key
        if (key.any { it.isWhitespace() }) {
            val values = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val what = if (values.size > 1) {
                "holds ${values.size} values on ${values.size} lines"
            } else {
                "has a space or line break in the middle"
            }
            throw IllegalArgumentException(
                "The key field $what. Paste exactly one key and nothing else — " +
                    "no second key, no line break.",
            )
        }
        return key
    }
}
