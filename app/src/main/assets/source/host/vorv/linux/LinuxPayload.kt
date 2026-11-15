package host.vorv.linux

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * Where proot actually is, and why it might not be runnable.
 *
 * Two different facts were being confused, and the Setup card told the owner a
 * lie because of it: "this APK was built without the payload" was printed when
 * the payload *was* in the APK but the installer had not unpacked it into
 * nativeLibraryDir. Android only executes a binary from a place the installer
 * created (app storage is not executable since Android 10), so the distinction
 * decides what the owner can do next.
 */
class LinuxPayload(private val context: Context) {

    data class State(
        val apkEntry: String?,
        /** Path the ClassLoader says the library lives at, when it can be found. */
        val resolvedPath: String?,
        val extracted: Boolean,
        val listing: List<String>,
        val copiedTo: File?,
        val execVerdict: String?,
    ) {
        val payloadInApk: Boolean get() = apkEntry != null
    }

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir ?: "")
    private val cacheDir: File get() = File(context.filesDir, "native")

    /**
     * Ask the runtime itself where this library was installed. `findLibrary`
     * knows the real extracted path even when ApplicationInfo.nativeLibraryDir
     * points somewhere stale.
     */
    fun resolvedProotPath(): String? = runCatching {
        // findLibrary is protected on ClassLoader, so it can only be asked by
        // reflection. Best effort: the fallback is nativeLibraryDir below.
        val m = ClassLoader::class.java.getDeclaredMethod("findLibrary", String::class.java)
        m.isAccessible = true
        (m.invoke(context.classLoader, "forge-proot") as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun inspect(): State {
        val listing = nativeDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        return State(
            apkEntry = apkEntry(),
            resolvedPath = resolvedProotPath(),
            extracted = File(nativeDir, "libforge-proot.so").isFile,
            listing = listing,
            copiedTo = File(cacheDir, "libforge-proot.so").takeIf { it.isFile },
            execVerdict = null,
        )
    }

    /** The executable to use, preferring what the runtime resolved. */
    fun executable(): File? {
        resolvedProotPath()?.let { p -> File(p).takeIf { it.isFile }?.let { return it } }
        return File(nativeDir, "libforge-proot.so").takeIf { it.isFile }
    }

    /** Name of the APK entry carrying proot for this device's ABI, if present. */
    fun apkEntry(): String? = runCatching {
        ZipFile(context.applicationInfo.sourceDir).use { zip ->
            val abis = android.os.Build.SUPPORTED_ABIS.toList()
            val names = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith("/libforge-proot.so") }
                .toList()
            names.firstOrNull { n -> abis.any { n.contains("/$it/") } } ?: names.firstOrNull()
        }
    }.getOrNull()

    /**
     * Unpack proot and its loader out of our own APK into app storage and try
     * to run it. On Android 10+ that executable bit does not help — the honest
     * result is recorded rather than swallowed, because it is the difference
     * between "wrong path" and "the installer never unpacked this app".
     */
    fun repair(): State = try {
        val entry = apkEntry() ?: return inspect()
        cacheDir.mkdirs()
        ZipFile(context.applicationInfo.sourceDir).use { zip ->
            fun copy(name: String, out: String) {
                zip.getEntry(name)?.let { e ->
                    zip.getInputStream(e).use { input ->
                        File(cacheDir, out).outputStream().use { input.copyTo(it) }
                    }
                }
            }
            copy(entry, "libforge-proot.so")
            val abi = entry.substringBeforeLast('/')
            copy("$abi/libforge-proot-loader.so", "loader")
            copy("$abi/libforge-proot-loader32.so", "loader32")
        }
        val proot = File(cacheDir, "libforge-proot.so").apply { setExecutable(true) }
        val verdict = runCatching {
            val p = ProcessBuilder(proot.absolutePath, "--version").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            "ran from app storage: ${text.take(200)}"
        }.getOrElse { "app storage refuses to execute it: ${it.message}" }
        inspect().copy(copiedTo = proot.takeIf { it.isFile }, execVerdict = verdict)
    } catch (e: Exception) {
        inspect().copy(execVerdict = "unpacking from the APK failed: ${e.message}")
    }
}
