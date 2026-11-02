package host.vorv.kernel

import com.google.gson.JsonObject
import host.vorv.data.entity.ToolCall
import host.vorv.llm.Llm
import host.vorv.tools.Citation
import host.vorv.tools.Tool
import host.vorv.tools.ToolContext
import host.vorv.tools.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end kernel tests with a scripted fake LLM, a fake event sink and
 * fake tools (audit finding H-6). No Room, no Android — pure JVM.
 */
class CognitiveKernelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------- fakes

    /** Scripted LLM: returns queued responses in order, records what it was asked. */
    private class FakeLlm(private val script: List<Llm.LlmResponse>) : Llm {
        override val label = "fake"
        val steps = mutableListOf<List<Llm.Message>>()
        private var i = 0
        override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse {
            steps.add(messages.toList())
            return script[i++]
        }
    }

    private class FakeTool(
        override val name: String,
        private val result: ToolResult = ToolResult(true, "did $name", 1),
        override val timeoutMs: Long = 60_000L,
        override val maxCallsPerTask: Int = 20,
        private val onRun: (JsonObject) -> Unit = {},
    ) : Tool {
        override val description = "fake $name"
        override fun schema() = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("x", JsonObject().apply { addProperty("type", "string") })
            })
            add("required", com.google.gson.JsonArray().apply { add("x") })
        }
        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
            onRun(input)
            return result
        }
    }

    private class FakeSink : KernelEventSink {
        val episodes = mutableListOf<Triple<String, String, String>>()
        val toolCalls = mutableListOf<ToolCall>()
        val skillResults = mutableListOf<Pair<String, Boolean>>()
        val iterationUpdates = mutableListOf<Pair<String, Int>>()
        override suspend fun logEpisode(kind: String, summary: String, detailsJson: String) {
            episodes.add(Triple(kind, summary, detailsJson))
        }
        override suspend fun insertToolCall(call: ToolCall) { toolCalls.add(call) }
        override suspend fun recordSkillResult(toolName: String, ok: Boolean) { skillResults.add(toolName to ok) }
        override suspend fun setTaskIterations(taskId: String, iterations: Int) {
            iterationUpdates.add(taskId to iterations)
        }
    }

    private fun toolUse(name: String, argsJson: String = "{}"): Llm.LlmResponse.ToolUse =
        Llm.LlmResponse.ToolUse(
            toolCalls = listOf(Llm.ToolCall(id = "call-1", name = name, arguments = argsJson)),
            reasoning = "need $name",
        )

    private fun kernel(sink: FakeSink, tools: List<Tool>): CognitiveKernel =
        CognitiveKernel(sink, tools, tmp.newFolder("outputs"))

    // ---------------------------------------------------- tests

    @Test
    fun `immediate text answer is returned and flagged uncited`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(Llm.LlmResponse.Text("Paris is the capital of France.", done = true)))
        val result = kernel(sink, emptyList()).run("t1", "q", llm, null)

        assertTrue(result.ok)
        assertTrue(result.answer.startsWith("[uncited]"))
        assertEquals(0, result.toolCalls)
        assertEquals("task_completed", sink.episodes.last().first)
        assertTrue(sink.episodes.last().second.contains("uncited"))
    }

    @Test
    fun `answer with inline citation is not flagged uncited`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(Llm.LlmResponse.Text("Paris [cite:web:https://x.fr] is in France.", done = true)))
        val result = kernel(sink, emptyList()).run("t1", "q", llm, null)

        assertTrue(result.ok)
        assertFalse(result.answer.startsWith("[uncited]"))
        assertEquals(1, result.citations.size)
        assertEquals("web", result.citations[0].type)
        assertTrue(result.answer.contains("[1]"))
    }

    @Test
    fun `tool call executes the tool and feeds the real result back`() = runTest {
        val sink = FakeSink()
        val executed = mutableListOf<String>()
        val tool = FakeTool("memory_write", onRun = { executed.add(it.toString()) })
        val llm = FakeLlm(listOf(
            toolUse("memory_write", """{"x":"v"}"""),
            Llm.LlmResponse.Text("Stored it. [cite:memory:abc]", done = true),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertEquals(1, executed.size)
        assertEquals(1, sink.toolCalls.size)
        assertEquals("memory_write", sink.toolCalls[0].toolName)
        assertEquals("did memory_write", sink.toolCalls[0].outputJson)
        // The second LLM call must contain the REAL tool output in a tool message.
        val toolMsg = llm.steps[1].last()
        assertEquals("tool", toolMsg.role)
        assertTrue(toolMsg.content.contains("did memory_write"))
        assertTrue(result.ok)
        assertEquals(1, result.toolCalls)
    }

    @Test
    fun `tool citation suppresses the uncited flag`() = runTest {
        val sink = FakeSink()
        val tool = FakeTool("web_fetch", ToolResult(true, "page text", 1, citation = Citation("web", "https://x", "web")))
        val llm = FakeLlm(listOf(
            toolUse("web_fetch", """{"x":"u"}"""),
            Llm.LlmResponse.Text("Based on the page, yes.", done = true),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertFalse(result.answer.startsWith("[uncited]"))
        assertEquals(1, result.citations.size)
    }

    @Test
    fun `unknown tool produces an honest error message to the llm`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(
            toolUse("does_not_exist"),
            Llm.LlmResponse.Text("That tool doesn't exist, so I can't.", done = true),
        ))
        val result = kernel(sink, emptyList()).run("t1", "q", llm, null)

        val toolMsg = llm.steps[1].last()
        assertTrue(toolMsg.content.contains("Unknown tool"))
        assertEquals(0, sink.toolCalls.size)
        assertTrue(result.ok)
    }

    @Test
    fun `invalid arguments are rejected by the validator before dispatch`() = runTest {
        val sink = FakeSink()
        var ran = false
        val tool = FakeTool("memory_write", onRun = { ran = true })
        val llm = FakeLlm(listOf(
            toolUse("memory_write", """{"wrong":1}"""),   // missing required "x"
            Llm.LlmResponse.Text("ok", done = true),
        ))
        kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertFalse("tool must NOT run with invalid args", ran)
        val toolMsg = llm.steps[1].last()
        assertTrue(toolMsg.content.contains("missing required argument 'x'"))
    }

    @Test
    fun `tool exception is caught and reported honestly`() = runTest {
        val sink = FakeSink()
        val tool = object : Tool {
            override val name = "boom"
            override val description = "always throws"
            override fun schema() = JsonObject().apply { addProperty("type", "object") }
            override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult =
                throw IllegalStateException("kaboom")
        }
        val llm = FakeLlm(listOf(
            toolUse("boom"),
            Llm.LlmResponse.Text("It failed with kaboom.", done = true),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        val toolMsg = llm.steps[1].last()
        assertTrue(toolMsg.content.contains("kaboom"))
        assertEquals(1, sink.toolCalls.size)
        assertEquals("kaboom", sink.toolCalls[0].errorMessage)
    }

    @Test
    fun `per-tool rate limit is enforced with an honest message`() = runTest {
        val sink = FakeSink()
        val tool = FakeTool("ping", maxCallsPerTask = 2)
        // Args must satisfy FakeTool's schema (required "x") — otherwise the
        // validator (correctly) rejects the calls and nothing executes.
        val args = """{"x":"v"}"""
        val llm = FakeLlm(listOf(
            toolUse("ping", args), toolUse("ping", args), toolUse("ping", args), toolUse("ping", args),
            Llm.LlmResponse.Text("done", done = true),
        ))
        kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertEquals("only 2 of 4 calls may execute", 2, sink.toolCalls.size)
        val thirdMsg = llm.steps[3].last()
        assertTrue(thirdMsg.content.contains("Rate limit"))
    }

    @Test
    fun `tool exceeding its wall-clock timeout gets a timeout error`() = runTest {
        val sink = FakeSink()
        val tool = object : Tool {
            override val name = "slow"
            override val description = "sleeps"
            override val timeoutMs: Long get() = 100L
            override fun schema() = JsonObject().apply { addProperty("type", "object") }
            override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
                kotlinx.coroutines.delay(5_000)
                return ToolResult(true, "never", 0)
            }
        }
        val llm = FakeLlm(listOf(
            toolUse("slow"),
            Llm.LlmResponse.Text("it timed out", done = true),
        ))
        kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertEquals(1, sink.toolCalls.size)
        assertNotNull(sink.toolCalls[0].errorMessage)
        assertTrue(sink.toolCalls[0].errorMessage!!.contains("timed out"))
    }

    @Test
    fun `long outputs are persisted to a file with a preview in the db`() = runTest {
        val sink = FakeSink()
        val big = "z".repeat(5_000)
        val tool = FakeTool("file_read", ToolResult(true, big, 1))
        val llm = FakeLlm(listOf(
            toolUse("file_read", """{"x":"f"}"""),
            Llm.LlmResponse.Text("read it", done = true),
        ))
        val k = CognitiveKernel(sink, listOf(tool), tmp.newFolder("full"))
        k.run("t-long", "q", llm, null)

        assertEquals(1, sink.toolCalls.size)
        val call = sink.toolCalls[0]
        assertEquals(2000, call.outputJson.length)
        assertNotNull(call.outputFilePath)
        val file = File(call.outputFilePath!!)
        assertTrue(file.exists())
        assertEquals(5_000, file.readText().length)
    }

    /**
     * N-M2 (VRA-2026-1103): two calls to the SAME tool within one assistant
     * turn (parallel tool calls) must persist to two DISTINCT files — the old
     * `<iteration>_<tool>.txt` name let the second call overwrite the first
     * while both DB rows pointed at the same file.
     */
    @Test
    fun `parallel calls to the same tool persist distinct output files`() = runTest {
        val sink = FakeSink()
        val bigA = "A".repeat(5_000)
        val bigB = "B".repeat(5_000)
        val scripted = object : Llm {
            override val label = "scripted"
            private var i = 0
            override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse {
                return when (i++) {
                    0 -> Llm.LlmResponse.ToolUse(
                        listOf(
                            Llm.ToolCall("c1", "file_read", """{"x":"a"}"""),
                            Llm.ToolCall("c2", "file_read", """{"x":"b"}"""),
                        ), reasoning = "both")
                    else -> Llm.LlmResponse.Text("done", done = true)
                }
            }
        }
        // Returns output A for the first invocation, B for the second.
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val countingTool = object : Tool {
            override val name = "file_read"
            override val description = "counting"
            override fun schema() = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("x", JsonObject().apply { addProperty("type", "string") })
                })
                add("required", com.google.gson.JsonArray().apply { add("x") })
            }
            override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
                return if (calls.getAndIncrement() == 0) ToolResult(true, bigA, 1)
                else ToolResult(true, bigB, 1)
            }
        }
        val k = CognitiveKernel(sink, listOf(countingTool), tmp.newFolder("parallel"))
        k.run("t-parallel", "q", scripted, null)

        assertEquals(2, sink.toolCalls.size)
        val paths = sink.toolCalls.mapNotNull { it.outputFilePath }
        assertEquals("both long outputs must be persisted", 2, paths.size)
        assertNotEquals("the two calls must NOT share one file", paths[0], paths[1])
        val contents = paths.map { File(it).readText() }
        assertTrue("first file holds call A output", contents.any { it.startsWith("A") })
        assertTrue("second file holds call B output", contents.any { it.startsWith("B") })
    }

    @Test
    fun `max iterations produces a stuck result`() = runTest {
        val sink = FakeSink()
        val tool = FakeTool("loop")
        // 25 valid tool calls, cap is 20 iterations.
        val script = (0 until 25).map { toolUse("loop", """{"x":"v"}""") }
        val llm = FakeLlm(script)
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertFalse(result.ok)
        assertEquals("max_iterations", result.error)
        assertEquals(20, result.iterations)
    }

    @Test
    fun `confirm bridge is passed through to tools`() = runTest {
        val sink = FakeSink()
        var confirmSeen: (suspend (String) -> Boolean)? = null
        val tool = object : Tool {
            override val name = "ask"
            override val description = "asks"
            override fun schema() = JsonObject().apply { addProperty("type", "object") }
            override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
                confirmSeen = ctx.confirm
                return if (ctx.confirm?.invoke("do it?") == true) ToolResult(true, "approved", 1)
                else ToolResult(false, "", 1, error = "denied")
            }
        }
        val llm = FakeLlm(listOf(toolUse("ask"), Llm.LlmResponse.Text("done", true)))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null, confirm = { true })

        assertNotNull("confirm must reach the tool", confirmSeen)
        assertTrue(result.ok)
    }

    @Test
    fun `llm failure breaks the loop with an error result`() = runTest {
        val sink = FakeSink()
        val llm = object : Llm {
            override val label = "failing"
            override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>) =
                throw RuntimeException("network down")
        }
        val result = kernel(sink, emptyList()).run("t1", "q", llm, null)

        assertFalse(result.ok)
        assertEquals("network down", result.error)
    }

    @Test
    fun `system prompt contains the untrusted-data rule and tool list`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(Llm.LlmResponse.Text("ok", true)))
        kernel(sink, listOf(FakeTool("alpha"))).run("t1", "q", llm, null)

        val systemMsg = llm.steps[0].first()
        assertEquals("system", systemMsg.role)
        assertTrue(systemMsg.content.contains("UNTRUSTED DATA"))
        assertTrue(systemMsg.content.contains("- alpha:"))
        assertTrue(systemMsg.content.contains("confirmation"))
    }
}
