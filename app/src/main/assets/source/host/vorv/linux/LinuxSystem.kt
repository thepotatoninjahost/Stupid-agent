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
 * "its own computer" true rather than decorative: `apt-get install -y gcc`
 * inside this system really does put a C compiler in it.
 *
 * Everything the system needs travels in the APK: the proot binary and its
 * loader (as native libraries, because Android only executes binaries the
 * installer unpacked into nativeLibraryDir) and a Debian 12 root filesystem
 * (as an asset, unpacked once into Vorv's storage).
 */
class LinuxSystem(private val context: Context) : GuestShell {

    val root: File get() = File(context.filesDir, "linux")
    val rootfs: File get() = File(root, "rootfs")
    private val loaderDir: File get() = File(root, "loader")
    private val stamp: File get() = File(root, "provisioned")

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir ?: "")

    private val payload by lazy { LinuxPayload(context) }

    private val provisioner = RootfsProvisioner(STAMP)

    /** The proot binary, at the path the runtime actually installed it. */
    fun prootExecutable(): File? = payload.executable()

    /**
     * proot's loader, from a place the kernel will execute.
     *
     * nativeLibraryDir comes first because it is the one directory Android lets
     * an app execute a binary from. The filesDir copy kept by older builds is
     * tried last: on Android 10+ it is expected to fail, which is the platform
     * saying the loader must not live in app storage at all.
     */
    fun resolveLoader(): File? =
        loaderCandidates(nativeDir, loaderDir).firstOrNull { it.isFile && it.canExecute() }

    private fun missingLoaderMessage(): String =
        "proot's loader cannot run on this install, so Linux cannot start. Android only runs " +
            "a binary the installer unpacked into nativeLibraryDir, and libforge-proot-loader.so " +
            (if (File(nativeDir, "libforge-proot-loader.so").isFile)
                "is there but not executable — reinstall the APK."
            else "is missing from it — uninstall Vorv and install this APK again.")

    fun inspectPayload(): LinuxPayload.State = payload.inspect()

    fun payloadReport(): LinuxPayload.State = payload.payloadReport()

    override fun isAvailable(): Boolean = payload.executable() != null

    fun isProvisioned(): Boolean = provisioner.isProvisioned(rootfs, stamp)

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
        val loader = resolveLoader()
        lines += "proot loader: " + (loader?.absolutePath ?: "NOTHING EXECUTABLE — reinstall the APK")
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
        // The loader is NOT copied into app storage. Android 10+ refuses to
        // execute a file the app itself wrote there (targetSdk >= 29), so a copy
        // in filesDir can never start proot — it has to stay where the installer
        // unpacked it, in nativeLibraryDir.
        val provisioned = provisioner
            .provision(
                rootfs = rootfs,
                stamp = stamp,
                loaderOk = { resolveLoader() != null },
                loaderFailure = { IllegalStateException(missingLoaderMessage()) },
                extract = { extractRootfs() },
            )
        // An install provisioned before R-2 was fixed already has a valid stamp and
        // will never be unpacked again, so the two hard links the old unpacker
        // dropped are put back in place. No re-extract: packages installed in the
        // guest survive.
        provisioned.onSuccess { RootfsLinks.repair(rootfs) }
        if (provisioned.isSuccess) {
            // A stamp only says the tar was unpacked, not that the pieces the guest
            // needs are really there. Check the tree itself, and unpack it again
            // once from the copy inside the APK before telling the owner it is bad.
            val first = GuestTree.broken(rootfs)
            if (first.isNotEmpty()) {
                rootfs.deleteRecursively()
                stamp.delete()
                runCatching { extractRootfs() }
                RootfsLinks.repair(rootfs)
                val second = GuestTree.broken(rootfs)
                if (second.isNotEmpty()) {
                    return@withContext Result.failure(IllegalStateException(GuestTree.describe(second)))
                }
            }
        }
        provisioned.map { root }
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
        val loader = resolveLoader()
            ?: return@withContext Shell(-1, "", missingLoaderMessage(), true)
        val argv = prootArgv(proot, rootfs, home, tmp, binds, workDir, command)
        val env = guestEnv(loader, tmp, proot.parentFile ?: File("/"))
        val first = launch(argv, env, home, timeoutMs)
        if (first.exitCode == 0 || first.timedOut || !first.output.contains("execve(")) {
            return@withContext first
        }
        val broken = GuestTree.broken(rootfs)
        if (broken.isNotEmpty()) {
            return@withContext Shell(
                first.exitCode,
                first.output + "\nvorv: the unpacked Linux system is broken: " + broken.joinToString("; "),
                null,
                false,
            )
        }
        // Every file the guest asks for is there, so the refusal is in the way
        // proot was told to start it. Two things can cause that and both are
        // reversible here: the two symlinks between /bin/sh and dash, and
        // PROOT_NO_SECCOMP, which is a shortcut some kernels will not take.
        val altArgv = prootArgv(proot, rootfs, home, tmp, binds, workDir, command, program = "/usr/bin/dash")
        val second = launch(altArgv, env - "PROOT_NO_SECCOMP", home, timeoutMs)
        if (second.ok) {
            return@withContext Shell(
                second.exitCode,
                second.output + "\nvorv: the guest only started by its fallback route " +
                    "(dash directly, without the no-seccomp shortcut). It works, and the plain " +
                    "route still needs fixing.",
                null,
                false,
            )
        }
        Shell(
            first.exitCode,
            first.output + "\nvorv: every file the guest needs is present and both start routes " +
                "were refused, so proot itself cannot start the guest on this phone.\n" +
                "vorv: fallback route said: " + second.output.lineSequence().take(6).joinToString(" | "),
            null,
            false,
        )
    }

    /** One proot run: start it, drain its output, enforce the timeout. */
    private fun launch(argv: List<String>, env: Map<String, String>, home: File, timeoutMs: Long): Shell {
        val builder = ProcessBuilder(argv)
        builder.environment().putAll(env)
        builder.directory(home)
        val process = runCatching { builder.redirectErrorStream(true).start() }.getOrElse {
            return Shell(-1, "", "proot could not be started: ${it.message ?: it.javaClass.simpleName}", false)
        }
        // Nothing is ever written to proot's stdin, and every pipe this leaves
        // open costs the app two file descriptors for the life of the process.
        runCatching { process.outputStream.close() }
        // Reading on this thread would block *before* the timeout is checked: a
        // command that never exits and never prints would never time out. The
        // drain runs on its own thread so waitFor() is the thing that decides.
        val out = StringBuilder()
        val drain = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(out) { if (out.length < MAX_OUTPUT) out.appendLine(line) }
                }
            }
        }.apply { isDaemon = true; name = "vorv-guest-drain"; start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            drain.join(500)
            runCatching { process.inputStream.close() }
            return Shell(-1, synchronized(out) { out.toString() }, "timed out after ${timeoutMs}ms", true)
        }
        drain.join(2_000)
        runCatching { process.inputStream.close() }
        return Shell(process.exitValue(), synchronized(out) { out.toString() }, null, false)
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
            program: String = "/bin/sh",
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
            add(program); add("-lc"); add(command)
        }

        /**
         * Every place a proot loader might be, in the order they may be used.
         *
         * Pure and ordered so a test can pin it: the installer-unpacked library
         * in nativeLibraryDir must always be chosen over a copy in app storage.
         */
        internal fun loaderCandidates(nativeDir: File, legacyLoaderDir: File): List<File> = listOf(
            File(nativeDir, "libforge-proot-loader.so"),
            File(nativeDir, "loader"),
            File(legacyLoaderDir, "loader"),
        )

        /** proot's environment: its loader, its libraries, a writable /tmp. */
        internal fun guestEnv(loader: File, tmp: File, nativeDir: File): Map<String, String> = mapOf(
            // PROOT_LOADER is exec'd by proot, so it must sit in a directory the
            // platform allows execution from — never in filesDir on Android 10+.
            "PROOT_LOADER" to loader.absolutePath,
            "PROOT_TMP_DIR" to tmp.absolutePath,
            // proot is linked against libtalloc.so and libandroid-shmem.so, whose
            // compiled-in runpath is /data/data/com.termux/files/usr/lib — a Termux
            // path that does not exist here. Without this the dynamic linker fails
            // to resolve them and proot dies before main(). Our copies sit next to
            // the binary in nativeLibraryDir, which is where this points.
            "LD_LIBRARY_PATH" to nativeDir.absolutePath,
            // The app has no seccomp filter to inherit; avoids a needless probe.
            "PROOT_NO_SECCOMP" to "1",
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "dumb",
            "LANG" to "C.UTF-8",
        )

        /** Bumped when the payload layout changes, so provision() re-extracts. */
        internal const val STAMP = "debian-12-bookworm-arm64-proot-5.1.107"

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

    /** Unpacks the root filesystem, preserving the symlinks and hard links Debian needs. */
    private fun extractRootfs() {
        context.assets.open("forge/linux/rootfs.tar").use { raw ->
            RootfsTar.extract(raw, rootfs)
        }
    }

}

