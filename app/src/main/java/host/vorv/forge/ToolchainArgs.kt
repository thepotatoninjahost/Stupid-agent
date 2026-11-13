package host.vorv.forge

import java.io.File

/**
 * Command lines for the app's own compiler, built as **argv arrays**.
 *
 * Nothing here is ever joined into a string that a shell parses: there is no
 * shell (docs/OWN-COMPUTER.md §4). Paths may contain spaces, quotes, `$(` or
 * backticks and cannot express them as syntax because no parser ever sees
 * them — which is a stronger guarantee than even a correct [host.vorv.tools.ShellQuote]
 * quoting pass, since there is no quoting step to get wrong.
 *
 * Pure functions, no Android types: the exact argv that runs on the phone is
 * pinned by unit tests here.
 */
object ToolchainArgs {

    const val KOTLIN_MAIN = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    const val D8_MAIN = "com.android.tools.r8.D8"

    /** JVM flags applied to every compiler run. Kept small: a phone has no swap. */
    val JVM_FLAGS: List<String> = listOf(
        "-Xmx1g",
        "-XX:+UseSerialGC",
        "-Dfile.encoding=UTF-8",
        "-Djava.awt.headless=true",
    )

    /**
     * Arguments for `java -cp <compiler> K2JVMCompiler`, i.e. everything after
     * the main class. [compilerClasspath] must contain the compiler, the
     * stdlib, coroutines and annotations — the three extras are not optional:
     * without them codegen dies with NoClassDefFoundError (proven 2026-11-13).
     */
    fun kotlinc(
        targetClasspath: List<File>,
        outputDir: File,
        sources: List<File>,
    ): List<String> = buildList {
        add("-no-stdlib")
        add("-no-reflect")
        add("-jvm-target"); add("17")
        add("-classpath"); add(targetClasspath.joinToString(File.pathSeparator) { it.absolutePath })
        add("-d"); add(outputDir.absolutePath)
        sources.forEach { add(it.absolutePath) }
    }

    /** Arguments for `java -cp d8.jar D8`. */
    fun d8(
        classFiles: List<File>,
        outputDir: File,
        minApi: Int,
        androidJar: File?,
    ): List<String> = buildList {
        add("--release")
        add("--min-api"); add(minApi.toString())
        if (androidJar != null) {
            add("--lib"); add(androidJar.absolutePath)
        }
        add("--output"); add(outputDir.absolutePath)
        classFiles.forEach { add(it.absolutePath) }
    }

    /** Full argv (executable first) for the embedded Kotlin compiler. */
    fun kotlincCommand(
        javaExecutable: String,
        compilerClasspath: List<File>,
        targetClasspath: List<File>,
        outputDir: File,
        sources: List<File>,
    ): List<String> = buildList {
        add(javaExecutable)
        addAll(JVM_FLAGS)
        add("-cp"); add(compilerClasspath.joinToString(File.pathSeparator) { it.absolutePath })
        add(KOTLIN_MAIN)
        addAll(kotlinc(targetClasspath, outputDir, sources))
    }

    /** Full argv for D8. */
    fun d8Command(
        javaExecutable: String,
        d8Jar: File,
        classFiles: List<File>,
        outputDir: File,
        minApi: Int,
        androidJar: File?,
    ): List<String> = buildList {
        add(javaExecutable)
        addAll(JVM_FLAGS)
        add("-cp"); add(d8Jar.absolutePath)
        add(D8_MAIN)
        addAll(d8(classFiles, outputDir, minApi, androidJar))
    }
}
