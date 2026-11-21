package host.vorv.watchdog

import dalvik.system.DexClassLoader

/**
 * A patch class loader that returns the PATCH's class for the FQCNs a patch
 * declares, and the app's class for everything else.
 *
 * `DexClassLoader` — like every `ClassLoader` — delegates to its parent
 * first, so `loadClass("host.vorv.tools.NotifyTool")` returns the running
 * app's class and the patch dex is never consulted (review C-1). Overriding
 * `loadClass` for the declared names (and their `$` nested classes) is what
 * makes the patch win without moving `Tool`, `KernelApi`, `HealerApi` or any
 * other shared type into the patch's loader: cross-loader `isAssignableFrom`
 * checks against the parent-loader interface keep working.
 */
internal class PatchDexLoader(
    dexPath: String,
    optimizedDir: String,
    parent: ClassLoader,
    private val shadowed: Set<String>,
) : DexClassLoader(dexPath, optimizedDir, null, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (!shadowsPatch(name, shadowed)) return super.loadClass(name, resolve)
        return try {
            val found = findClass(name)
            if (resolve) resolveClass(found)
            found
        } catch (e: ClassNotFoundException) {
            // A synthetic/nested class the patch dex does not carry (for
            // example Foo$WhenMappings): fall back to the app loader so an
            // unrelated reference does not break the whole patch. A declared
            // FQCN itself is a hard failure — the patch claims a class it did
            // not ship.
            if (name in shadowed) throw e
            super.loadClass(name, resolve)
        }
    }
}

/** True when [name] is a declared patch class or one of its nested classes. */
internal fun shadowsPatch(name: String, shadowed: Set<String>): Boolean =
    name in shadowed || shadowed.any { name.startsWith("$it$") }
