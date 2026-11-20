package host.vorv.forge

import android.content.Context
import host.vorv.util.CappedOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Java runtime Vorv carries inside itself.
 *
 * Turning source into an installable app needs `javac`, a dexer and a signer,
 * and all three are Java programs on a phone that has no Java. So one travels
 * with the app: pinned by sha256 in tools/forge/runtime-manifest.json, unpacked
 * into Vorv's own storage on first use.
 *
 * Android refuses to execute files an app wrote. The way through is the one the
 * platform provides: payload launchers are packaged as native libraries, so the
 * installer unpacks them into `nativeLibraryDir`, a read-only executable
 * location, and that is what gets started.
 */
class ForgeRuntime(private val context: Context) {

    val home: File get() = File(context.filesDir, "forge/jvm")

    /** Jars shipped with the payload: the Android API, the dexer, the signer. */
    val toolsDir: File get() = File(context.filesDir, "forge/tools")

    val androidJar: File get() = File(toolsDir, "android.jar")
    val d8Jar: File get() = File(toolsDir, "d8.jar")
    val apksignerJar: File get() = File(toolsDir, "apksigner.jar")

    private val launcher: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libforge-java.so")

    fun isAvailable(): Boolean = launcher.isFile

    /** The Kotlin compiler self-repair needs, shipped in the same payload. */
    val kotlinCompilerJar: File get() = File(toolsDir, "kotlin-compiler-embeddable-2.4.21.jar")

    fun isProvisioned(): Boolean =
        androidJar.isFile && kotlinCompilerJar.isFile && File(home, "lib/modules").isFile

    private val stamp: File get() = File(context.filesDir, "forge/payload")

    suspend fun provision(payloadRevision: String = REVISION): Result<File> = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "This build carries no Java runtime. Package it with " +
                        "tools/forge/fetch-runtime.sh, then rebuild Vorv.",
                ),
            )
        }
        if (stamp.isFile && stamp.readText().trim() == payloadRevision && isProvisioned()) {
            return@withContext Result.success(home)
        }
        try {
            home.deleteRecursively()
            home.mkdirs()
            toolsDir.mkdirs()
            copyAssets("forge/jvm", home)
            copyAssets("forge/tools", toolsDir)

            val libDir = File(home, "lib").apply { mkdirs() }
            File(libDir, "server").mkdirs()
            File(context.applicationInfo.nativeLibraryDir)
                .listFiles { f -> f.name.endsWith(".so") && !f.name.startsWith("libforge-") }
                ?.forEach { lib ->
                    val target = if (lib.name == "libjvm.so") File(libDir, "server/${lib.name}") else File(libDir, lib.name)
                    lib.copyTo(target, overwrite = true)
                }
            if (!File(home, "lib/modules").isFile) {
                return@withContext Result.failure(IllegalStateException("payload incomplete: lib/modules missing"))
            }
            if (!androidJar.isFile || !d8Jar.isFile || !apksignerJar.isFile) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "payload incomplete: android.jar, d8.jar, apksigner.jar or the Kotlin " +
                            "compiler is missing — repackage with tools/forge/fetch-runtime.sh",
                    ),
                )
            }
            stamp.parentFile?.mkdirs()
            stamp.writeText(payloadRevision)
            Result.success(home)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Argv for a payload tool (`java`, `javac`, `jar`, `keytool`). */
    fun tool(name: String, vararg args: String): List<String> {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libforge-$name.so")
        require(exe.isFile) { "this build has no $name" }
        val tmp = File(context.filesDir, "forge/tmp").apply { mkdirs() }
        return listOf(exe.absolutePath, "-Djava.home=${home.absolutePath}", "-Djava.io.tmpdir=${tmp.absolutePath}") +
            args.toList()
    }

    /**
     * Runs a payload tool to completion; returns exit code and merged output.
     *
     * The output is drained on its own thread: reading it inline blocked on the
     * process's stdout, so a tool that hung before printing anything also hung
     * this call and the timeout below never got a chance to fire.
     */
    fun exec(argv: List<String>, timeoutMs: Long = 300_000): Exec {
        val started = System.currentTimeMillis()
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        // L-12: the cap holds per CHARACTER, not per line — see CappedOutput.
        val output = CappedOutput(MAX_OUTPUT)
        val drain = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line -> output.appendLine(line) }
            } catch (_: java.io.IOException) {
                // Process died mid-read; the exit code carries the verdict.
            }
        }.apply { isDaemon = true; start() }

        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            drain.join(1_000)
            return Exec(-1, output.text(), System.currentTimeMillis() - started, timedOut = true)
        }
        drain.join(2_000)
        return Exec(process.exitValue(), output.text(), System.currentTimeMillis() - started, timedOut = false)
    }

    data class Exec(val exitCode: Int, val output: String, val ms: Long, val timedOut: Boolean) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
        fun tail(lines: Int = 4): String = output.trim().lines().takeLast(lines).joinToString(" | ")
    }

    private fun copyAssets(assetPath: String, dest: File) {
        val assets = context.assets
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssets("$assetPath/$child", File(dest, child))
    }

    private companion object {
        const val REVISION = "openjdk-17.0.21-2+d8-37+kotlinc-2.4.21"
        const val MAX_OUTPUT = 4000
    }
}
