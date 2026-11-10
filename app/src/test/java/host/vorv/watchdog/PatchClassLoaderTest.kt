package host.vorv.watchdog

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class LoaderFixture { fun value() = "fixture" }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PatchClassLoaderTest {
    @Test fun `target must come from patch and cannot fall back to installed class`() {
        val target = LoaderFixture::class.java
        val loader = object : PatchClassLoader("", ".", target.classLoader!!, setOf(target.name)) {
            override fun findClass(name: String): Class<*> = throw ClassNotFoundException(name)
        }
        assertTrue(runCatching { loader.loadClass(target.name) }.exceptionOrNull() is ClassNotFoundException)
        assertSame(host.vorv.tools.Tool::class.java, loader.loadClass("host.vorv.tools.Tool"))
    }

    @Test fun `target implementation is defined separately with shared parent types`() {
        val target = LoaderFixture::class.java
        val bytes = target.getResourceAsStream("/${target.name.replace('.', '/')}.class")!!.use { it.readBytes() }
        val loader = object : PatchClassLoader("", ".", target.classLoader!!, setOf(target.name)) {
            override fun findClass(name: String): Class<*> = defineClass(name, bytes, 0, bytes.size)
        }
        val patched = loader.loadClass(target.name)
        assertNotSame(target, patched)
        assertSame(loader, patched.classLoader)
        assertEquals("fixture", patched.getMethod("value").invoke(patched.getConstructor().newInstance()))
    }
}
