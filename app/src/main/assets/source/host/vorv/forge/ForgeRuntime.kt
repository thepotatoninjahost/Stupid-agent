package host.vorv.forge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Vorv's own Java runtime, carried inside the app.
 *
 * Why this exists: turning code into an installable app needs `javac` and a
 * dexer, which are Java programs. Android has no JVM, so one travels with the
 * app — pinned by sha256 in tools/forge/runtime-manifest.json and unpacked here
 * on first use, inside Vorv's private storage.
 *
 * Android blocks running files the app wrote, deliberately. The way through is
 * the one the platform provides: payload binaries are packaged as native
 * libraries, so the installer extracts them into `nativeLibraryDir` — a
 * read-only, executable location — and that is what gets started.
 */
class ForgeRuntime(private val context: Context) {

    /** Where the JVM's support files (modules, conf, libs) live at run time. */
    val home: File get() = File(context.filesDir, "forge/jvm")

    private val stamp: File get() = File(context.filesDir, "forge/payload")

    private val launcher: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libforge-java.so")

    fun isProvisioned(): Boolean = stamp.isFile && File(home, "lib/modules").isFile

    /** True when the payload shipped with the installed APK (built with the fetch script). */
    fun isAvailable(): Boolean = launcher.isFile

    /**
     * Unpacks the payload once. Idempotent: the stamp records the payload
     * revision, so an app update that ships a new runtime repaves instead of
     * silently running the old one.
     */
    suspend fun provision(payloadRevision: String = REVISION): Result<File> =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) {
                return@withContext Result.failure(IllegalStateException(
                    "This build has no Java runtime inside it — build it with " +
                        "tools/forge/fetch-runtime.sh first.",
                ))
            }
            if (stamp.isFile && stamp.readText().trim() == payloadRevision && File(home, "lib/modules").isFile) {
                return@withContext Result.success(home)
            }
            try {
                home.deleteRecursively()
                home.mkdirs()
                copyAssets("forge/jvm", home)

                // Libraries the launcher dlopens must sit where java.home says.
                val libDir = File(home, "lib").apply { mkdirs() }
                File(libDir, "server").mkdirs()
                val native = File(context.applicationInfo.nativeLibraryDir)
                native.listFiles { f -> f.name.endsWith(".so") }?.forEach { lib ->
                    if (lib.name.startsWith("libforge-")) return@forEach // launchers stay put
                    val target = when (lib.name) {
                        "libjvm.so" -> File(libDir, "server/${lib.name}")
                        else -> File(libDir, lib.name)
                    }
                    lib.copyTo(target, overwrite = true)
                }
                stamp.parentFile?.mkdirs()
                stamp.writeText(payloadRevision)
                if (!File(home, "lib/modules").isFile) {
                    return@withContext Result.failure(IllegalStateException(
                        "the runtime payload is incomplete: lib/modules is missing",
                    ))
                }
                Result.success(home)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Command line for a payload tool (`javac`, `java`, `jar`…), ready for ProcessBuilder. */
    fun command(tool: String, vararg args: String): List<String> {
        val toolLauncher = File(context.applicationInfo.nativeLibraryDir, "libforge-$tool.so")
        require(toolLauncher.isFile) { "no $tool in this build's runtime" }
        return listOf(
            toolLauncher.absolutePath,
            "-Djava.home=${home.absolutePath}",
            "-Djava.io.tmpdir=${File(context.filesDir, "forge/tmp").apply { mkdirs() }.absolutePath}",
            *args,
        )
    }

    /**
     * Starts the runtime and asks its version. This is the first real proof
     * that the bundled JVM runs on the device, so it returns the raw line
     * instead of a boolean: a silent "true" would hide a launcher that exits 0
     * without doing anything.
     */
    suspend fun probe(timeoutMs: Long = 60_000): Result<String> = withContext(Dispatchers.IO) {
        provision().onFailure { return@withContext Result.failure(it) }
        try {
            val process = ProcessBuilder(command("java", "-version"))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext Result.failure(IllegalStateException("java -version hung for ${timeoutMs}ms"))
            }
            val version = output.lineSequence().firstOrNull { it.contains("version") }
                ?: return@withContext Result.failure(
                    IllegalStateException("java -version produced: ${output.ifBlank { "(nothing)" }}"),
                )
            Result.success(version.trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
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
        /** Bumped when runtime-manifest.json changes. */
        const val REVISION = "openjdk-17.0.21-2"
    }
}
