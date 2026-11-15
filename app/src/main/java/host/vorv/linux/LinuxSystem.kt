package host.vorv.linux

import android.content.Context
import host.vorv.tools.GuestShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A real Linux system inside the app: its own filesystem, its own shell, its
 * own package manager.
 *
 * No root and no Termux. proot runs the whole userland unprivileged by
 * interposing on syscalls, so programs inside see a root filesystem that is
 * really a folder in Vorv's private storage. That is what makes the promise
 * "its own computer" true rather than decorative: `apk add gcc` inside this
 * system really does put a C compiler in it.
 *
 * Everything the system needs travels in the APK: the proot binary and its
 * loader (as native libraries, because Android only executes binaries the
 * installer unpacked into nativeLibraryDir) and an Alpine root filesystem
 * (as an asset, unpacked once into Vorv's storage).
 */
class LinuxSystem(private val context: Context) : GuestShell {

    val root: File get() = File(context.filesDir, "linux")
    val rootfs: File get() = File(root, "rootfs")
    private val loaderDir: File get() = File(root, "loader")
    private val stamp: File get() = File(root, "provisioned")

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir ?: "")

    private val payload by lazy { LinuxPayload(context) }

    /** The proot binary, at the path the runtime actually installed it. */
    fun prootExecutable(): File? = payload.executable()

    fun inspectPayload(): LinuxPayload.State = payload.inspect()

    fun repairPayload(): LinuxPayload.State = payload.repair()

    override fun isAvailable(): Boolean = payload.executable() != null

    fun isProvisioned(): Boolean = stamp.isFile && File(rootfs, "etc/alpine-release").isFile

    /**
     * Why the system is or is not usable on this phone, in words the owner can
     * read off the Setup card. A missing payload is the one failure that
     * cannot be fixed from inside the app, so it has to name itself instead of
     * leaving a bare "not packaged".
     */
    fun diagnose(): String {
        val lines = mutableListOf<String>()
        lines += "version " + runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" }
        }.getOrElse { "unknown" }
        lines += "ABIs " + android.os.Build.SUPPORTED_ABIS.joinToString(",")
        lines += "nativeLibraryDir " + nativeDir.absolutePath

        val state = inspectPayload()
        lines += "proot in APK: " + (state.apkEntry ?: "MISSING — this build really has no payload")
        lines += "runtime resolved: " + (state.resolvedPath ?: "not found by the ClassLoader")
        lines += "extracted copy: " + if (state.extracted) "present" else "MISSING"
        if (state.listing.isEmpty()) {
            lines += "nativeLibraryDir holds nothing — the installer unpacked no libraries for this install"
        } else {
            lines += "nativeLibraryDir holds " + state.listing.take(12).joinToString(",") +
                if (state.listing.size > 12) " (+${state.listing.size - 12})" else ""
        }

        val assets = runCatching { context.assets.list("forge/linux")?.toList() ?: emptyList() }.getOrElse { emptyList() }
        lines += "payload " + if (assets.contains("rootfs.tar")) "rootfs.tar present" else
            "rootfs.tar MISSING (assets/forge/linux has: " + (if (assets.isEmpty()) "nothing" else assets.joinToString(",")) + ")"
        lines += "rootfs " + if (isProvisioned()) "unpacked" else "not unpacked yet"
        return lines.joinToString("\n")
    }

    suspend fun provision(): Result<File> = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            val inApk = payload.inspect().payloadInApk
            return@withContext Result.failure(
                IllegalStateException(
                    if (inApk)
                        "The payload is in this APK but Android did not unpack it for this install, so it cannot run. " +
                            "Uninstall Vorv and install this APK again."
                    else "This build carries no Linux system. Package it with tools/forge/fetch-linux.sh.",
                ),
            )
        }
        if (isProvisioned()) return@withContext Result.success(root)
        try {
            rootfs.deleteRecursively()
            rootfs.mkdirs()
            extractRootfs()
            loaderDir.mkdirs()
            listOf("loader", "loader32").forEach { name ->
                // Prefer the installed copy; fall back to the one unpacked from
                // our own APK, so a partially-unpacked install still works.
                val candidates = listOf(
                    File(nativeDir, "libforge-proot-$name.so"),
                    File(File(context.filesDir, "native"), name),
                )
                candidates.firstOrNull { it.isFile }
                    ?.copyTo(File(loaderDir, name), overwrite = true)
            }
            File(loaderDir, "loader").setExecutable(true)
            if (!isProvisioned()) {
                return@withContext Result.failure(IllegalStateException("the Linux root filesystem is incomplete"))
            }
            stamp.writeText(STAMP)
            Result.success(root)
        } catch (e: Exception) {
            rootfs.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * Runs one shell command inside the Linux system and returns what it said.
     *
     * proot is given the app's own view of /dev, /proc and /sys read-only where
     * the guest only needs to read them, and the guest's home is a folder
     * inside the computer, so work survives between commands.
     */
    override suspend fun run(
        command: String,
        timeoutMs: Long,
        binds: List<Pair<String, String>>,
        workDir: String,
    ): Shell = withContext(Dispatchers.IO) {
        provision().onFailure { return@withContext Shell(-1, "", it.message ?: "not provisioned", true) }
        val proot = payload.executable()
            ?: return@withContext Shell(
                -1, "",
                "Android has not unpacked this app's Linux binaries, so proot cannot run here. " +
                    "The APK does contain them" +
                    (if (payload.inspect().payloadInApk) " (lib/…/libforge-proot.so)" else "") +
                    " — the unpack happens at install time, so uninstall Vorv and install the APK again.",
                true,
            )
        val home = File(root, "home").apply { mkdirs() }
        val tmp = File(root, "tmp").apply { mkdirs() }
        val argv = prootArgv(proot, rootfs, home, tmp, binds, workDir, command)
        val env = guestEnv(loaderDir, tmp)
        val started = System.currentTimeMillis()
        val builder = ProcessBuilder(argv)
        builder.environment().putAll(env)
        builder.directory(home)
        val process = builder.redirectErrorStream(true).start()
        val out = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { line ->
            if (out.length < MAX_OUTPUT) out.appendLine(line)
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@withContext Shell(-1, out.toString(), "timed out after ${timeoutMs}ms", true)
        }
        Shell(process.exitValue(), out.toString(), null, false)
    }

    companion object {
        /**
         * The proot command line, built as argv so no shell ever parses a path
         * (the same rule as [host.vorv.forge.ToolchainArgs]). Pure: the exact
         * instruction sequence is pinned by unit tests without a device.
         */
        internal fun prootArgv(
            proot: File,
            rootfs: File,
            home: File,
            tmp: File,
            binds: List<Pair<String, String>>,
            workDir: String,
            command: String,
        ): List<String> = buildList {
            add(proot.absolutePath)
            add("-0")
            add("-r"); add(rootfs.absolutePath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("$home:/root")
            add("-b"); add("$tmp:/tmp")
            // Guest paths are what the command sees; host paths are real folders
            // on the phone (the user's workspace, for instance).
            binds.forEach { (hostPath, guestPath) ->
                add("-b"); add(if (hostPath == guestPath) hostPath else "$hostPath:$guestPath")
            }
            add("-w"); add(workDir)
            add("/bin/sh"); add("-lc"); add(command)
        }

        /** proot's environment: its loader, a writable /tmp and a plain PATH. */
        internal fun guestEnv(loaderDir: File, tmp: File): Map<String, String> = mapOf(
            "PROOT_LOADER" to File(loaderDir, "loader").absolutePath,
            "PROOT_TMP_DIR" to tmp.absolutePath,
            // The app has no seccomp filter to inherit; avoids a needless probe.
            "PROOT_NO_SECCOMP" to "1",
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "dumb",
            "LANG" to "C.UTF-8",
        )

        /** Bumped when the payload layout changes, so provision() re-extracts. */
        internal const val STAMP = "alpine-3.20.0-proot-5.1.107"

        /**
         * Output kept in memory per command. 12k chars cut real compiler logs
         * mid-diagnostic; 200k holds a full build log while a runaway command
         * still cannot eat the phone's memory.
         */
        internal const val MAX_OUTPUT = 200_000
    }

    data class Shell(val exitCode: Int, val output: String, val error: String?, val timedOut: Boolean) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }

    /** Unpacks the root filesystem, preserving the symlinks Alpine depends on. */
    private fun extractRootfs() {
        context.assets.open("forge/linux/rootfs.tar").use { raw ->
            RootfsTar.extract(raw, rootfs)
        }
    }

}

