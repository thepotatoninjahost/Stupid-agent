package host.vorv.kernel

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import host.vorv.tools.Tool

/**
 * Validates LLM-produced tool arguments against the tool's declared JSON
 * schema before dispatch (audit architecture recommendation 10.2 — the LLM
 * is untrusted; its arguments are data, not commands).
 *
 * Checks: required properties present, and the JSON type of each declared
 * property matches ("string" | "integer" | "number" | "boolean"). Extra
 * properties are allowed (models add harmless keys).
 *
 * Returns null when valid, or a human/LLM-readable error message.
 */
object ToolCallValidator {

    /**
     * Matches each declared argument to the type its schema asks for.
     *
     * Qwen's chat template writes every scalar argument as plain text — a
     * number as `10`, a boolean as `True` — so an XML/Hermes tool call that is
     * right in meaning would otherwise be refused for its JSON type ("count
     * must be integer, got string") and cost the model an iteration. Only
     * declared properties are touched, a value that does not convert is left
     * alone for [validate] to reject, and objects/arrays pass through.
     */
    fun coerce(tool: Tool, input: JsonObject): JsonObject {
        val props = runCatching { tool.schema().getAsJsonObject("properties") }.getOrNull() ?: return input
        var out: JsonObject? = null
        for ((name, def) in props.entrySet()) {
            val v = input.get(name) ?: continue
            if (v.isJsonNull || !v.isJsonPrimitive) continue
            val expected = (def as? JsonObject)?.get("type")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val coerced = when (expected) {
                "integer" -> v.asString.toLongOrNull()?.let { JsonPrimitive(it) }
                "number" -> v.asString.toDoubleOrNull()?.let { JsonPrimitive(it) }
                "boolean" -> when (v.asString.trim().lowercase()) {
                    "true" -> JsonPrimitive(true)
                    "false" -> JsonPrimitive(false)
                    else -> null
                }
                "string" -> if (v.asJsonPrimitive.isString) null else JsonPrimitive(v.asString)
                else -> null
            } ?: continue
            if (coerced == v) continue
            val target = out ?: input.deepCopy().also { out = it }
            target.add(name, coerced)
        }
        return out ?: input
    }

    fun validate(tool: Tool, input: JsonObject): String? {
        val schema = tool.schema()
        val props = schema.getAsJsonObject("properties") ?: return null
        val required = schema.getAsJsonArray("required")?.map { it.asString } ?: emptyList()

        for (name in required) {
            val v = input.get(name)
            if (v == null || v.isJsonNull) {
                return "missing required argument '$name' for tool '${tool.name}'"
            }
        }

        for ((name, def) in props.entrySet()) {
            val v = input.get(name) ?: continue
            if (v.isJsonNull) continue
            val expected = (def as? JsonObject)?.get("type")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val actual = jsonTypeOf(v) ?: continue   // arrays/objects aren't type-checked deeply
            if (!typesCompatible(expected, actual)) {
                return "argument '$name' for tool '${tool.name}' must be $expected, got $actual"
            }
        }
        return null
    }

    private fun jsonTypeOf(v: com.google.gson.JsonElement): String? = when {
        v.isJsonPrimitive -> {
            val p = v.asJsonPrimitive
            when {
                p.isBoolean -> "boolean"
                p.isString -> "string"
                p.isNumber -> {
                    val d = p.asDouble
                    // JSON has one number type; distinguish integer-valued doubles
                    if (d == Math.floor(d) && !d.isInfinite()) "integer" else "number"
                }
                else -> null
            }
        }
        else -> null
    }

    private fun typesCompatible(expected: String, actual: String): Boolean = when (expected) {
        "number" -> actual == "number" || actual == "integer"
        "integer" -> actual == "integer"
        else -> expected == actual
    }
}
