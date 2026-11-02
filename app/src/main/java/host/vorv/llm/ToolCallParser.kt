package host.vorv.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parses raw local-LLM output into either a tool-call request or a final
 * answer (audit finding H-6: extracted from LocalLlm so it is unit-testable).
 *
 * Qwen2.5-Coder's chat template emits OpenAI-style tool calls as:
 *   <tool>{"name": "...", "arguments": {...}}</tool>
 *
 * Fallback plain format (system-prompt-driven):
 *   TOOL_CALL: tool_name
 *   ARGS: {"...": ...}
 *
 * Because the local model runs with a finite maxTokens budget, a tool call
 * can be TRUNCATED mid-JSON. [repairJson] balances braces/brackets/quotes so
 * a truncated call is still recoverable instead of being mis-read as a raw
 * text answer (audit finding M-1).
 */
object ToolCallParser {

    private val openAiToolCallRegex = Regex(
        pattern = "<tool>\\s*(\\{.*?\\})\\s*</tool>",
        options = setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val plainToolCallRegex = Regex(
        // Separator between tool name and ARGS: may be a newline, a colon, or
        // plain whitespace (all three observed from the local model).
        pattern = "TOOL_CALL:\\s*([A-Za-z0-9_]+)\\s*:?\\s*ARGS:\\s*(\\{[\\s\\S]*\\})",
    )
    /** Lenient variant: captures everything after ARGS: even with no closing brace. */
    private val plainLenientRegex = Regex(
        pattern = "TOOL_CALL:\\s*([A-Za-z0-9_]+)\\s*:?\\s*ARGS:\\s*([\\s\\S]*)",
    )
    private val answerRegex = Regex("ANSWER:\\s*([\\s\\S]*)")

    fun parse(text: String): Llm.LlmResponse {
        val trimmed = text.trim()

        // 1. Well-formed OpenAI-style call.
        openAiToolCallRegex.find(trimmed)?.let { mr ->
            parseOpenAiArgs(mr.groupValues[1])?.let { return it }
        }

        // 2. Truncated OpenAI-style call: "<tool>{..." without a closing tag.
        //    Attempt brace-balancing repair before giving up.
        if (!trimmed.contains("</tool>")) {
            val start = trimmed.indexOf("<tool>")
            if (start >= 0) {
                val raw = trimmed.substring(start + "<tool>".length).trim()
                repairJson(raw)?.let { parseOpenAiArgs(it) }?.let { return it }
            }
        }

        // 2.5. Markdown-fenced or bare-JSON tool call.
        //      Qwen2.5-Coder via GenieX sometimes emits tool calls as:
        //        ```json
        //        {"name": "js_exec", "arguments": {"code": "..."}}
        //        ```
        //      or as a bare JSON object/array, instead of the expected
        //      <tool>...</tool> tags. This path strips fences and parses.
        //      Gated on having a "name" key so prose containing JSON is
        //      never misparsed as a tool call.
        parseMarkdownOrBareJson(trimmed)?.let { return it }

        // 3. Plain-text protocol.
        plainToolCallRegex.find(trimmed)?.let { mr ->
            val name = mr.groupValues[1]
            val rawArgs = mr.groupValues[2].trim()
            val args = try {
                JsonParser.parseString(rawArgs).asJsonObject.toString()
            } catch (e: Exception) {
                JsonObject().apply {
                    addProperty("error", "model produced invalid JSON args: ${rawArgs.take(200)}")
                }.toString()
            }
            return Llm.LlmResponse.ToolUse(
                toolCalls = listOf(Llm.ToolCall(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    arguments = args,
                )),
                reasoning = null,
            )
        }

        // 3b. Plain-text protocol with TRUNCATED arguments (no closing brace):
        //     "TOOL_CALL: name ARGS: {"not closed" — still a tool call; the
        //     kernel will report the invalid-JSON args honestly to the model.
        if (trimmed.contains("TOOL_CALL:")) {
            plainLenientRegex.find(trimmed)?.let { mr ->
                val name = mr.groupValues[1]
                val rawArgs = mr.groupValues[2].trim()
                val args = try {
                    JsonParser.parseString(rawArgs).asJsonObject.toString()
                } catch (e: Exception) {
                    JsonObject().apply {
                        addProperty("error", "model produced invalid JSON args: ${rawArgs.take(200)}")
                    }.toString()
                }
                return Llm.LlmResponse.ToolUse(
                    toolCalls = listOf(Llm.ToolCall(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        arguments = args,
                    )),
                    reasoning = null,
                )
            }
        }

        // 4. ANSWER: prefix, or the raw text.
        val ans = answerRegex.find(trimmed)
        val body = ans?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: trimmed
        return Llm.LlmResponse.Text(body, done = true)
    }

    private fun parseOpenAiArgs(rawJson: String): Llm.LlmResponse? {
        val json = try {
            JsonParser.parseString(rawJson).asJsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val name = json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val args = json.get("arguments")?.let {
            if (it.isJsonObject) it.asJsonObject.toString() else it.asString
        } ?: "{}"
        return Llm.LlmResponse.ToolUse(
            toolCalls = listOf(Llm.ToolCall(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                arguments = args,
            )),
            reasoning = null,
        )
    }

    /**
     * Best-effort repair of truncated JSON: walks the string tracking string
     * state and open braces/brackets, then appends whatever closers are
     * missing. Returns null when the input never looked like an object.
     */
    fun repairJson(raw: String): String? {
        val cleaned = raw.trim()
        if (!cleaned.startsWith("{")) return null
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (ch in cleaned) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        val sb = StringBuilder(cleaned)
        if (escaped) sb.append('"')            // dangling backslash — neutralize
        if (inString) sb.append('"')           // close the open string
        // A trailing comma before closers is invalid JSON — strip it.
        while (sb.isNotEmpty() && sb.last() in ",:") sb.deleteCharAt(sb.length - 1)
        while (stack.isNotEmpty()) sb.append(stack.removeLast())
        return sb.toString()
    }

    /**
     * Parses tool calls emitted as markdown-fenced JSON or bare JSON.
     *
     * Observed from Qwen2.5-Coder via GenieX (on-device, S25):
     *   - Single fenced object:  ```json\n{"name":...,"arguments":{...}}\n```
     *   - Fenced array:           ```json\n[{...},{...}]\n```
     *   - Double-fenced (rare):   ```json\n```json\n{...}\n```\n```
     *   - Bare JSON:              {"name":...,"arguments":{...}}
     *
     * Gated on the presence of a "name" key so ordinary prose or JSON data
     * is never misparsed as a tool call.
     */
    private fun parseMarkdownOrBareJson(text: String): Llm.LlmResponse? {
        val trimmedText = text.trimStart()
        // Only try this path if the text looks like it contains a JSON tool
        // call: either wrapped in markdown code fences, or starting with {/[.
        if (!text.contains("```") && !trimmedText.startsWith("{") && !trimmedText.startsWith("[")) {
            return null
        }

        // 1. Try each markdown-fenced code block as a tool call. Handles the
        //    case where prose or non-tool JSON appears before the tool call.
        if (text.contains("```")) {
            val fenceRegex = Regex("```+(?:json)?\\s*([\\s\\S]*?)\\s*```+")
            for (match in fenceRegex.findAll(text)) {
                val content = match.groupValues[1].trim()
                if (content.startsWith("{") || content.startsWith("[")) {
                    parseToolCallJson(content)?.let { return it }
                }
            }
            // Double-fence fallback: strip ALL fence markers from the entire
            // text and try the result. Handles the nested-fence pattern
            // observed in the on-device screen recording.
            val fullyStripped = text.replace(Regex("```+\\s*(?:json)?\\s*"), "").trim()
            if (fullyStripped.startsWith("{") || fullyStripped.startsWith("[")) {
                parseToolCallJson(fullyStripped)?.let { return it }
            }
        }

        // 2. Try the whole text as bare JSON (only if it starts with { or [).
        if (trimmedText.startsWith("{") || trimmedText.startsWith("[")) {
            parseToolCallJson(trimmedText)?.let { return it }
        }

        // 3. Prose before bare JSON (e.g. "Sure! {\"name\":...}"): try from
        //    the first { or [.
        val jsonStart = trimmedText.indexOfAny(charArrayOf('{', '['))
        if (jsonStart > 0) {
            parseToolCallJson(trimmedText.substring(jsonStart).trim())?.let { return it }
        }

        return null
    }

    /**
     * Parses a JSON string (object or array) into a ToolUse response.
     * Returns null if the JSON doesn't have the tool-call shape
     * (i.e. no "name" key on any element).
     */
    private fun parseToolCallJson(jsonStr: String): Llm.LlmResponse? {
        val parsed = try {
            JsonParser.parseString(jsonStr)
        } catch (e: Exception) {
            // Truncated JSON — try brace-balancing repair.
            repairJson(jsonStr)?.let {
                runCatching { JsonParser.parseString(it) }.getOrNull()
            } ?: return null
        } ?: return null

        return when {
            parsed.isJsonObject -> {
                parseToolCallObject(parsed.asJsonObject)?.let { tc ->
                    Llm.LlmResponse.ToolUse(toolCalls = listOf(tc), reasoning = null)
                }
            }
            parsed.isJsonArray -> {
                val calls = parsed.asJsonArray.mapNotNull { elem ->
                    if (elem.isJsonObject) parseToolCallObject(elem.asJsonObject) else null
                }
                if (calls.isEmpty()) null
                else Llm.LlmResponse.ToolUse(toolCalls = calls, reasoning = null)
            }
            else -> null
        }
    }

    private fun parseToolCallObject(obj: JsonObject): Llm.ToolCall? {
        val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val args = obj.get("arguments")?.let {
            when {
                it.isJsonObject -> it.asJsonObject.toString()
                it.isJsonPrimitive -> it.asString
                else -> "{}"
            }
        } ?: "{}"
        return Llm.ToolCall(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            arguments = args,
        )
    }
}
