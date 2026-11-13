package host.vorv.forge

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Compiles Kotlin and dexes it using the app's own toolchain: no Termux, no
 * shell, no user-visible path (docs/OWN-COMPUTER.md).
 *
 * The class is deliberately split from Android types so the whole pipeline
 * runs under a plain JVM in tests: the only integration points are the argv
 * built by [ToolchainArgs] and a [ProcessBuilder], both of which behave the
 * same on a desktop JVM and on the phone's bundled runtime.
 */
class ForgeCompiler(
    private val javaExecutable: String,
    private val toolchainLibDir: File,
    private val androidJar: File?,
    private val workRoot: File,
    private val compileTimeoutMs: Long = 300_000,
    private val dexTimeoutMs: Long = 120_000,
) {

    sealed interface Result {
        data class Ok(val dex: File, val classCount: Int, val elapsedMs: Long) : Result
        data class Failed(val stage: Stage, val detail: String) : Result
    }

    enum class Stage { COMPILE, DEX, OUTPUT }

    private val compilerClasspath: List<File> by lazy {
        listOf(
            File(toolchainLibDir, "kotlin-compiler-embeddable-2.4.21.jar"),
            File(toolchainLibDir, "kotlin-stdlib-2.4.21.jar"),
            File(toolchainLibDir, "kotlinx-coroutines-core-jvm-1.11.0.jar"),
            File(toolchainLibDir, "annotations-26.0.2.jar"),
        )
    }

    private val d8Jar: File by lazy { File(toolchainLibDir, "d8.jar") }

    /**
     * The compiler runs with `-no-stdlib`, so `kotlin.*` must be resolvable
     * from the *target* classpath as well — the app's own classes reference
     * it. Proven the hard way: without this, every patch fails at codegen
     * with "cannot access built-in declaration 'kotlin.Any'".
     */
    fun targetClasspathWithStdlib(targetClasspath: List<File>): List<File> {
        val stdlib = File(toolchainLibDir, "kotlin-stdlib-2.4.21.jar")
        val already = targetClasspath.any { it.isFile && it.name.startsWith("kotlin-stdlib") }
        return if (already || !stdlib.isFile) targetClasspath else targetClasspath + stdlib
    }

    /** True when every compiler file the args reference is actually present. */
    fun toolchainProblems(): List<String> =
        (compilerClasspath + d8Jar).filterNot { it.isFile }.map { "missing: ${it.name}" }

    fun compileAndDex(
        runId: String,
        sources: List<File>,
        targetClasspath: List<File>,
        minApi: Int,
    ): Result {
        if (sources.isEmpty()) return Result.Failed(Stage.COMPILE, "no source files")
        val missing = toolchainProblems()
        if (missing.isNotEmpty()) {
            return Result.Failed(Stage.COMPILE, "toolchain incomplete: ${missing.joinToString("; ")}")
        }
        val started = System.currentTimeMillis()
        val work = File(workRoot, runId)
        val classesDir = File(work, "classes")
        val dexDir = File(work, "dex")
        if (!classesDir.mkdirs() || !dexDir.mkdirs()) {
            return Result.Failed(Stage.COMPILE, "cannot create work dir under $workRoot")
        }

        val compile = exec(
            ToolchainArgs.kotlincCommand(
                javaExecutable = javaExecutable,
                compilerClasspath = compilerClasspath,
                targetClasspath = targetClasspathWithStdlib(targetClasspath),
                outputDir = classesDir,
                sources = sources,
            ),
            work,
            compileTimeoutMs,
        )
        if (compile.timedOut) {
            return Result.Failed(Stage.COMPILE, "kotlinc exceeded ${compileTimeoutMs} ms")
        }
        if (compile.exitCode != 0) {
            return Result.Failed(Stage.COMPILE, compile.stderr.ifBlank { "kotlinc exit ${compile.exitCode}" })
        }

        val classFiles = classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        if (classFiles.isEmpty()) {
            return Result.Failed(Stage.COMPILE, "kotlinc produced no .class files")
        }

        val dex = exec(
            ToolchainArgs.d8Command(
                javaExecutable = javaExecutable,
                d8Jar = d8Jar,
                classFiles = classFiles,
                outputDir = dexDir,
                minApi = minApi,
                androidJar = androidJar,
            ),
            work,
            dexTimeoutMs,
        )
        if (dex.timedOut) {
            return Result.Failed(Stage.DEX, "d8 exceeded ${dexTimeoutMs} ms")
        }
        if (dex.exitCode != 0) {
            return Result.Failed(Stage.DEX, dex.stderr.ifBlank { "d8 exit ${dex.exitCode}" })
        }

        val out = File(dexDir, "classes.dex")
        if (!out.isFile || out.length() == 0L) {
            return Result.Failed(Stage.OUTPUT, "d8 reported success but produced no classes.dex")
        }
        return Result.Ok(out, classFiles.size, System.currentTimeMillis() - started)
    }

    private class ExecOutcome(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    /**
     * Runs argv directly (never through a shell). Output is drained
     * concurrently and capped: a compiler that prints a megabyte of errors
     * must not be able to grow the runtime's heap.
     */
    private fun exec(argv: List<String>, cwd: File, timeoutMs: Long): ExecOutcome {
        val pb = ProcessBuilder(argv).directory(cwd)
        val process = pb.start()
        process.outputStream.close()
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread { drain(process.inputStream.bufferedReader(), out) }.apply { isDaemon = true; start() }
        val t2 = Thread { drain(process.errorStream.bufferedReader(), err) }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        t1.join(2_000)
        t2.join(2_000)
        return ExecOutcome(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = out.toString(),
            stderr = err.toString(),
            timedOut = !finished,
        )
    }

    private fun drain(reader: java.io.Reader, sink: StringBuilder) {
        val buf = CharArray(4_096)
        try {
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                synchronized(sink) {
                    if (sink.length < OUTPUT_CAP) sink.appendRange(buf, 0, n)
                }
            }
        } catch (_: java.io.IOException) {
            // Process died mid-read; the exit code already carries the verdict.
        } finally {
            runCatching { reader.close() }
        }
    }

    private companion object {
        const val OUTPUT_CAP = 16_384
    }
}
