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
        /** "arm64-v8a" when the APK carries a copy for this device, else null. */
        val abi: String?,
        /** One honest sentence saying what is wrong, or null when nothing is. */
        val diagnosis: String?,
    ) {
        val payloadInApk: Boolean get() = apkEntry != null
    }

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir ?: "")

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
        val entry = apkEntry()
        return State(
            apkEntry = entry,
            resolvedPath = resolvedProotPath(),
            extracted = File(nativeDir, "libforge-proot.so").isFile,
            listing = listing,
            abi = entry?.let { it.removePrefix("lib/").substringBefore('/') },
            diagnosis = null,
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
     * Inventory only. There is nothing this class can repair: since Android 10 a
     * binary an app writes into its own data directory cannot be executed, no
     * matter what permission bits are set. The old version copied proot there
     * and exec'd it to "prove" it worked, which could only ever print "app
     * storage refuses to execute it" — a repair button that cannot repair.
     *
     * What actually decides whether the guest can start is whether the installer
     * unpacked the APK's arm64 libraries into [nativeDir], so this reports that
     * fact and the one action that changes it.
     */
    fun payloadReport(): State {
        val base = inspect()
        val where = "${nativeDir.path}/libforge-proot.so"
        val text = when {
            !base.payloadInApk ->
                "This APK carries no proot for any ABI — it was built without the payload. " +
                    "Install a build that contains it (Setup → Linux System shows this too)."
            !base.extracted ->
                "proot is inside this APK for ${base.abi ?: "this device"}, but the installer never " +
                    "unpacked it into ${nativeDir.path}. Unpacking happens at install time, so " +
                    "uninstall Vorv and install this same APK again."
            base.resolvedPath == null ->
                "proot is unpacked at $where, but the runtime does not resolve it by name. " +
                    "Reinstall the APK."
            else -> null
        }
        return base.copy(diagnosis = text)
    }
}
