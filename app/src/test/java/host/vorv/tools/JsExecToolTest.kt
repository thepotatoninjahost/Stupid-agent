package host.vorv.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test fun `Rhino shell classes via Packages remain inert`() {
        val r = runJs("""
            console.log(typeof Packages.org.mozilla.javascript.tools.shell.Main);
            console.log(typeof Packages.org.mozilla.javascript.tools.shell.Global);
            console.log(typeof Packages.org.mozilla.javascript.tools.shell.Environment);
        """.trimIndent())
        assertTrue(r.describe(), r.ok)
        assertTrue(r.describe(), r.output.lines().take(3) == listOf("object", "object", "object"))
    }

    @Test fun `Rhino shell entry point via Packages is blocked`() {
        val r = runJs("Packages.org.mozilla.javascript.tools.shell.Main.getGlobal();")
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

    // ---------------------------------------------------- Rhino rollback regressions

    @Test fun `toFixed preserves rounding and trailing zeroes`() {
        val r = runJs("""
            console.log(JSON.stringify([
                (1.25).toFixed(1), (-1.25).toFixed(1),
                (12).toFixed(2), (0).toFixed(2), (-0).toFixed(2)
            ]));
        """.trimIndent())
        assertTrue(r.describe(), r.ok)
        assertEquals("[\"1.3\",\"-1.3\",\"12.00\",\"0.00\",\"0.00\"]", r.output)
    }

    @Test fun `toFixed coerces precision and defaults to zero digits`() {
        val r = runJs("""
            console.log(JSON.stringify([
                (1.25).toFixed(), (1.25).toFixed(undefined),
                (1.25).toFixed(null), (1.25).toFixed(NaN),
                (1.25).toFixed('2'), (1.25).toFixed(2.9)
            ]));
        """.trimIndent())
        assertTrue(r.describe(), r.ok)
        assertEquals("[\"1\",\"1\",\"1\",\"1\",\"1.25\",\"1.25\"]", r.output)
    }

    @Test fun `toFixed accepts both precision boundaries`() {
        val r = runJs("""
            console.log((1.5).toFixed(0));
            console.log((1).toFixed(100));
        """.trimIndent())
        assertTrue(r.describe(), r.ok)
        assertEquals("2\n1." + "0".repeat(100), r.output)
    }

    @Test fun `toFixed rejects invalid precision as RangeError before formatting`() {
        // Keep inputs bounded: the upper boundary exercises the validation
        // protecting the native formatter without allocating enormous strings.
        for (precision in listOf("-1", "101", "Infinity", "-Infinity")) {
            val r = runJs("""
                try {
                    (1).toFixed($precision);
                    console.log('unexpected success');
                } catch (e) {
                    console.log(e.name);
                }
            """.trimIndent())
            assertTrue("precision=$precision ${r.describe()}", r.ok)
            assertEquals("precision=$precision ${r.describe()}", "RangeError", r.output)
        }
    }

    @Test fun `uncaught toFixed range error is a tool failure and next run succeeds`() {
        val failed = runJs("(1).toFixed(101)")
        assertFalse(failed.describe(), failed.ok)
        assertTrue(failed.describe(), failed.error.orEmpty().contains("RangeError"))
        assertEquals("(no output)", failed.output)

        val recovered = runJs("(1.25).toFixed(2)")
        assertTrue(recovered.describe(), recovered.ok)
        assertEquals("=> 1.25", recovered.output)
    }

    @Test fun `toFixed handles nonfinite numbers and exponential threshold`() {
        val r = runJs("""
            console.log(JSON.stringify([
                (NaN).toFixed(2), (Infinity).toFixed(2),
                (-Infinity).toFixed(2), (1e21).toFixed(2), (1e20).toFixed(2)
            ]));
        """.trimIndent())
        assertTrue(r.describe(), r.ok)
        assertEquals(
            "[\"NaN\",\"Infinity\",\"-Infinity\",\"1e+21\",\"100000000000000000000.00\"]",
            r.output,
        )
    }

    @Test fun `toFixed rejects nonnumeric receiver`() {
        val r = runJs("""
            try {
                Number.prototype.toFixed.call('1.25', 2);
                console.log('unexpected success');
            } catch (e) {
                console.log(e.name);
            }
        """.trimIndent())
        assertTrue(r.describe(), r.ok)
        assertEquals("TypeError", r.output)
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