/**
 * The provision decision, free of Android types so a JVM test can drive it.
 *
 * 0.9.2 shipped this ordering wrong: `provision()` asked `isProvisioned()` — which
 * requires the stamp — and only wrote the stamp afterwards, so on a fresh install
 * the check could not pass and the stamp was never written. Provisioning failed on
 * every run, on every phone, and said "the Linux root filesystem is incomplete"
 * while the tree it had just unpacked was fine. Nothing in the suite could see it
 * because only pure helpers were tested.
 *
 * The stamp is also read, not just tested for existence: the constant is bumped
 * when the payload layout changes, and a stamp that no longer equals it means the
 * unpacked tree is stale and must be built again.
 */
internal class RootfsProvisioner(private val stampValue: String) {

    fun isProvisioned(rootfs: File, stamp: File): Boolean =
        File(rootfs, MARKER).isFile &&
            stamp.isFile &&
            runCatching { stamp.readText().trim() == stampValue }.getOrDefault(false)

    /**
     * Extract when the tree is missing or stale, then confirm what landed and
     * record the stamp. A failure leaves no half-built tree and no stamp.
     */
    fun provision(
        rootfs: File,
        stamp: File,
        loaderOk: () -> Boolean = { true },
        loaderFailure: () -> Exception = { IllegalStateException(INCOMPLETE) },
        extract: () -> Unit,
    ): Result<Unit> {
        if (isProvisioned(rootfs, stamp)) return Result.success(Unit)
        try {
            rootfs.deleteRecursively()
            rootfs.mkdirs()
            extract()
            if (!loaderOk()) {
                rootfs.deleteRecursively()
                return Result.failure(loaderFailure())
            }
            if (!File(rootfs, MARKER).isFile) {
                rootfs.deleteRecursively()
                return Result.failure(IllegalStateException(INCOMPLETE))
            }
            stamp.parentFile?.mkdirs()
            stamp.writeText(stampValue)
            return Result.success(Unit)
        } catch (e: Exception) {
            rootfs.deleteRecursively()
            return Result.failure(e)
        }
    }

