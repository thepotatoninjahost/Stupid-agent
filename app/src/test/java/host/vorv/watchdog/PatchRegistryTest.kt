package host.vorv.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import android.content.Context

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
    fun `the dispatch surface is exactly the 19 dispatchable classes`() {
        // Night session 2026-11-08: 18 → 19 — crash_logs joined the patchable
        // surface (the agent's eyes on its own crash evidence).
        assertEquals(19, PatchRegistry.DISPATCH_BASES.size)
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.tools.ShellTool"))
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.tools.SelfFixTool"))
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.tools.CrashLogsTool"))
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.kernel.CognitiveKernel"))
        assertTrue(PatchRegistry.isPatchableClass("host.vorv.healer.HealingAgent"))
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
    fun `registered loaders are used and reset works`() {
        PatchRegistry.resetForTest()
        PatchRegistry.registerLoader(javaClass.classLoader)
        // javaClass.classLoader can load NotifyTool and it implements Tool,
        // so dispatch must now succeed with the ORIGINAL class itself.
        val tool = PatchRegistry.create(
            host.vorv.tools.Tool::class.java,
            "host.vorv.tools.NotifyTool",
            context,
        )
        assertNotNull(tool)
        assertEquals("notify", tool!!.name)
        PatchRegistry.resetForTest()
    }
}
