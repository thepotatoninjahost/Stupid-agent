package host.vorv.testutil

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.StringWriter
import java.net.URL
import java.nio.file.Files
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/**
 * Compiles a tiny Java fixture at test runtime and hands back a URL pointing
 * at the output directory.
 *
 * Why Java and not Kotlin: the fixtures must be REAL, separately-compiled
 * classes with a chosen FQCN, so a shadowing classloader can return them
 * instead of the app's class (review C-1 / M-3). A Proxy would not do:
 * PatchRegistry.create constructs the class, so it needs a real constructor.
 */
object JavacFixture {

    data class Compiled(val root: File, val classes: File) {
        val urls: Array<URL> get() = arrayOf(classes.toURI().toURL())
    }

    fun compile(fqcn: String, source: String): Compiled {
        val compiler = ToolProvider.getSystemJavaCompiler()
        assumeTrue("this JVM has no system Java compiler", compiler != null)

        val root = Files.createTempDirectory("vorv-javac-").toFile()
        val pkg = fqcn.substringBeforeLast('.')
        val simple = fqcn.substringAfterLast('.')
        val srcDir = File(root, "src/" + pkg.replace('.', '/')).apply { mkdirs() }
        val outDir = File(root, "out").apply { mkdirs() }
        val src = File(srcDir, "$simple.java").apply { writeText(source) }

        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val err = StringWriter()
        val args = mutableListOf("-d", outDir.absolutePath, "-proc:none")
        System.getProperty("java.class.path")?.takeIf { it.isNotBlank() }?.let {
            args += "-classpath"
            args += it
        }
        val fileManager = compiler!!.getStandardFileManager(diagnostics, null, null)
        val ok = fileManager.use { fm ->
            compiler.getTask(
                err, fm, diagnostics, args, null, fm.getJavaFileObjects(src),
            ).call()
        }
        if (!ok) {
            val text = diagnostics.diagnostics.joinToString("\n") { it.toString() }
            fail("javac failed for $fqcn:\n$text\n$err")
        }
        return Compiled(root, outDir)
    }
}
