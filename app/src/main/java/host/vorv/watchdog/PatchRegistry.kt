package host.vorv.watchdog

import android.util.Log

/**
 * PatchRegistry — the DISPATCH layer that makes a verified, approved patch
 * actually RUN (v0.6.8 fix for the audited "patches load but never run").
 *
 * THE BUG THIS FIXES: the old code called
 * `DexClassLoader.loadClass(fqcn)` and treated "class loaded" as "patch
 * applied". But the app's own code still constructed the ORIGINAL class —
 * `original.isAssignableFrom(patched)` is always false across classloaders,
 * so a patched class was dead weight that only LOOKED applied.
 *
 * THE MECHANISM:
 *  - Every patchable class implements a SHARED DISPATCH INTERFACE that
 *    lives in the app (parent) classloader: the tools implement
 *    [host.vorv.tools.Tool], the kernel implements
 *    [host.vorv.kernel.KernelApi], the healer implements
 *    [host.vorv.healer.HealerApi].
 *  - Construction sites call [create] instead of constructing directly.
 *    [create] asks the registered patch loaders for the class; if a patch
 *    class is found AND it implements the dispatch interface (checked
 *    against the PARENT-loader interface object), it is constructed and
 *    returned. Otherwise the caller falls back to the original class.
 *  - [isPatchableClass] exposes the honest patchable surface (see
 *    DISPATCH_BASES): a patch for a class OUTSIDE this surface can never
 *    dispatch, and the healer refuses to generate one.
 *
 * THREAD SAFETY: registration happens once at boot (before any dispatch);
 * dispatch afterwards is read-only over a concurrent map of per-class lists.
 */
object PatchRegistry {

    private const val TAG = "Vorv/PatchRegistry"

    /**
     * The honest patchable surface: FQCN → dispatch interface. A patch may
     * only override a class in this table, because only construction sites
     * that dispatch through these interfaces will ever RUN a patch.
     */
    val DISPATCH_BASES: Map<String, Class<*>> = mapOf(
        // ---- agent tools (dispatch as Tool) ----
        "host.vorv.tools.MemoryWriteTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.MemoryRecallTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.JsExecTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.FileReadTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.FileWriteTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.FileListTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.CrashLogsTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.PythonExecTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.WebSearchTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.WebFetchTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.AppIntentTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.ShellTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.NotifyTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.ScheduleTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.ScheduleCancelTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.BrowserAutomateTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.SelfFixTool" to host.vorv.tools.Tool::class.java,
        "host.vorv.tools.SelfUpdateTool" to host.vorv.tools.Tool::class.java,
        // ---- kernel (dispatch as KernelApi) ----
        "host.vorv.kernel.CognitiveKernel" to host.vorv.kernel.KernelApi::class.java,
        // ---- healer (dispatch as HealerApi) ----
        "host.vorv.healer.HealingAgent" to host.vorv.healer.HealerApi::class.java,
    )

    /**
     * Patch classloaders registered at boot by HotPatcher, keyed by the FQCN
     * each loader was registered for. Per-class registration is what stops a
     * patch for one class from masking a patch for another: a loader that
     * does not carry the requested class must not win the dispatch.
     */
    private val loadersByClass =
        java.util.concurrent.ConcurrentHashMap<String, MutableList<ClassLoader>>()

    /** Called by HotPatcher after a patch dex passes every verification gate. */
    fun registerLoader(loader: ClassLoader, classes: Collection<String>) {
        var registered = 0
        for (fqcn in classes) {
            if (!isPatchableClass(fqcn)) {
                Log.w(TAG, "ignoring non-patchable class $fqcn in patch loader")
                continue
            }
            loadersByClass.computeIfAbsent(fqcn) {
                java.util.concurrent.CopyOnWriteArrayList()
            }.add(loader)
            registered++
        }
        Log.i(TAG, "Patch loader registered for $registered class(es) " +
            "(${loadersByClass.size} classes active)")
    }

    /** Whether [fqcn] is in the honest patchable surface. */
    fun isPatchableClass(fqcn: String): Boolean = fqcn in DISPATCH_BASES

    /** The dispatch interface a patch for [fqcn] must implement, or null. */
    fun dispatchBaseFor(fqcn: String): Class<*>? = DISPATCH_BASES[fqcn]

    /**
     * Create [fqcn] from a registered patch if one exists and implements
     * [iface]; otherwise return null so the caller constructs the original.
     *
     * The interface check is the load-bearing line: it is done against the
     * PARENT-loader [iface] object, so a patch class that fails to
     * implement the real dispatch interface is rejected here instead of
     * silently never running.
     */
    fun <T : Any> create(iface: Class<T>, fqcn: String, vararg args: Any?): T? {
        if (!isPatchableClass(fqcn)) return null
        for (loader in loadersByClass[fqcn].orEmpty()) {
            val patched: Class<*> = try {
                loader.loadClass(fqcn)
            } catch (e: ClassNotFoundException) {
                continue
            } catch (e: Exception) {
                Log.w(TAG, "Patch load failed for $fqcn: ${e.message}")
                continue
            }
            // A loader that has no class for this FQCN hands back the app's
            // own class. Constructing it would "apply" a patch for a different
            // class and would let two patches never both run (review C-1).
            if (patched.classLoader === iface.classLoader) {
                Log.w(TAG, "loader returned the app's own $fqcn — refusing to dispatch it as a patch")
                continue
            }
            if (!iface.isAssignableFrom(patched)) {
                Log.e(TAG, "Patch for $fqcn does not implement ${iface.name} — refusing to dispatch")
                continue
            }
            return try {
                construct(patched, args)
            } catch (e: Exception) {
                Log.e(TAG, "Patch construction failed for $fqcn: ${e.message}", e)
                null
            }
        }
        return null
    }

    /** Kotlin convenience reified form. */
    inline fun <reified T : Any> create(fqcn: String, vararg args: Any?): T? =
        create(T::class.java, fqcn, *args)

    /** Best-effort constructor matching by shape (Kotlin classes from kotlinc). */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> construct(cls: Class<*>, args: Array<out Any?>): T? {
        val ctors = cls.constructors
        // Exact-arity first, then by-arity with null tolerance.
        val candidate = ctors.filter { it.parameterCount == args.size }.firstOrNull()
            ?: ctors.filter { it.parameterCount >= args.size }.minByOrNull { it.parameterCount }
            ?: return null
        return try {
            val expanded: Array<Any?> = arrayOfNulls(candidate.parameterCount)
            System.arraycopy(args, 0, expanded, 0, minOf(args.size, candidate.parameterCount))
            candidate.newInstance(*expanded) as? T
        } catch (e: Exception) {
            Log.e(TAG, "Patch newInstance failed: ${e.message}", e)
            null
        }
    }

    /** Test hook: clear registered loaders. */
    fun resetForTest() {
        loadersByClass.clear()
    }
}
