package host.vorv.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attack tests for the Rhino sandbox (audit findings C-1, H-3).
 * These run on the plain JVM — Rhino has no Android dependency.
 */
class JsExecToolTest {

    private val tool = JsExecTool()
    private val ctx = ToolContext(taskId = "test", iteration = 1, workspaceUri = null)

    private fun runJs(code: String): ToolResult = kotlinx.coroutines.runBlocking {
        tool.run(JsonObject().apply { addProperty("code", code) }, ctx)
    }

    // ---------------------------------------------------- sandbox escapes: BLOCK

    @Test fun `Packages java lang Runtime is blocked`() {
        val r = runJs("""var out = java.lang.Runtime.getRuntime().exec("ls"); console.log(out);""")
        assertFalse(r.ok)
        assertTrue(r.error!!.isNotBlank())
    }

    @Test fun `Packages escape is blocked`() {
        val r = runJs("""var f = new Packages.java.io.File("/data/data/host.vorv/databases/vorv.db");
            console.log(f.exists());""")
        assertFalse(r.ok)
    }

    @Test fun `reading the app database via java io File is blocked`() {
        val r = runJs("""
            var scanner = new java.util.Scanner(new java.io.File("/data/data/host.vorv/files/datastore/vorv_prefs.preferences_pb"));
            console.log(scanner.next());
        """.trimIndent())
        assertFalse(r.ok)
    }

    @Test fun `System exit is blocked`() {
        val r = runJs("""java.lang.System.exit(0);""")
        assertFalse(r.ok)
    }

    @Test fun `importClass escape is blocked`() {
        val r = runJs("""importClass(java.lang.Runtime);""")
        assertFalse(r.ok)
    }

    @Test fun `importPackage escape is blocked`() {
        val r = runJs("""importPackage(java.io); console.log(File);""")
        assertFalse(r.ok)
    }

    @Test fun `JavaAdapter escape is blocked`() {
        val r = runJs("""var x = new JavaAdapter(java.lang.Runnable, {run:function(){}});""")
        assertFalse(r.ok)
    }

    @Test fun `reflection via Class.forName is blocked`() {
        val r = runJs("""var c = java.lang.Class.forName("java.lang.Runtime"); console.log(c);""")
        assertFalse(r.ok)
    }

    // ---------------------------------------------------- legitimate code: ALLOW

    @Test fun `arithmetic and console log work`() {
        val r = runJs("console.log(2 + 2);")
        assertTrue(r.ok)
        assertTrue(r.output.contains("4"))
    }

    @Test fun `JSON builtins work`() {
        val r = runJs("""console.log(JSON.stringify({a: 1}));""")
        assertTrue(r.ok)
        assertTrue(r.output.contains("\"a\":1"))
    }

    @Test fun `string manipulation works`() {
        val r = runJs("""console.log("hello".toUpperCase());""")
        assertTrue(r.ok)
        assertTrue(r.output.contains("HELLO"))
    }

    @Test fun `return value is captured`() {
        val r = runJs("6 * 7")
        assertTrue(r.ok)
        assertTrue(r.output.contains("42"))
    }

    @Test fun `es6 arrow functions work`() {
        val r = runJs("const f = (x) => x * 2; console.log(f(21));")
        assertTrue(r.ok)
        assertTrue(r.output.contains("42"))
    }

    @Test fun `syntax error reported honestly`() {
        val r = runJs("function f(:")
        assertFalse(r.ok)
        assertTrue(r.error!!.isNotBlank())
    }

    // ---------------------------------------------------- timeout

    @Test fun `infinite loop is stopped by the instruction watchdog`() {
        val start = System.currentTimeMillis()
        val r = runJs("while (true) { }")
        val elapsed = System.currentTimeMillis() - start
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("timed out", ignoreCase = true))
        // The watchdog fires within ~5s + observer granularity — assert generous bound.
        assertTrue("watchdog took ${elapsed}ms", elapsed < 15_000)
    }

    @Test fun `missing code argument is an honest error`() {
        val r = kotlinx.coroutines.runBlocking {
            tool.run(JsonObject(), ctx)
        }
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("missing code"))
    }
}
