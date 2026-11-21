package host.vorv.watchdog

import java.net.URL
import java.net.URLClassLoader

/**
 * A JVM stand-in for PatchDexLoader: same shadowing rule, URLClassLoader
 * instead of DexClassLoader. Lets unit tests prove that a parent-first loader
 * returns the app's class while a shadowing loader returns the patch's
 * (review C-1).
 */
internal class UrlShadowLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val shadowed: Set<String>,
) : URLClassLoader(urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (!shadowsPatch(name, shadowed)) return super.loadClass(name, resolve)
        return try {
            val found = findClass(name)
            if (resolve) resolveClass(found)
            found
        } catch (e: ClassNotFoundException) {
            if (name in shadowed) throw e
            super.loadClass(name, resolve)
        }
    }
}
