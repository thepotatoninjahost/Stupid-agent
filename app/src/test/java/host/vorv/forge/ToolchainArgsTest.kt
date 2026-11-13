package host.vorv.forge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolchainArgsTest {

    private val sep = File.pathSeparator

    @Test
    fun `kotlinc argv keeps hostile paths as single arguments`() {
        // The exact string that broke the Termux wrapper (ShellQuote H-1):
        // a workspace folder named with a command substitution.
        val hostile = File("/data/user/0/host.vorv/files/forge/work/x'\$(reboot)/src")
        val args = ToolchainArgs.kotlinc(
            targetClasspath = listOf(File("/a.jar")),
            outputDir = File("/out"),
            sources = listOf(File(hostile, "Fix.kt")),
        )
        assertTrue(args.contains(hostile.resolve("Fix.kt").absolutePath))
        // No argument may be a shell fragment: each source path is one element.
        args.forEach { assertFalse(it.contains(" ")) }
        assertEquals(1, args.count { it.endsWith("Fix.kt") })
    }

    @Test
    fun `kotlinc is invoked without stdlib or reflect`() {
        val args = ToolchainArgs.kotlinc(listOf(File("/a.jar")), File("/out"), listOf(File("/s.kt")))
        assertTrue(args.contains("-no-stdlib"))
        assertTrue(args.contains("-no-reflect"))
        assertEquals("17", args[args.indexOf("-jvm-target") + 1])
    }

    @Test
    fun `classpath entries are joined with the platform separator`() {
        val args = ToolchainArgs.kotlinc(
            listOf(File("/one.jar"), File("/two.jar")),
            File("/out"),
            listOf(File("/s.kt")),
        )
        assertEquals("/one.jar${sep}/two.jar", args[args.indexOf("-classpath") + 1])
    }

    @Test
    fun `compiler main class and its runtime jars come before compiler arguments`() {
        val cmd = ToolchainArgs.kotlincCommand(
            javaExecutable = "/x/libforgejvm.so",
            compilerClasspath = listOf(File("/tc/kotlin-compiler-embeddable-2.4.21.jar")),
            targetClasspath = listOf(File("/a.jar")),
            outputDir = File("/out"),
            sources = listOf(File("/s.kt")),
        )
        assertEquals("/x/libforgejvm.so", cmd.first())
        assertTrue(cmd.contains(ToolchainArgs.KOTLIN_MAIN))
        assertTrue(cmd.indexOf(ToolchainArgs.KOTLIN_MAIN) < cmd.indexOf("-d"))
        assertTrue(cmd.count { it == "-cp" } == 1)
    }

    @Test
    fun `d8 argv pins release, min api and output directory`() {
        val args = ToolchainArgs.d8(
            classFiles = listOf(File("/c/A.class"), File("/c/B.class")),
            outputDir = File("/dex"),
            minApi = 31,
            androidJar = File("/android.jar"),
        )
        assertTrue(args.contains("--release"))
        assertEquals("31", args[args.indexOf("--min-api") + 1])
        assertEquals("/dex", args[args.indexOf("--output") + 1])
        assertEquals("/android.jar", args[args.indexOf("--lib") + 1])
        assertEquals(2, args.count { it.endsWith(".class") })
    }

    @Test
    fun `d8 without an android jar omits the lib flag`() {
        val args = ToolchainArgs.d8(listOf(File("/c/A.class")), File("/dex"), 31, null)
        assertFalse(args.contains("--lib"))
    }

    @Test
    fun `compile and dex reject an incomplete toolchain before executing anything`() {
        val empty = File(System.getProperty("java.io.tmpdir"), "forge-empty-${System.nanoTime()}").apply { mkdirs() }
        val compiler = ForgeCompiler(
            javaExecutable = "/bin/false",
            toolchainLibDir = empty,
            androidJar = null,
            workRoot = empty,
        )
        val result = compiler.compileAndDex("run1", listOf(File("/s.kt")), emptyList(), 31)
        assertTrue(result is ForgeCompiler.Result.Failed)
        assertTrue((result as ForgeCompiler.Result.Failed).detail.startsWith("toolchain incomplete"))
        assertEquals(5, compiler.toolchainProblems().size)
    }
}