    companion object {
        /** Present in every Debian rootfs and in nothing else we unpack. */
        const val MARKER = "etc/debian_version"
        const val INCOMPLETE = "the Debian root filesystem is incomplete"
    }
}

/**
 * The two hard links Debian's rootfs carries, and the in-place repair for installs
 * that were provisioned before R-2 (review 2026-11-14) dropped them.
 *
 * Those installs hold a valid stamp, so the tree is never unpacked again; without
 * this, `usr/bin/perl5.36.0` and `usr/bin/uncompress` would stay missing for the
 * life of the install. Repairing only what is absent keeps it idempotent and never
 * overwrites a file a package installed in the guest owns.
 */
internal object RootfsLinks {

    val LINKS = listOf(
        "usr/bin/perl5.36.0" to "usr/bin/perl",
        "usr/bin/uncompress" to "usr/bin/gunzip",
    )

    fun repair(rootfs: File) {
        for ((name, target) in LINKS) {
            val link = File(rootfs, name)
            if (link.exists()) continue
            val source = File(rootfs, target)
            if (!source.isFile) continue
            link.parentFile?.mkdirs()
            val linked = runCatching {
                java.nio.file.Files.createLink(link.toPath(), source.toPath())
            }.isSuccess
            if (!linked) {
                runCatching {
                    source.inputStream().use { input ->
                        link.outputStream().use { input.copyTo(it) }
                    }
                    link.setExecutable(source.canExecute(), false)
                }
            }
        }
    }
}

