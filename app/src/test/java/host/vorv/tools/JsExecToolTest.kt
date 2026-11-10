package host.vorv.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attack + behavior tests for the Rhino sandbox (audit findings C-1, H-3).
 * These run on the plain JVM — Rhino has no Android dependency.
 *
 * The sandbox contract (see JsExecTool KDoc):
 *  - every Java class access path is denied by the ClassShutter
 *  - the Packages namespace may be VISIBLE (Rhino's lazy package machinery)
 *    but is INERT — no class ever materializes through it
 *  - standard ECMAScript + console work, including const/let/var/function
 *    declarations in the child scope
 *  - CPU loops are stopped by the instruction watchdog; blocking calls are
 *    bounded by the join deadline
 */
class JsExecToolTest {

    private val tool = JsExecTool()
    private val ctx = ToolContext(taskId = "test", iteration = 1, workspaceUri = null)

    private fun runJs(code: String): ToolResult = runBlocking {
        tool.run(JsonObject().apply { addProperty("code", code) }, ctx)
    }

    /** Assertion context: carries the real sandbox error/output into failure reports. */
    private fun ToolResult.describe() = "ok=$ok err=$error out=${output.take(120)} stderr=${stderr.take(120)}"

    // ---------------------------------------------------- sandbox escapes: BLOCK

    @Test fun `Packages java io File read is blocked`() {
        val r = runJs("""
            var s = new Packages.java.util.Scanner(new Packages.java.io.File('/data/data/host.vorv/databases/vorv.db')).useDelimiter('\\A').next();
            console.log(s);
        """.trimIndent())
        assertFalse(r.describe(), r.ok)
    }

    @Test fun `java lang Runtime exec is blocked`() {
        val r = runJs("""var p = java.lang.Runtime.getRuntime().exec('ls');""")
        assertFalse(r.describe(), r.ok)
    }

    @Test fun `System exit is blocked`() {
        val r = runJs("""java.lang.System.exit(0);""")
        assertFalse(r.describe(), r.ok)
    }

    @Test fun `Packages namespace is visible but inert`() {
        // Rhino's lazy package machinery can re-expose the root namespace
        // object after deletion; the ClassShutter must keep it inert —
        // no class may materialize through ANY traversal path.
        val r = runJs("""
            var j = Packages.java.lang;
            j.System.exit(0);
        """.trimIndent())
        assertFalse(r.describe(), r.ok)
    }

    @Test fun `Packages Class forName is blocked`() {
        val r = runJs("""var c = Packages.java.lang.Class.forName('java.lang.Runtime'); console.log(c);""")
        assertFalse(r.describe(), r.ok)
    }

    @Test fun `importClass escape is blocked`() {
        val r = runJs("""importClass(java.lang.Runtime);""")
        assertFalse(r.describe(), r.ok)
    }

    @Test fun `importPackage escape is blocked`() {
        val r = runJs("""importPackage(java.io); console.log(File);""")
        assertFalse(r.describe(), r.ok)
    }

    @Test fun `JavaAdapter escape is blocked`() {
        val r = runJs("""var x = new JavaAdapter(java.lang.Runnable, {run:function(){}});""")
        assertFalse(r.describe(), r.ok)
    }

    @Test fun `reflection via console getClass is blocked`() {
        val r = runJs("""var c = console.getClass().forName('java.lang.Runtime'); console.log(c);""")
        assertFalse(r.describe(), r.ok)
    }

    @Test fun `ProcessBuilder via Packages is blocked`() {
        val r = runJs("""var pb = new Packages.java.lang.ProcessBuilder('sh','-c','ls'); pb.start();""")
        assertFalse(r.describe(), r.ok)
    }

    // ---------------------------------------------------- legitimate code: ALLOW

    @Test fun `arithmetic and console log work`() {
        val r = runJs("console.log(2 + 2);")
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.contains("4"))
    }

    @Test fun `JSON builtins work`() {
        val r = runJs("""console.log(JSON.stringify({a: 1}));""")
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.contains("\"a\":1"))
    }

    @Test fun `string manipulation works`() {
        val r = runJs("""console.log("hello".toUpperCase());""")
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.contains("HELLO"))
    }

    @Test fun `es6 arrow functions work`() {
        val r = runJs("const f = (x) => x * 2; console.log(f(21));")
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.contains("42"))
    }

    @Test fun `var and let declarations work`() {
        val r = runJs("var total = 0; for (var i = 0; i < 5; i++) { total += i; } let double = total * 2; console.log(double);")
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.contains("20"))
    }

    @Test fun `function declarations work`() {
        val r = runJs("function add(a, b) { return a + b; } console.log(add(3, 4));")
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.contains("7"))
    }

    @Test fun `array methods work`() {
        val r = runJs("console.log([1,2,3].map(x => x * 2).join(','));")
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.contains("2,4,6"))
    }

    @Test fun `console warn and error go to stderr`() {
        val r = runJs("console.warn('careful'); console.error('broken');")
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.stderr.contains("WARN: careful"))
        assertTrue(r.describe(), r.stderr.contains("ERR: broken"))
    }

    @Test fun `return value is captured`() {
        val r = runJs("6 * 7")
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.contains("42"))
    }

    @Test fun `syntax error reported honestly`() {
        val r = runJs("function f(:")
        assertFalse(r.describe(), r.ok)
        assertTrue(r.describe(), r.error!!.isNotBlank())
    }

    // ---------------------------------------------------- budget enforcement

    @Test fun `infinite loop is stopped by the instruction watchdog`() {
        val start = System.currentTimeMillis()
        val r = runJs("while (true) { }")
        val elapsed = System.currentTimeMillis() - start
        assertFalse(r.describe(), r.ok)
        assertTrue(r.describe(), r.error!!.contains("timed out", ignoreCase = true))
        // Watchdog fires at 5s + observer granularity; join deadline bounds the rest.
        assertTrue("watchdog took ${elapsed}ms", elapsed < 15_000)
    }

    @Test fun `stdout and stderr share an output budget`() {
        val r = runJs("var s = 'x'.repeat(20000); console.log(s); console.warn(s); console.error(s); console.info(s);")
        assertFalse(r.describe(), r.ok)
        assertTrue(r.describe(), r.error!!.contains("output limit"))
        assertTrue(r.describe(), r.output.length + r.stderr.length <= 65536)
    }

    @Test fun `caught console failures cannot resume output collection`() {
        val r = runJs("""
            for (var i = 0; i < 100; i++) {
                try { console.log('x'.repeat(10000)); } catch (e) { }
            }
        """.trimIndent())
        assertFalse(r.describe(), r.ok)
        assertTrue(r.describe(), r.error!!.contains("output limit"))
        assertTrue(r.describe(), r.output.length <= 65536)
    }

    @Test fun `oversized lazy string and return value are rejected`() {
        for (code in listOf("console.log('x'.repeat(1000000));", "'x'.repeat(1000000)")) {
            val r = runJs(code)
            assertFalse(r.describe(), r.ok)
            assertTrue(r.describe(), r.error!!.contains("output limit"))
            assertTrue(r.describe(), r.output.length < 66000)
        }
    }

    @Test fun `combined stdout and return value have a separate persistence cap`() {
        val r = runJs("console.log('x'.repeat(60000)); 'y'.repeat(60000)")
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.length < 66000)
        assertTrue(r.describe(), r.output.contains("truncated at 65536 chars"))
    }

    @Test fun `unicode output and large errors stay bounded`() {
        val r = runJs("for (var i = 0; i < 1000; i++) console.log('界'.repeat(1000));")
        assertFalse(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.toByteArray(Charsets.UTF_8).size <= 196608)
        val error = runJs("throw new Error('x'.repeat(1000000));")
        assertFalse(error.describe(), error.ok)
        assertTrue(error.describe(), error.error!!.length < 66000)
    }

    @Test fun `missing code argument is an honest error`() {
        val r = runBlocking {
            tool.run(JsonObject(), ctx)
        }
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("missing code"))
    }

    // ---------------------------------------------------- M-11: Errors are results

    @Test fun `hard StackOverflowError is reported as failure, never a fake success (M-11)`() {
        // JSON.parse recurses in Rhino's native Java JSON parser, which has no
        // depth guard — verified against the pinned Rhino 1.7.15.1 jar: deep
        // nesting throws a RAW java.lang.StackOverflowError (an Error, not an
        // Exception, and no instruction is counted so no timeout fires).
        // The old `catch (Exception)` let that Error kill the worker thread
        // without setting errorRef, and the tool then reported ok=true with
        // output "(no output)" — a fabricated success for exactly the failure
        // the sandbox exists to catch. The widened catch makes the failure a
        // RESULT. (For contrast: deep paren nesting is depth-guarded by
        // Rhino's parser itself and comes back as an EvaluatorException.)
        val depth = 100_000
        val r = runJs("JSON.parse('['.repeat($depth) + ']'.repeat($depth))")
        assertFalse(
            "a hard evaluation failure must never come back as ok=true — ${r.describe()}",
            r.ok,
        )
        assertTrue(
            "the error channel must carry the failure — ${r.describe()}",
            (r.error ?: "").contains("StackOverflow"),
        )
    }
}
