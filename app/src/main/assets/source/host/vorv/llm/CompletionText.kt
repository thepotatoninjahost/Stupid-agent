package host.vorv.llm

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The text of one completion, in every shape a provider actually sends.
 *
 * OpenAI's own format is `choices[0].message.content` as a string, but a
 * gateway (OpenRouter among them) may hand back the same reply as an array of
 * parts — `[{"type":"text","text":"…"}]` — or as an empty `content` with the
 * text in `reasoning`. Reading only `.asString` turned such a reply into
 * "HTTP 200 but no text", which accused the model id of being retired when it
 * had answered perfectly well.
 */
internal object CompletionText {

    /** The reply's text: `content`, then `reasoning`, then legacy `text`. */
    fun of(message: JsonElement?): String {
        val obj = message as? JsonObject ?: return ""
        return content(obj).ifBlank { reasoning(obj) }
    }

    /** Only `message.content`, as a string or as an array of text parts. */
    fun content(message: JsonElement?): String {
        val obj = message as? JsonObject ?: return ""
        val raw = obj.get("content") ?: return ""
        if (raw.isJsonNull) return ""
        if (raw.isJsonPrimitive) return raw.asString
        if (!raw.isJsonArray) return ""
        return raw.asJsonArray.joinToString("") { part ->
            val p = part as? JsonObject ?: return@joinToString ""
            // Some gateways nest the text one level down: {"type":"text","text":{"value":"…"}}.
            val text = p.get("text") ?: return@joinToString ""
            when {
                text.isJsonNull -> ""
                text.isJsonPrimitive -> text.asString
                text.isJsonObject -> text.asJsonObject.get("value")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                else -> ""
            }
        }
    }

    /** `reasoning` (OpenRouter/Qwen) or `reasoning_content` (DeepSeek). */
    fun reasoning(message: JsonElement?): String {
        val obj = message as? JsonObject ?: return ""
        for (key in listOf("reasoning", "reasoning_content")) {
            val v = obj.get(key) ?: continue
            if (v.isJsonPrimitive && v.asString.isNotBlank()) return v.asString
        }
        val details = obj.get("reasoning_details")
        if (details != null && details.isJsonArray) {
            val joined = details.asJsonArray.joinToString("") { d ->
                val text = (d as? JsonObject)?.get("text") ?: return@joinToString ""
                if (text.isJsonPrimitive) text.asString else ""
            }
            if (joined.isNotBlank()) return joined
        }
        return ""
    }

    /**
     * `choices[0].finish_reason`, or null when the provider did not say.
     * "length" means the model ran into its output ceiling: it said nothing
     * because it was cut off, which is not proof that the model is gone.
     */
    fun finishReason(body: String): String? = try {
        JsonParser.parseString(body).asJsonObject
            .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString
    } catch (e: Exception) {
        null
    }

    /**
     * Why a 200 carried nothing, in the provider's own words when it gave any:
     * `error.message`, the finish reason, and the body head. A bare "no text"
     * left the owner guessing; this names the actual payload.
     */
    fun emptyReplyReason(body: String): String {
        // The body here is whatever the provider sent; a non-object is the
        // finding this function reports, not an error to log.
        val obj: JsonObject? = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            null
        }
        val error = obj?.getAsJsonObject("error")
            ?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        val finish = obj?.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString
        val parts = buildList {
            if (!error.isNullOrBlank()) add("provider said: $error")
            if (!finish.isNullOrBlank()) add("finish_reason=$finish")
        }
        return if (parts.isEmpty()) "body: ${body.replace('\n', ' ').take(240)}" else parts.joinToString(", ")
    }
}
