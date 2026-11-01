package host.vorv.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven tests for the local-LLM tool-call parser (audit H-6 + M-1).
 */
class ToolCallParserTest {

    private fun parseName(res: Llm.LlmResponse): String? =
        (res as? Llm.LlmResponse.ToolUse)?.toolCalls?.firstOrNull()?.name

    private fun parseArgs(res: Llm.LlmResponse): String? =
        (res as? Llm.LlmResponse.ToolUse)?.toolCalls?.firstOrNull()?.arguments

    private fun parseText(res: Llm.LlmResponse): String? =
        (res as? Llm.LlmResponse.Text)?.content

    // ---------------------------------------------------- well-formed <tool>

    @Test fun `parses well-formed openai tool call`() {
        val r = ToolCallParser.parse("""<tool>{"name":"file_read","arguments":{"path":"a.txt"}}</tool>""")
        assertEquals("file_read", parseName(r))
        assertTrue(parseArgs(r)!!.contains("a.txt"))
    }

    @Test fun `parses tool call with whitespace`() {
        val r = ToolCallParser.parse("  <tool>  {\"name\":\"js_exec\",\"arguments\":{\"code\":\"1+1\"}}  </tool>  ")
        assertEquals("js_exec", parseName(r))
    }

    @Test fun `tool call with multiline json arguments`() {
        val r = ToolCallParser.parse(
            "<tool>{\n  \"name\": \"memory_write\",\n  \"arguments\": {\n    \"topic\": \"t\",\n    \"content\": \"c\"\n  }\n}</tool>"
        )
        assertEquals("memory_write", parseName(r))
    }

    @Test fun `tool call with missing arguments key defaults to empty object`() {
        val r = ToolCallParser.parse("""<tool>{"name":"file_list"}</tool>""")
        assertEquals("file_list", parseName(r))
        assertEquals("{}", parseArgs(r))
    }

    // ---------------------------------------------------- truncated <tool>

    @Test fun `repairs truncated tool call missing closing tag and braces`() {
        val r = ToolCallParser.parse("""<tool>{"name":"file_write","arguments":{"path":"out.txt","content":"hello""")
        assertEquals("file_write", parseName(r))
        assertTrue(parseArgs(r)!!.contains("out.txt"))
        assertTrue(parseArgs(r)!!.contains("hello"))
    }

    @Test fun `repairs truncation inside a string value`() {
        val r = ToolCallParser.parse("""<tool>{"name":"file_write","arguments":{"path":"o.txt","content":"line1\nline2""")
        assertEquals("file_write", parseName(r))
    }

    @Test fun `truncated garbage after tool tag falls back to text`() {
        val r = ToolCallParser.parse("""<tool>this is not json at all""")
        assertTrue(r is Llm.LlmResponse.Text)
    }

    // ---------------------------------------------------- plain protocol

    @Test fun `parses plain TOOL_CALL newline ARGS format`() {
        val r = ToolCallParser.parse("TOOL_CALL: memory_write\nARGS: {\"topic\":\"t\",\"content\":\"c\"}")
        assertEquals("memory_write", parseName(r))
    }

    @Test fun `parses plain TOOL_CALL colon ARGS format`() {
        val r = ToolCallParser.parse("TOOL_CALL: js_exec ARGS: {\"code\":\"1\"}")
        assertEquals("js_exec", parseName(r))
    }

    @Test fun `plain format with invalid json args produces error payload not crash`() {
        val r = ToolCallParser.parse("TOOL_CALL: js_exec ARGS: {not json")
        assertEquals("js_exec", parseName(r))
        assertTrue(parseArgs(r)!!.contains("error"))
    }

    // ---------------------------------------------------- answers

    @Test fun `parses ANSWER prefix`() {
        val r = ToolCallParser.parse("ANSWER: The file has 42 lines.")
        assertEquals("The file has 42 lines.", parseText(r))
    }

    @Test fun `raw text without markers is a final answer`() {
        val r = ToolCallParser.parse("The capital of France is Paris.")
        assertEquals("The capital of France is Paris.", parseText(r))
    }

    @Test fun `empty text returns empty answer`() {
        val r = ToolCallParser.parse("   ")
        assertEquals("", parseText(r))
    }

    @Test fun `text before tool tag does not prevent tool parsing`() {
        val r = ToolCallParser.parse("Let me check that.\n<tool>{\"name\":\"file_read\",\"arguments\":{\"path\":\"x\"}}</tool>")
        assertEquals("file_read", parseName(r))
    }

    // ---------------------------------------------------- repairJson unit

    @Test fun `repairJson closes braces and string`() {
        assertEquals("""{"a":"b"}""", ToolCallParser.repairJson("""{"a":"b""""))
    }

    @Test fun `repairJson closes nested structures`() {
        assertEquals("""{"a":{"b":1}}""", ToolCallParser.repairJson("""{"a":{"b":1"""))
    }

    @Test fun `repairJson strips trailing comma`() {
        assertEquals("""{"a":1}""", ToolCallParser.repairJson("""{"a":1,"""))
    }

    @Test fun `repairJson returns null for non-object`() {
        assertEquals(null, ToolCallParser.repairJson("just text"))
    }

    @Test fun `repairJson handles escaped quote in string`() {
        assertEquals("""{"a":"b\"c"}""", ToolCallParser.repairJson("""{"a":"b\"c""""))
    }

    @Test fun `repairJson handles brackets`() {
        assertEquals("""{"a":[1,2]}""", ToolCallParser.repairJson("""{"a":[1,2"""))
    }
}
