package host.vorv.kernel

import com.google.gson.JsonObject
import host.vorv.tools.Tool
import host.vorv.tools.ToolContext
import host.vorv.tools.ToolResult
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallValidatorTest {

    private class FakeTool : Tool {
        override val name = "fake"
        override val description = "fake"
        override fun schema() = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("path", JsonObject().apply { addProperty("type", "string") })
                add("count", JsonObject().apply { addProperty("type", "integer") })
                add("ratio", JsonObject().apply { addProperty("type", "number") })
                add("flag", JsonObject().apply { addProperty("type", "boolean") })
            })
            add("required", com.google.gson.JsonArray().apply { add("path") })
        }
        override suspend fun run(input: JsonObject, ctx: ToolContext) = ToolResult(true, "ok", 0)
    }

    private val tool = FakeTool()

    private fun json(s: String): JsonObject =
        com.google.gson.JsonParser.parseString(s).asJsonObject

    @Test fun `valid input passes`() {
        assertNull(ToolCallValidator.validate(tool, json("""{"path":"a.txt","count":3}""")))
    }

    @Test fun `missing required field is rejected`() {
        val err = ToolCallValidator.validate(tool, json("""{"count":3}"""))
        assertNotNull(err)
        assertTrue(err!!.contains("path"))
    }

    @Test fun `null required field is rejected`() {
        assertNotNull(ToolCallValidator.validate(tool, json("""{"path":null}""")))
    }

    @Test fun `wrong type string vs integer is rejected`() {
        val err = ToolCallValidator.validate(tool, json("""{"path":"a","count":"three"}"""))
        assertNotNull(err)
        assertTrue(err!!.contains("count"))
    }

    @Test fun `integer accepted where number expected`() {
        assertNull(ToolCallValidator.validate(tool, json("""{"path":"a","ratio":2}""")))
    }

    @Test fun `float rejected where integer expected`() {
        assertNotNull(ToolCallValidator.validate(tool, json("""{"path":"a","count":2.5}""")))
    }

    @Test fun `boolean type checked`() {
        assertNull(ToolCallValidator.validate(tool, json("""{"path":"a","flag":true}""")))
        assertNotNull(ToolCallValidator.validate(tool, json("""{"path":"a","flag":"yes"}""")))
    }

    @Test fun `extra unknown fields are allowed`() {
        assertNull(ToolCallValidator.validate(tool, json("""{"path":"a","surprise":123}""")))
    }

    // Qwen's template writes every scalar as plain text, so the types in a
    // template-rendered call are the schema's to decide, not the text's.
    @Test fun `text arguments are matched to the declared types`() {
        val input = json("""{"path":2024,"count":"10","ratio":"0.5","flag":"True"}""")
        assertNotNull("uncoerced, the types do not match", ToolCallValidator.validate(tool, input))

        val args = ToolCallValidator.coerce(tool, input)
        assertNull(ToolCallValidator.validate(tool, args))
        assertTrue(args.get("path").asJsonPrimitive.isString)
        assertTrue(args.get("count").asJsonPrimitive.isNumber)
        assertTrue(args.get("ratio").asJsonPrimitive.isNumber)
        assertTrue(args.get("flag").asJsonPrimitive.isBoolean)
        assertTrue(args.get("flag").asBoolean)
    }

    @Test fun `a value that does not convert is still refused`() {
        val args = ToolCallValidator.coerce(tool, json("""{"path":"a","count":"three","flag":"yes"}"""))
        assertNotNull(ToolCallValidator.validate(tool, args))
    }

    @Test fun `coerce leaves a correctly typed call untouched`() {
        val input = json("""{"path":"a","count":3,"flag":false}""")
        assertTrue(ToolCallValidator.coerce(tool, input) === input)
    }

    @Test fun `coerce does not turn an unknown field into anything`() {
        val args = ToolCallValidator.coerce(tool, json("""{"path":"a","surprise":"7"}"""))
        assertTrue(args.get("surprise").asJsonPrimitive.isString)
    }

    @Test fun `schema without required accepts empty object`() {
        val loose = object : Tool {
            override val name = "loose"
            override val description = "loose"
            override fun schema() = JsonObject().apply { addProperty("type", "object") }
            override suspend fun run(input: JsonObject, ctx: ToolContext) = ToolResult(true, "ok", 0)
        }
        assertNull(ToolCallValidator.validate(loose, json("{}")))
    }
}
