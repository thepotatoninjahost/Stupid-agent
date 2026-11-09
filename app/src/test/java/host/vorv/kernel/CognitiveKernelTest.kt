package host.vorv.kernel

import com.google.gson.JsonObject
import host.vorv.data.entity.ToolCall
import host.vorv.llm.Llm
import host.vorv.tools.Citation
import host.vorv.tools.Tool
import host.vorv.tools.ToolContext
import host.vorv.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
        // Night session 2026-11-08: the citation gate changed this test's
        // premise. The old version cited [cite:web:...] with ZERO tool
        // activity and expected a proof chip — that was exactly the
        // forged-citation exploit. Now the cite must be backed: the web
        // tool's output really contains the URL.
        val tool = FakeTool("web_search", ToolResult(true, "top result: https://x.fr", 1))
        val llm = FakeLlm(listOf(
            toolUse("web_search", """{"x":"q"}"""),
            Llm.LlmResponse.Text("Paris [cite:web:https://x.fr] is in France.", done = true),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertTrue(result.ok)
        assertFalse(result.answer.startsWith("[uncited]"))
        assertEquals(1, result.citations.size)
        assertEquals("web", result.citations[0].type)
        assertTrue(result.answer.contains("[1]"))
    }

    // ------------- night session 2026-11-08: the citation gate -------------

    @Test
    fun `unbacked inline citation is stripped to uncited and logged as forged`() = runTest {
        val sink = FakeSink()
        // Real memory ids are UUIDs — memory_recall prints "[id=<first 8
        // chars>]". M-4's evidence gate requires >= 4 chars, so the toy id
        // "42" of the pre-fix version would now (correctly) be unbackable.
        val tool = FakeTool("memory_recall", ToolResult(true, "[id=f81d4fae] topic: porch light", 1))
        val llm = FakeLlm(listOf(
            toolUse("memory_recall", """{"x":"porch"}"""),
            Llm.LlmResponse.Text("Porch light [cite:memory:f81d4fae], and Mars [cite:memory:deadbeef] too.", done = true),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertTrue(result.ok)
        assertEquals("only the backed cite survives", 1, result.citations.size)
        assertEquals("f81d4fae", result.citations[0].ref)
        assertTrue("backed cite is numbered", result.answer.contains("[1]"))
        assertTrue("forged cite becomes the honesty marker",
            result.answer.contains("Mars [uncited]"))
        assertFalse("one valid cite means the answer is not uncited-flagged",
            result.answer.startsWith("[uncited]"))
        assertTrue("the forgery is in the verifiable log",
            sink.episodes.any { it.first == "citation_forged" && it.second.contains("memory:deadbeef") })
    }

    @Test
    fun `answer whose only citation is forged gets the uncited flag`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(Llm.LlmResponse.Text(
            "Trust me [cite:memory:does-not-exist].", done = true)))
        val result = kernel(sink, emptyList()).run("t1", "q", llm, null)

        assertTrue(result.ok)
        assertTrue("no real evidence → the flag is back",
            result.answer.startsWith("[uncited]"))
        assertTrue(result.citations.isEmpty())
        assertTrue(sink.episodes.any { it.first == "citation_forged" })
    }

    @Test
    fun `exec citation is backed by a real exec round-trip`() = runTest {
        val sink = FakeSink()
        val tool = FakeTool("shell", ToolResult(true, "ok", 1))
        val llm = FakeLlm(listOf(
            toolUse("shell", """{"x":"ls"}"""),
            // The tool-call id lives in message metadata, not output text —
            // the gate backs exec cites on the round-trip itself.
            Llm.LlmResponse.Text("Ran it [cite:exec:call-1].", done = true),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertEquals(1, result.citations.size)
        assertFalse(result.answer.startsWith("[uncited]"))
        assertTrue(sink.episodes.none { it.first == "citation_forged" })
    }

    @Test
    fun `failed tool output is not citation evidence`() = runTest {
        val sink = FakeSink()
        // A read that FAILED names the file in its error — that must not
        // make the file citable (citing a file you never read is the
        // hallucination the gate exists for).
        val tool = FakeTool("file_read", ToolResult(false, "", 1, error = "No such file: notes.txt"))
        val llm = FakeLlm(listOf(
            toolUse("file_read", """{"x":"notes.txt"}"""),
            Llm.LlmResponse.Text("The notes [cite:file:notes.txt] say hi.", done = true),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertTrue(result.citations.isEmpty())
        assertTrue(result.answer.startsWith("[uncited]"))
        assertTrue(sink.episodes.any { it.first == "citation_forged" })
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
        // v0.6.13: the act-don't-advise rule is part of the standing prompt.
        assertTrue(systemMsg.content.contains("code block is NOT doing it"))
        // v0.6.14: raw tool output/JSON must never be pasted into the
        // user-facing answer (field finding: the model quoted
        // {"ok":true,"output":"No memories matched..."} verbatim in chat).
        assertTrue(systemMsg.content.contains("Never paste raw tool"))
        assertTrue(systemMsg.content.contains("plain English"))
        // v0.6.19: the anti-lecture rule and the pkg fact (field finding #8).
        assertTrue(systemMsg.content.contains("Never answer an action request with a lecture"))
        assertTrue(systemMsg.content.contains("pkg install -y"))
        // v0.7.3: the never-guess rule — the standing principle, pinned.
        assertTrue(systemMsg.content.contains("NEVER guess"))
        assertTrue(systemMsg.content.contains("file_list before file_read"))
        assertTrue(systemMsg.content.contains("\"should work\" claims are failures"))
    }

    // ------------------------------------------------ nudge (v0.6.13):
    // local coder models sometimes PROPOSE commands in a code block instead
    // of CALLING the tool. The kernel must correct that ONCE, and only when
    // nothing has been executed yet.

    @Test
    fun `bash-block answer with no tool call gets one nudge then the tool call runs`() = runTest {
        val sink = FakeSink()
        val executed = mutableListOf<String>()
        val tool = FakeTool("shell", onRun = { executed.add(it.toString()) })
        val llm = FakeLlm(listOf(
            // The exact failure from the user's screenshot: advice + ```bash,
            // no tool call.
            Llm.LlmResponse.Text(
                "You can run:\n```bash\npkg update && pkg upgrade\npkg install git\n```",
                done = true,
            ),
            // After the nudge the model does the right thing.
            toolUse("shell", """{"x":"pkg update"}"""),
            Llm.LlmResponse.Text("Installed. [cite:exec:shell]", done = true),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "install packages", llm, null)

        assertTrue(result.ok)
        assertEquals(1, result.toolCalls)
        assertEquals(1, executed.size)
        // The nudge episode is logged for the verifiable record.
        assertTrue(sink.episodes.any { it.first == "nudge" && it.second == "code-block-without-tool-call" })
        // The corrective message was actually sent to the model.
        assertEquals(3, llm.steps.size)
        val lastStep = llm.steps.last()
        assertTrue(lastStep.any { it.role == "user" && it.content.contains("no tool call") })
        // The final answer is cited (a tool really ran).
        assertFalse(result.answer.startsWith("[uncited]"))
    }

    @Test
    fun `nudge fires at most once — a second code-block answer is delivered as final`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(
            Llm.LlmResponse.Text("```bash\npkg update\n```", done = true),
            Llm.LlmResponse.Text("```bash\npkg update\n```", done = true),
        ))
        val result = kernel(sink, emptyList()).run("t1", "q", llm, null)

        // No infinite nudge loop: exactly one nudge, then the answer.
        assertEquals(2, llm.steps.size)
        assertTrue(result.ok)
        assertTrue(result.answer.startsWith("[uncited]"))
        assertEquals(1, sink.episodes.count { it.first == "nudge" })
    }

    @Test
    fun `no nudge when a tool already ran — code in the final summary is legitimate`() = runTest {
        val sink = FakeSink()
        val tool = FakeTool("shell")
        val llm = FakeLlm(listOf(
            toolUse("shell", """{"x":"ls"}"""),
            // Final answer after the tool ran, quoting what it did.
            Llm.LlmResponse.Text("Ran it. For reference: ```bash\nls\n```", done = true),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertEquals(2, llm.steps.size)
        assertTrue(sink.episodes.none { it.first == "nudge" })
        assertTrue(result.ok)
    }

    @Test
    fun `no nudge for a plain text answer without code blocks`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(Llm.LlmResponse.Text("Just an answer.", done = true)))
        kernel(sink, emptyList()).run("t1", "q", llm, null)

        assertEquals(1, llm.steps.size)
        assertTrue(sink.episodes.none { it.first == "nudge" })
    }

    @Test
    fun `python code block also triggers the nudge`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(
            Llm.LlmResponse.Text("```python\nprint('hi')\n```", done = true),
            Llm.LlmResponse.Text("Final advice, no code.", done = true),
        ))
        val result = kernel(sink, emptyList()).run("t1", "q", llm, null)

        assertEquals(2, llm.steps.size)
        assertEquals(1, sink.episodes.count { it.first == "nudge" })
        assertTrue(result.ok)
    }

    // ================== v0.6.19 lecture blind spot (field finding #8):
    // "termux works install the tools you need" was answered with an essay
    // about "learning language" — no tool call, no code block. The nudge
    // must catch PROSE non-answers to action requests too.

    @Test
    fun `prose lecture answering an action request gets one nudge then the tool runs`() = runTest {
        val sink = FakeSink()
        val tool = FakeTool("shell")
        val llm = FakeLlm(listOf(
            // The exact field failure: an essay, no tool call, no code block.
            Llm.LlmResponse.Text(
                "Let's begin with learning about language.\n\n" +
                    "### Step 1: Understanding Syntax\n" +
                    "Syntax refers to the rules that govern the structure of sentences " +
                    "in a language. Example: \"The cat eats fish.\"", done = true),
            // After the nudge the model does the right thing.
            toolUse("shell", """{"x":"pkg install -y git"}"""),
            Llm.LlmResponse.Text("Installed git. [cite:exec:shell]", done = true),
        ))
        val result = kernel(sink, listOf(tool)).run(
            "t1", "termux works install the tools you need", llm, null)

        // The corrective message was sent (3 LLM calls, not 1).
        assertEquals(3, llm.steps.size)
        val lastStep = llm.steps.last()
        assertTrue(lastStep.any { it.role == "user" && it.content.contains("no tool call") })
        assertTrue(lastStep.any { it.role == "user" && it.content.contains("pkg install -y") })
        // The lecture nudge is logged under its own name.
        assertTrue(sink.episodes.any {
            it.first == "nudge" && it.second == "action-request-without-tool-call" })
        // The tool actually ran and the answer is cited.
        assertEquals(1, result.toolCalls)
        assertFalse(result.answer.startsWith("[uncited]"))
    }

    @Test
    fun `lecture nudge fires at most once - a second essay is delivered as final`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(
            Llm.LlmResponse.Text("Step 1: Understanding Syntax. The cat eats fish.", done = true),
            Llm.LlmResponse.Text("Step 2: Understanding Reasoning. Socrates is mortal.", done = true),
        ))
        val result = kernel(sink, emptyList()).run("t1", "install the tools", llm, null)

        assertEquals(2, llm.steps.size)
        assertEquals(1, sink.episodes.count { it.first == "nudge" })
        assertTrue(result.ok)
        assertTrue(result.answer.startsWith("[uncited]"))
    }

    @Test
    fun `questions ending in a question mark never get the lecture nudge`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(Llm.LlmResponse.Text("Termux is a terminal emulator.", done = true)))
        kernel(sink, emptyList()).run("t1", "how do I install python in termux?", llm, null)

        assertEquals(1, llm.steps.size)
        assertTrue(sink.episodes.none { it.first == "nudge" })
    }

    @Test
    fun `action-request heuristic matches the field transcript and rejects chat`() {
        // The exact message from the field recording.
        assertTrue(CognitiveKernel.looksLikeActionRequest("termux works install the tools you need."))
        assertTrue(CognitiveKernel.looksLikeActionRequest("run ls for me"))
        assertTrue(CognitiveKernel.looksLikeActionRequest("please set up the workspace"))
        // Questions are information requests, not action orders.
        assertFalse(CognitiveKernel.looksLikeActionRequest("how do I install python?"))
        assertFalse(CognitiveKernel.looksLikeActionRequest("what is your name?"))
        assertFalse(CognitiveKernel.looksLikeActionRequest("tell me about termux"))
        // Word boundaries: "install" must not fire inside "installation".
        assertFalse(CognitiveKernel.looksLikeActionRequest("I love your installation of android"))
    }

    @Test
    fun `L1 - statements about a subject are never action requests`() {
        // The code-review examples: ordinary statements that mention action
        // verbs. The old anywhere-match nudged them into "call the tool NOW".
        assertFalse(CognitiveKernel.looksLikeActionRequest("the app runs slowly"))
        assertFalse(CognitiveKernel.looksLikeActionRequest("it won't update"))
        assertFalse(CognitiveKernel.looksLikeActionRequest("the phone gets hot when I run the camera"))
        assertFalse(CognitiveKernel.looksLikeActionRequest("my build pipeline fails on deploy"))
    }

    @Test
    fun `L1 - imperative position and lead-ins still fire`() {
        assertTrue(CognitiveKernel.looksLikeActionRequest("update the dependencies"))
        assertTrue(CognitiveKernel.looksLikeActionRequest("please install git"))
        assertTrue(CognitiveKernel.looksLikeActionRequest("vorv, delete the temp files"))
        assertTrue(CognitiveKernel.looksLikeActionRequest("i want you to clean the workspace"))
        // "you" is deliberately NOT a subject-statement opener: addressing
        // the agent IS a request.
        assertTrue(CognitiveKernel.looksLikeActionRequest("you should install git"))
        // No subject opener + verb mid-sentence = the field shape, still fires.
        assertTrue(CognitiveKernel.looksLikeActionRequest("termux works install the tools you need."))
    }

    // -------------------------------------- M2: honest truncation marker

    @Test
    fun `finish_reason length answer is delivered with an honest truncation marker`() = runTest {
        val sink = FakeSink()
        // done=false == the engine ran out of tokens (finish_reason=length).
        val llm = FakeLlm(listOf(Llm.LlmResponse.Text("The capital of France is Par", done = false)))
        val result = kernel(sink, emptyList()).run("t1", "what is the capital of france", llm, null)

        assertTrue("task completes (degraded, not dead)", result.ok)
        assertTrue(
            "answer must carry the truncation marker",
            result.answer.contains("truncated by the model's token limit"),
        )
        assertTrue(
            "episode log records the truncation",
            sink.episodes.any { it.first == "task_completed" && it.third.contains("\"truncated\":true") },
        )
    }

    @Test
    fun `complete answer carries no truncation marker`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(Llm.LlmResponse.Text("The capital of France is Paris.", done = true)))
        val result = kernel(sink, emptyList()).run("t1", "what is the capital of france", llm, null)

        assertTrue(result.ok)
        assertFalse(
            "done=true must not be marked truncated",
            result.answer.contains("truncated"),
        )
    }

    // -------------------------------------- M14: budget charged on dispatch

    @Test
    fun `invalid calls do not consume the per-tool rate budget`() = runTest {
        val ran = mutableListOf<String>()
        val tool = FakeTool(
            name = "needs_x",
            maxCallsPerTask = 2,
            onRun = { ran.add("ran") },
        ) // schema REQUIRES string "x"
        val bad = toolUse("needs_x", "{}")            // missing required x
        val good = toolUse("needs_x", """{"x":"1"}""")
        val llm = FakeLlm(listOf(bad, bad, good, Llm.LlmResponse.Text("done", done = true)))
        val sink = FakeSink()

        val result = kernel(sink, listOf(tool)).run("t1", "do it", llm, null)

        // Pre-M14: the two invalid attempts charged the budget (max 2), so
        // the VALID third call was rate-limited and the tool never ran.
        assertEquals("the valid call must dispatch despite two prior invalid ones", 1, ran.size)
        assertTrue(result.ok)
    }

    // ========================= v0.6.16 retry-loop backstop (field finding #4)

    /** A tool with a programmable per-call result — fail N times, then pass. */
    private class ScriptedTool(
        override val name: String,
        private val results: List<ToolResult>,
    ) : Tool {
        override val description = "scripted $name"
        private var i = 0
        override fun schema() = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("x", JsonObject().apply { addProperty("type", "string") })
            })
            add("required", com.google.gson.JsonArray().apply { add("x") })
        }
        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult =
            results[i++ % results.size]
    }

    private fun wallResult() = ToolResult(false, "", 1, error = "the wall: manual setup needed")

    @Test
    fun `three consecutive failures inject one corrective - three more after it force-finalize`() = runTest {
        val sink = FakeSink()
        // The model ignores the corrective and keeps calling the dead tool —
        // the recording's exact behavior, minus the 30s-per-retry cost (the
        // shell circuit breaker makes retries instant in production).
        val llm = FakeLlm(listOf(
            toolUse("shell", """{"x":"a"}"""),
            toolUse("shell", """{"x":"b"}"""),
            toolUse("shell", """{"x":"c"}"""),
            toolUse("shell", """{"x":"d"}"""),
            toolUse("shell", """{"x":"e"}"""),
            toolUse("shell", """{"x":"f"}"""),
            Llm.LlmResponse.Text("never reached", done = true),
        ))
        val tool = ScriptedTool("shell", List(6) { wallResult() })
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        // Six LLM calls: a,b,c fail (streak 3 → corrective, counters reset);
        // d,e,f fail (streak 3 again, corrective already used → force-final).
        assertEquals(6, llm.steps.size)
        // The first call AFTER the corrective must see the order in history.
        val fourthStepMessages = llm.steps[3]
        assertTrue(
            "the corrective must be in the 4th step's history",
            fourthStepMessages.any { m -> m.role == "user" && m.content.contains("STOP calling shell") },
        )
        assertEquals("retry_corrective episode logged once", 1, sink.episodes.count { it.first == "retry_corrective" })

        // The 6th failure (after the corrective) force-finalizes.
        assertFalse(result.ok)
        assertEquals("retry_loop", result.error)
        assertTrue("answer names the tool and the honest last error",
            result.answer.contains("shell") && result.answer.contains("the wall"))
    }

    @Test
    fun `a success in between resets the consecutive-failure counter`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(
            toolUse("shell", """{"x":"1"}"""),
            toolUse("shell", """{"x":"2"}"""),
            toolUse("shell", """{"x":"3"}"""),
            toolUse("shell", """{"x":"4"}"""),
            toolUse("shell", """{"x":"5"}"""),
            Llm.LlmResponse.Text("done", done = true),
        ))
        // fail, fail, SUCCESS (resets), fail, fail — never 3 in a row.
        val tool = ScriptedTool("shell", listOf(
            wallResult(), wallResult(),
            ToolResult(true, "ok", 1),
            wallResult(), wallResult(),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertEquals("all 5 calls + final answer", 6, llm.steps.size)
        assertTrue("no corrective — no 3-in-a-row streak", sink.episodes.none { it.first == "retry_corrective" })
        assertTrue(result.ok)
    }

    // ========================= v0.6.16 stop-button cancellation propagation

    /** A tool that suspends forever — the user presses STOP mid-tool. */
    private class StuckTool : Tool {
        override val name = "stuck"
        override val description = "never returns"
        override fun schema() = JsonObject().apply { addProperty("type", "object") }
        override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
            delay(Long.MAX_VALUE)
            return ToolResult(true, "late", 1)
        }
    }

    @Test
    fun `user stop propagates out of the kernel instead of being swallowed`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(toolUse("stuck", """{"x":"1"}""")))
        val kernel = kernel(sink, listOf(StuckTool()))

        var rethrown = false
        val job = launch {
            try {
                kernel.run("t1", "q", llm, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                rethrown = true // the ONLY acceptable outcome
            }
        }
        runCurrent()          // advance to the tool's suspension point
        job.cancel()          // the user presses STOP
        joinAll(job)          // wait for the cancellation to settle

        assertTrue("cancellation must PROPAGATE — a swallowed stop is the v0.6.15 bug", rethrown)
        assertTrue("stopped task is logged",
            sink.episodes.any { it.first == "task_stopped" && it.second.contains("cancelled by user") })
    }

    // ------------------------------------------- R3: pairing-safe compaction
    //
    // compactHistoryIfNeeded is internal on purpose: these tests pin the
    // window boundary directly, without a scripted LLM.

    private fun bigMsg(role: String, id: Int) =
        Llm.Message(role, "payload $id ".repeat(120)) // ~1 KB each

    /** R3 tests drive the kernel instance — compactHistoryIfNeeded is an
     *  internal instance function, not a companion member. */
    private fun compactor(): CognitiveKernel = kernel(FakeSink(), emptyList())

    @Test
    fun `compaction window never opens on a tool result`() {
        val call = Llm.ToolCall("c1", "file_read", "{\"path\":\"a\"}")
        val messages = mutableListOf(
            Llm.Message("system", "SYS"),
            Llm.Message("user", "THE-TASK"),
        )
        // Tuned so the NAIVE boundary (size - COMPACT_KEEP_RECENT) lands
        // exactly ON the tool result: the pair is followed by exactly
        // COMPACT_KEEP_RECENT-1 = 5 messages.
        repeat(20) { messages.add(bigMsg("assistant", it)) }
        messages.add(Llm.Message("assistant", "calling", listOf(call)))
        messages.add(Llm.Message("tool", "RESULT-BODY", toolCallId = "c1"))
        repeat(4) { messages.add(bigMsg("user", 100 + it)) }
        messages.add(Llm.Message("assistant", "THE-FINAL"))

        // Sanity: without the pairing fix the window opened on the tool msg.
        assertEquals(
            "test tuning: naive boundary must land on the tool result",
            "tool", messages[messages.size - CognitiveKernel.COMPACT_KEEP_RECENT].role,
        )

        compactor().compactHistoryIfNeeded(messages, 1_000)

        // The first kept message (index 3, after [system, task, digest]) must
        // NOT be a tool result — templates reject orphans (root cause RC-3).
        assertNotEquals("kept window must not start on an orphaned tool result", "tool", messages[3].role)
        assertTrue("digest must be present", messages[2].content.contains("digest"))
        assertTrue("recent turns survive", messages.size >= 4)
        assertTrue("the final answer survives",
            messages.any { it.role == "assistant" && it.content == "THE-FINAL" })
    }

    @Test
    fun `kept tool result still has its calling assistant adjacent`() {
        val call = Llm.ToolCall("c9", "shell", "{\"cmd\":\"ls\"}")
        val messages = mutableListOf(
            Llm.Message("system", "SYS"),
            Llm.Message("user", "THE-TASK"),
        )
        repeat(20) { messages.add(bigMsg("assistant", it)) }
        messages.add(Llm.Message("assistant", "calling", listOf(call)))
        messages.add(Llm.Message("tool", "RESULT-BODY", toolCallId = "c9"))
        // Again tuned: 5 messages after the pair put the tool result at the
        // naive boundary — the pre-R3 window kept it WITHOUT its caller.
        repeat(4) { messages.add(bigMsg("user", 100 + it)) }
        messages.add(Llm.Message("assistant", "final answer"))

        assertEquals(
            "test tuning: naive boundary must land on the tool result",
            "tool", messages[messages.size - CognitiveKernel.COMPACT_KEEP_RECENT].role,
        )

        compactor().compactHistoryIfNeeded(messages, 1_000)

        // Whatever the window, a kept tool result must be immediately
        // preceded by the assistant that called it.
        messages.forEachIndexed { i, m ->
            if (m.role == "tool") {
                assertTrue("tool result cannot be the first kept message", i >= 3)
                val prev = messages[i - 1]
                assertTrue(
                    "tool result (toolCallId=${m.toolCallId}) must follow its caller, got ${prev.role}",
                    prev.role == "assistant" && prev.toolCalls.any { it.id == m.toolCallId },
                )
            }
        }
        assertTrue("compaction must have folded the bulk", messages.size < 28)
    }

    @Test
    fun `under-budget history is untouched`() {
        val messages = mutableListOf(
            Llm.Message("system", "SYS"),
            Llm.Message("user", "small"),
            Llm.Message("assistant", "answer"),
        )
        compactor().compactHistoryIfNeeded(messages, 1_000_000)
        assertEquals(3, messages.size)
    }

    // -------- night session 2026-11-08, screen recording 20:32 --------
    // "did you see the crash report?" → "I don't have any crash report
    // information" ([uncited]) — no tool could reach the crash artifacts,
    // and the prompt never told the model to check. Two pins below: the
    // crash_logs tool is citable evidence, and rule 13 exists.

    @Test
    fun `crash_logs evidence backs a file citation`() = runTest {
        val sink = FakeSink()
        val reportPath = "crash_logs/crash_2026-11-07_20-31-00-000pid1234.txt"
        val tool = FakeTool("crash_logs", ToolResult(true, "newest: $reportPath (2 KB)", 1))
        val llm = FakeLlm(listOf(
            toolUse("crash_logs", """{"x":"list"}"""),
            Llm.LlmResponse.Text(
                "My last crash report [cite:file:$reportPath] shows what happened.",
                done = true,
            ),
        ))
        val result = kernel(sink, listOf(tool)).run("t1", "q", llm, null)

        assertTrue(result.ok)
        assertFalse(result.answer.startsWith("[uncited]"))
        assertEquals(1, result.citations.size)
        assertEquals("file", result.citations[0].type)
    }

    @Test
    fun `system prompt carries rule 13 - check before claiming absence`() = runTest {
        val sink = FakeSink()
        val llm = FakeLlm(listOf(Llm.LlmResponse.Text("ok", done = true)))
        kernel(sink, emptyList()).run("t1", "q", llm, null)

        val systemPrompt = llm.steps.first().first().content
        assertTrue(systemPrompt.contains("13."))
        assertTrue(systemPrompt.contains("crash_logs"))
        assertTrue(systemPrompt.contains("never ask the user to paste something a tool should provide"))
    }
}
