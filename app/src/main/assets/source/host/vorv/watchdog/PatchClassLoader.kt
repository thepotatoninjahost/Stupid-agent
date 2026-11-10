package host.vorv.watchdog

import dalvik.system.DexClassLoader

/** Load patched implementations from their DEX, sharing every other app type with the parent. */
internal open class PatchClassLoader(
    dexPath: String,
    optimizedDirectory: String,
    parent: ClassLoader,
    private val targets: Set<String>,
) : DexClassLoader(dexPath, optimizedDirectory, null, parent) {
    internal fun handles(name: String) = name in targets

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        if (targets.any { name == it || name.startsWith("$it\$") }) {
            // Never fall back to the original target: that would verify or run
            // the existing implementation while reporting a patch as applied.
            (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
        } else {
            super.loadClass(name, resolve)
        }
    }
}
