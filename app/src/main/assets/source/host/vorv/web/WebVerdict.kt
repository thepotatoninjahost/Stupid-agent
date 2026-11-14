package host.vorv.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One assertion the page or the test script made about the running app. */
data class AppCheck(val name: String, val passed: Boolean, val detail: String = "")

/**
 * The verdict on a built app, decided by rules — not by optimism.
 *
 * A run only passes when all of these hold:
 *  - the script's answer could be parsed at all,
 *  - at least one check actually ran,
 *  - every check passed,
 *  - the page logged no JavaScript errors while it ran.
 *
 * Anything else is a failure with a stated reason. "No result" is never
 * treated as "fine": that is exactly how a broken app gets handed over.
 */
data class WebVerdict(
    val ok: Boolean,
    val checks: List<AppCheck>,
    val consoleErrors: List<String>,
    val reason: String?,
    val raw: String = "",
) {
    fun report(): String = buildString {
        append(if (ok) "PASS" else "FAIL")
        reason?.let { append(" — ").append(it) }
        if (checks.isNotEmpty()) {
            append("\nchecks:")
            checks.forEach { append("\n  ").append(if (it.passed) "ok   " else "FAIL ").append(it.name) }
        }
        if (consoleErrors.isNotEmpty()) {
            append("\npage errors:")
            consoleErrors.forEach { append("\n  ").append(it) }
        }
    }

    companion object {
        /**
         * Parses what `WebView.evaluateJavascript` returned. That value is a
         * JSON-encoded *JavaScript value*: a string answer arrives quoted and
         * escaped, an object answer arrives bare, and `null` arrives as the
         * text `null`. All three shapes are handled; unparseable input fails.
         */
        fun fromEvaluation(
            evaluated: String?,
            consoleErrors: List<String>,
            screenshotNote: String? = null,
        ): WebVerdict {
            val errors = consoleErrors.filter { it.isNotBlank() }
            if (evaluated.isNullOrBlank() || evaluated == "null" || evaluated == "\"\"") {
                return WebVerdict(
                    ok = false,
                    checks = emptyList(),
                    consoleErrors = errors,
                    reason = "the test script returned nothing — nothing was verified" +
                        (screenshotNote?.let { " ($it)" } ?: ""),
                    raw = evaluated ?: "",
                )
            }
            val body = unwrap(evaluated)
            val json: JsonObject = try {
                JsonParser.parseString(body).asJsonObject
            } catch (e: Exception) {
                return WebVerdict(
                    ok = false,
                    checks = emptyList(),
                    consoleErrors = errors,
                    reason = "test result was not JSON (${e.message?.take(120)}): ${body.take(300)}",
                    raw = body,
                )
            }

            val errorField = json.get("error")?.takeIf { !it.isJsonNull }?.asString
            if (errorField != null) {
                return WebVerdict(
                    ok = false, checks = emptyList(), consoleErrors = errors,
                    reason = "test script threw: $errorField", raw = body,
                )
            }

            val checks = json.getAsJsonArray("checks")?.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                AppCheck(
                    name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "unnamed check",
                    passed = obj.get("pass")?.takeIf { !it.isJsonNull }?.asBoolean == true,
                    detail = obj.get("detail")?.takeIf { !it.isJsonNull }?.asString ?: "",
                )
            }.orEmpty()

            val reason = when {
                checks.isEmpty() -> "no checks ran — a build you cannot exercise is not tested"
                errors.isNotEmpty() -> "${errors.size} JavaScript error(s) on the page"
                checks.any { !it.passed } -> "${checks.count { !it.passed }} of ${checks.size} checks failed"
                json.get("ok")?.takeIf { !it.isJsonNull }?.asBoolean == false ->
                    "the page reported itself as not ok"
                else -> null
            }
            return WebVerdict(
                ok = reason == null,
                checks = checks,
                consoleErrors = errors,
                reason = reason,
                raw = body,
            )
        }

        /** `"{\"ok\":true}"` -> `{"ok":true}`; bare JSON is returned unchanged. */
        private fun unwrap(evaluated: String): String {
            val trimmed = evaluated.trim()
            if (!trimmed.startsWith("\"")) return trimmed
            return try {
                JsonParser.parseString(trimmed).asString
            } catch (e: Exception) {
                trimmed.removeSurrounding("\"")
            }
        }
    }
}
