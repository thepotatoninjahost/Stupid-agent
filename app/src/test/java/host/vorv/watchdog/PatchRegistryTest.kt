package host.vorv.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import host.vorv.testutil.JavacFixture

/**
 * PatchRegistry dispatch tests (v0.6.8 patch-dispatch fix).
 *
 * Pins the honest patchable surface and the interface-based dispatch
 * contract: a patch class that does not implement its dispatch interface
 * must NEVER be dispatched, and only classes in DISPATCH_BASES are
 * patchable at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PatchRegistryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the dispatch surface is exactly the 20 dispatchable classes`() {
        // Night session 2026-11-08: 18 → 19 — crash_logs joined the patchable
        // surface (the agent's eyes on its own crash evidence).
        // 2026-11-28: 19 → 20 — self_update joined it (the tool that changes
        // Vorv's own source and installs the rebuilt app; it must be
        // repairable by the same pipeline it uses).
        assertEquals(20, PatchRegistry.DISPATCH_BASES.size)
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.tools.ShellTool"))
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.tools.SelfFixTool"))
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.tools.SelfUpdateTool"))
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.tools.CrashLogsTool"))
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.kernel.CognitiveKernel"))
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.healer.HealingAgent"))
    }

    @Test
    fun `every patchable tool is actually constructed through the registry`() {
        // The audited bug: a patch was reported applied and could never run,
        // because AgentViewModel built every tool with `Tool(appContext)`
        // instead of `PatchRegistry.create`. A patch that cannot dispatch is
        // a lie told to the owner, so this pins the construction sites.
        //
        // 0.9.37 moved the roster to tools/ToolRoster.kt: the test reads both
        // files, so the construction sites are pinned wherever they live.
        val source = listOf(
            "src/main/java/host/vorv/AgentViewModel.kt",
            "src/main/java/host/vorv/tools/ToolRoster.kt",
        ).joinToString("\n") { java.io.File(it).readText() }
        val toolClasses = PatchRegistry.DISPATCH_BASES.keys.filter { it.startsWith("host.vorv.tools.") }
        assertTrue("tool patch surface is suspiciously small", toolClasses.size >= 17)
        val missing = toolClasses.filter { !source.contains("patched(\"$it\"") }
        assertEquals("built directly, so a patch for these would never run: $missing",
            emptyList<String>(), missing)
    }

    @Test
    fun `non-dispatch surface classes are refused`() {
        // Activities, Application, helpers — a patch for them can never run.
        assertFalse(PatchRegistry.isPatchableClass("host.vorv.MainActivity"))
        assertFalse(PatchRegistry.isPatchableClass("host.vorv.VorvApplication"))
        assertFalse(PatchRegistry.isPatchableClass("host.vorv.watchdog.WatchdogService"))
        assertFalse(PatchRegistry.isPatchableClass("com.example.Anything"))
    }

    @Test
    fun `every dispatch base is an interface the original classes implement`() {
        for ((fqcn, base) in PatchRegistry.DISPATCH_BASES) {
            assertTrue("$fqcn base must be an interface", base.isInterface)
            val original = Class.forName(fqcn)
            assertTrue(
                "$fqcn must implement its dispatch interface ${base.name}",
                base.isAssignableFrom(original),
            )
        }
    }

    @Test
    fun `create returns null when no patch loader is registered`() {
        PatchRegistry.resetForTest()
        val result = PatchRegistry.create(
            host.vorv.tools.Tool::class.java,
            "host.vorv.tools.NotifyTool",
            context,
        )
        assertNull("no loaders registered — must fall back to the original", result)
    }

    @Test
    fun `create refuses classes outside the patchable surface`() {
        PatchRegistry.resetForTest()
        val result = PatchRegistry.create(
            host.vorv.tools.Tool::class.java,
            "host.vorv.MainActivity",
            context,
        )
        assertNull(result)
    }

    /**
     * The v0.6.7 dispatch bug, pinned: a same-FQCN class loaded under a
     * different classloader is a DIFFERENT runtime identity, so the old
     * `original.isAssignableFrom(patched)` check was always false and
     * patches never ran. Interface-based dispatch must instead accept a
     * patched class through the PARENT-loader interface object.
     */
    @Test
    fun `interface dispatch accepts a cross-loader implementor`() {
        // A classloader whose parent is THIS test's loader, hosting an
        // implementor of Tool created via Proxy — the cheapest way to prove
        // the interface check crosses classloader boundaries.
        val loader = object : ClassLoader(javaClass.classLoader) {}
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            loader,
            arrayOf(host.vorv.tools.Tool::class.java),
        ) { _, _, _ -> null }
        assertTrue(
            "interface check must be done against the parent-loader interface",
            host.vorv.tools.Tool::class.java.isAssignableFrom(proxy.javaClass),
        )
    }

    @Test
    fun `shadowsPatch matches the class and its nested classes only`() {
        val shadowed = setOf("host.vorv.tools.NotifyTool")
        assertTrue(shadowsPatch("host.vorv.tools.NotifyTool", shadowed))
        assertTrue(shadowsPatch("host.vorv.tools.NotifyTool\$Companion", shadowed))
        assertFalse(shadowsPatch("host.vorv.tools.NotifyToolOther", shadowed))
        assertFalse(shadowsPatch("host.vorv.tools.ShellTool", shadowed))
    }

    @Test
    fun `a parent-first loader returns the app class and a shadowing loader the patch`() {
        val parent = JavacFixture.compile(
            "probe.Same",
            """
            package probe;
            public class Same { public static String who() { return "parent"; } }
            """.trimIndent(),
        )
        val child = JavacFixture.compile(
            "probe.Same",
            """
            package probe;
            public class Same { public static String who() { return "child"; } }
            """.trimIndent(),
        )
        val parentLoader = java.net.URLClassLoader(parent.urls, javaClass.classLoader)
        val plainChild = java.net.URLClassLoader(child.urls, parentLoader)
        assertEquals(
            "parent-first delegation must hand back the app's class",
            "parent",
            plainChild.loadClass("probe.Same").getMethod("who").invoke(null),
        )

        val shadowing = UrlShadowLoader(child.urls, parentLoader, setOf("probe.Same"))
        val patched = shadowing.loadClass("probe.Same")
        assertEquals(
            "the shadowing loader must hand back the patch's class",
            "child",
            patched.getMethod("who").invoke(null),
        )
        assertTrue(
            "and it must be a different runtime class",
            patched !== parentLoader.loadClass("probe.Same"),
        )
    }

    /**
     * C-1 pinned end to end: a patch dex for host.vorv.tools.NotifyTool is
     * dispatched ONLY when the loader really shadows that class. Registering
     * the app's own loader must never count as "a patch ran".
     */
    @Test
    fun `a loader registered for the class dispatches its patch`() {
        PatchRegistry.resetForTest()
        val compiled = JavacFixture.compile("host.vorv.tools.NotifyTool", NOTIFY_PATCH_SOURCE)
        val loader = UrlShadowLoader(
            compiled.urls, javaClass.classLoader, setOf("host.vorv.tools.NotifyTool"),
        )
        PatchRegistry.registerLoader(loader, listOf("host.vorv.tools.NotifyTool"))

        val tool = PatchRegistry.create(
            host.vorv.tools.Tool::class.java,
            "host.vorv.tools.NotifyTool",
            context,
        )
        assertNotNull("the registered patch must be dispatched", tool)
        assertEquals("notify-patched", tool!!.name)
        assertSame("the patched class, not the app's", loader, tool.javaClass.classLoader)
        PatchRegistry.resetForTest()
    }

    @Test
    fun `a loader that does not shadow the class is refused`() {
        PatchRegistry.resetForTest()
        PatchRegistry.registerLoader(javaClass.classLoader, listOf("host.vorv.tools.NotifyTool"))
        val tool = PatchRegistry.create(
            host.vorv.tools.Tool::class.java,
            "host.vorv.tools.NotifyTool",
            context,
        )
        assertNull("the app's own class is not a patch — must not be dispatched", tool)
        PatchRegistry.resetForTest()
    }

    @Test
    fun `a loader is not consulted for a class it was not registered for`() {
        PatchRegistry.resetForTest()
        val compiled = JavacFixture.compile("host.vorv.tools.NotifyTool", NOTIFY_PATCH_SOURCE)
        val loader = UrlShadowLoader(
            compiled.urls, javaClass.classLoader, setOf("host.vorv.tools.NotifyTool"),
        )
        PatchRegistry.registerLoader(loader, listOf("host.vorv.tools.ShellTool"))
        val tool = PatchRegistry.create(
            host.vorv.tools.Tool::class.java,
            "host.vorv.tools.NotifyTool",
            context,
        )
        assertNull(tool)
        PatchRegistry.resetForTest()
    }

    private companion object {
        val NOTIFY_PATCH_SOURCE = """
            package host.vorv.tools;
            import com.google.gson.JsonObject;
            import kotlin.coroutines.Continuation;

            public class NotifyTool implements Tool {
                public NotifyTool(Object ignored) {}
                @Override public String getName() { return "notify-patched"; }
                @Override public String getDescription() { return "patched fixture"; }
                @Override public JsonObject schema() { return new JsonObject(); }
                @Override public long getTimeoutMs() { return 70000L; }
                @Override public int getMaxCallsPerTask() { return 10; }
                @Override public Object run(
                    JsonObject input, ToolContext ctx,
                    Continuation<? super ToolResult> continuation) {
                    return null;
                }
            }
        """.trimIndent()
    }
}
