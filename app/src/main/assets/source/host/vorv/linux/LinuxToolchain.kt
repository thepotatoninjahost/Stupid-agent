package host.vorv.linux

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Installs the Android build tools into the Debian guest.
 *
 * The toolchain is 203 MB of Debian packages — too much to ship inside the APK —
 * so it is downloaded once, on the owner's connection, and verified package by
 * package against the sha256 apt published. Only then does anything get written.
 *
 * The unpacking is done by `dpkg-deb -x` *inside* the guest, not by this class.
 * 0.9.5 tried to lay the files down from the app and died with "Too many symbolic
 * links": Debian's packages are full of link chains, and resolving those through
 * the app's view of the filesystem is not the same as resolving them inside the
 * Linux system. dpkg-deb is the tool built for exactly this, and inside the guest
 * an absolute link means the guest's own root, as the package author intended.
 *
 * Nothing here runs on its own: the owner presses INSTALL BUILD TOOLS, and every
 * package name and byte count is reported as it happens.
 */
class LinuxToolchain(
    private val rootfs: File,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val runInGuest: suspend (String) -> LinuxSystem.Shell,
) {

    data class Package(
        val name: String,
        val version: String,
        val url: String,
        val sha256: String,
        val size: Long,
    )

    private data class Plan(val what: String = "", val packages: List<Package> = emptyList())

    data class Progress(
        val done: Int,
        val total: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val current: String,
    ) {
        fun line(): String {
            fun mb(v: Long) = String.format("%.1f MB", v / 1e6)
            return "$done/$total packages — ${mb(bytesDone)} of ${mb(bytesTotal)} — $current"
        }
    }

    /**
     * Inside the guest, so `dpkg-deb` can read them without a bind mount and the
     * files stay with the computer rather than in a cache Android may clear.
     */
    private val debsDir: File get() = File(rootfs, DEBS_IN_GUEST.trim('/'))
    private val stampFile: File get() = File(rootfs, STAMP_PATH)

    fun plan(planJson: String): List<Package> =
        (gson.fromJson(planJson, Plan::class.java)?.packages ?: emptyList())

    suspend fun install(
        plan: List<Package>,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> = try {
        if (plan.isEmpty()) error("the toolchain plan is empty — this build carries no package list")
        rootfs.mkdirs()
        debsDir.mkdirs()
        requireDpkg()

        val total = plan.sumOf { it.size }
        var done = 0L
        for ((index, pkg) in plan.withIndex()) {
            onProgress(Progress(index, plan.size, done, total, "fetching ${pkg.name}"))
            val file = download(pkg)
            onProgress(Progress(index, plan.size, done, total, "unpacking ${pkg.name}"))
            unpackInGuest(file)
            done += pkg.size
            onProgress(Progress(index + 1, plan.size, done, total, pkg.name))
        }

        val javaHome = linkJavaTools()

        // Verified by asking the guest, not by looking at the app's own view of
        // the tree: a file can exist on one side and not the other.
        val check = runInGuest("test -x /usr/bin/javac && test -x /usr/bin/aapt")
        if (!check.ok) {
            error(
                "all packages unpacked, but the guest still has no runnable javac/aapt " +
                    "(exit ${check.exitCode}) — the plan and this Debian root do not agree",
            )
        }
        stampFile.parentFile?.mkdirs()
        stampFile.writeText(
            plan.joinToString("\n") { "${it.name} ${it.version}" } + "\nJAVA_HOME=" + javaHome + "\n",
        )
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private suspend fun requireDpkg() {
        val probe = runInGuest("command -v dpkg-deb")
        if (!probe.ok) {
            error(
                "this Debian guest has no dpkg-deb, so packages cannot be unpacked " +
                    "(exit ${probe.exitCode}: ${probe.output.trim().take(120)})",
            )
        }
    }

    private fun debPath(pkg: Package) = File(debsDir, pkg.name + "_" + pkg.version + ".deb")

    private fun download(pkg: Package): File {
        val dest = debPath(pkg)
        if (dest.isFile && sha256(dest) == pkg.sha256) return dest
        dest.delete()
        val part = File(dest.parentFile, dest.name + ".part")
        val request = Request.Builder().url(pkg.url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("${pkg.name}: HTTP ${response.code} from ${pkg.url}")
            val body = response.body ?: error("${pkg.name}: empty response")
            part.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        val got = sha256(part)
        if (got != pkg.sha256) {
            part.delete()
            error("REFUSED ${pkg.name}: sha256 is $got, the plan says ${pkg.sha256}")
        }
        if (!part.renameTo(dest)) error("${pkg.name}: could not store the package")
        return dest
    }

    /**
     * `dpkg-deb -x` lays files down but runs no post-install script, so Debian's
     * `/usr/bin/javac` (an update-alternatives link) is never created — the JDK
     * really lives at java-1.17.0-openjdk-arm64/bin/ under /usr/lib/jvm.
     * 0.9.6 found exactly that: aapt present, javac absent. Make the links here,
     * against whatever JDK the plan actually installed, and report its home.
     */
    private suspend fun linkJavaTools(): String {
        val shell = runInGuest(JAVA_LINK_COMMAND)
        if (!shell.ok) {
            error(
                "the JDK unpacked but its bin/ could not be linked into /usr/bin " +
                    "(exit ${shell.exitCode}: ${shell.output.trim().take(200)})",
            )
        }
        return shell.output.lineSequence()
            .firstOrNull { it.startsWith("JAVA_HOME=") }
            ?.removePrefix("JAVA_HOME=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("the JDK unpacked but no home was reported: ${shell.output.trim().take(200)}")
    }

    private suspend fun unpackInGuest(deb: File) {
        val guestPath = DEBS_IN_GUEST.trimEnd('/') + "/" + deb.name
        val shell = runInGuest("dpkg-deb -x '$guestPath' /")
        if (!shell.ok) {
            error(
                "dpkg-deb could not unpack ${deb.name} (exit ${shell.exitCode}): " +
                    shell.output.trim().lines().takeLast(3).joinToString(" | ").take(300),
            )
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val STAMP_PATH = "etc/vorv-toolchain"
        const val ASSET = "forge/linux/toolchain.json"
        /**
         * Picks the installed JDK whatever its exact version, then links the five
         * tools a build needs. `ln -sf` is idempotent, so a re-run is harmless.
         */
        const val JAVA_LINK_COMMAND =
            "J=\$(ls -d /usr/lib/jvm/java-1*-openjdk-* 2>/dev/null | head -1); " +
                "if [ -z \"\$J\" ]; then echo 'no openjdk under /usr/lib/jvm' >&2; exit 3; fi; " +
                "for t in java javac jar javap keytool; do " +
                "if [ -x \"\$J/bin/\$t\" ]; then ln -sf \"\$J/bin/\$t\" /usr/bin/\$t; fi; done; " +
                "echo \"JAVA_HOME=\$J\""

        /** Where the .debs live inside the guest. */
        const val DEBS_IN_GUEST = "/var/cache/vorv-debs"

        /** True when javac and aapt exist on disk, independent of any guest run. */
        fun looksInstalled(rootfs: File): Boolean =
            File(rootfs, "usr/bin/javac").isFile && File(rootfs, "usr/bin/aapt").isFile
    }
}