/**
 * The files a Debian guest must have before anything is asked of it, checked the
 * way the guest will follow them.
 *
 * 0.9.10 on the owner's phone reported proot's bare `execve("/usr/bin/sh"): No
 * such file or directory` for every command, which names the program proot tried
 * and nothing about why. A missing or dangling piece in the unpacked tree looks
 * exactly like that, and so does a tree that is complete; this decides which,
 * and names the paths instead of leaving the owner with proot's guess list.
 */
internal object GuestTree {

    val REQUIRED = listOf(
        "bin/sh",
        "usr/bin/dash",
        "usr/bin/tar",
        "usr/bin/dpkg-deb",
        "usr/bin/apt-get",
        "lib/ld-linux-aarch64.so.1",
        "usr/lib/aarch64-linux-gnu/libc.so.6",
        "etc/debian_version",
    )

    private const val MAX_LINKS = 16

    /**
     * Why each required path is unusable, one sentence per path. Empty means the
     * tree is whole as far as can be seen from outside the guest.
     *
     * Symlinks are followed by hand, from the guest's root: a link written as
     * `/lib/x` or `../usr/lib/x` must resolve inside the rootfs and nowhere else.
     */
    fun broken(rootfs: File): List<String> = REQUIRED.mapNotNull { path ->
        when (val verdict = resolve(rootfs, path)) {
            null -> null
            else -> "$path — $verdict"
        }
    }

    fun describe(broken: List<String>): String =
        "The Linux system on this phone is incomplete, so it was refused rather than half-started: " +
            broken.joinToString("; ") +
            ". Uninstalling Vorv and installing the APK again rebuilds it from the copy in the app."

    /** null when [path] resolves to a readable regular file inside [rootfs]. */
    private fun resolve(rootfs: File, path: String): String? {
        var current = path
        var links = 0
        while (true) {
            val file = File(rootfs, current)
            if (!file.exists() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                return "missing"
            }
            if (!java.nio.file.Files.isSymbolicLink(file.toPath())) {
                return if (file.isFile) null else "is a folder, not a file"
            }
            if (++links > MAX_LINKS) return "is a symlink that never ends"
            val target = java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
            current = if (target.startsWith("/")) target.trimStart('/') else
                java.nio.file.Paths.get(current).parent?.resolve(target)?.normalize()?.toString()
                    ?: target
            if (current.startsWith("..")) return "is a symlink pointing outside the Linux system"
            if (current == file.path) return "is a symlink to itself"
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
                        entry.isLink -> {
                            // R-2 (review 2026-11-14): the Debian rootfs this app ships
                            // DOES contain hard links (usr/bin/perl5.36.0 -> usr/bin/perl,
                            // usr/bin/uncompress -> usr/bin/gunzip). Skipping them silently
                            // removed those paths from the guest, so commands Debian
                            // packaging expects later failed with "not found". Link the
                            // already-extracted target; copy it if the filesystem refuses.
                            val source = File(base, entry.linkName.removePrefix("./")).canonicalFile
                            if (!source.path.startsWith(base.path + File.separator)) {
                                throw SecurityException(
                                    "rootfs hard link escapes the tree: ${entry.name} -> ${entry.linkName}")
                            }
                            target.parentFile?.mkdirs()
                            target.delete()
                            if (!source.isFile) {
                                throw IllegalStateException(
                                    "rootfs hard link ${entry.name} -> ${entry.linkName} " +
                                        "names an entry that was not extracted before it")
                            }
                            val linked = runCatching {
                                java.nio.file.Files.createLink(target.toPath(), source.toPath())
                            }.isSuccess
                            if (!linked) {
                                source.inputStream().use { input ->
                                    target.outputStream().use { input.copyTo(it) }
                                }
                                target.setExecutable(source.canExecute(), false)
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { tar.copyTo(it) }
                            target.setExecutable((entry.mode and 0b001_000_000) != 0, false)
                        }
                        // Entries after this point are neither directory, symlink,
                        // hard link nor regular file: ignored deliberately, and the
                        // payload is checked for completeness elsewhere (RootfsProvisioner
                        // tests the Debian marker, and the rootfs is our own asset).
                    }
                }
            }
        }
    }
}