/** Tar handling for the root filesystem, kept separate so it can be tested without Android. */
internal object RootfsTar {

    /**
     * Accepts a plain tar or a gzipped one. The asset is a plain tar because AGP
     * strips a .gz suffix from asset names, but a gzipped stream is still read
     * so the payload can be swapped without touching this code.
     */
    fun extract(tarData: java.io.InputStream, rootfs: File) {
        val base = rootfs.canonicalFile
        val buffered = java.io.BufferedInputStream(tarData).apply { mark(2) }
        val gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream: java.io.InputStream =
            if (gzipped) GzipCompressorInputStream(buffered) else buffered
        stream.use {
            TarArchiveInputStream(it).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val name = entry.name.removePrefix("./").trimEnd('/')
                    if (name.isEmpty()) continue
                    val target = File(base, name).canonicalFile
                    // A tar entry must never resolve outside the rootfs.
                    if (!target.path.startsWith(base.path + File.separator) && target != base) {
                        throw SecurityException("rootfs entry escapes the tree: ${entry.name}")
                    }
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            target.delete()
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(), java.nio.file.Paths.get(entry.linkName),
                            )
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { tar.copyTo(it) }
                            target.setExecutable((entry.mode and 0b001_000_000) != 0, false)
                        }
                    }
                }
            }
        }
    }
}
