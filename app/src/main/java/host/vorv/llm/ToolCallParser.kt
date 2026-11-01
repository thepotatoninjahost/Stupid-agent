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
}
