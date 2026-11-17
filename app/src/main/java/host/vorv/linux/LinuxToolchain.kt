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

    /** First reason the guest gave for a package it refused. Cleared per install. */
    private var lastWhy: String = ""

    fun plan(planJson: String): List<Package> =
        (gson.fromJson(planJson, Plan::class.java)?.packages ?: emptyList())

    suspend fun install(
        plan: List<Package>,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> = try {
        if (plan.isEmpty()) error("the toolchain plan is empty — this build carries no package list")
        lastWhy = ""
        rootfs.mkdirs()
        debsDir.mkdirs()
        requireDpkg()

        val total = plan.sumOf { it.size }
        var done = 0L
        val files = ArrayList<String>(plan.size)
        for ((index, pkg) in plan.withIndex()) {
            onProgress(Progress(index, plan.size, done, total, "fetching ${pkg.name}"))
            val file = download(pkg)
            files += DEBS_IN_GUEST.trimEnd('/') + "/" + file.name
            done += pkg.size
            onProgress(Progress(index + 1, plan.size, done, total, pkg.name))
        }

        // 0.9.6 started one proot for every one of the 169 packages and died on
        // the 31st with proot unable to exec /bin/sh. Each of those starts was a
        // fork, a ptrace attach and a fresh proot init on a phone; unpacking in
        // a few sessions instead is what proot-distro does, and it means 169
        // package installs cost six proot starts rather than 169.
        val batches = files.chunked(BATCH_SIZE)
        val failed = mutableListOf<String>()
        batches.forEachIndexed { index, batch ->
            val last = (index * BATCH_SIZE) + batch.size
            onProgress(
                Progress(
                    plan.size, plan.size, total, total,
                    "unpacking packages ${index * BATCH_SIZE + 1}-$last of ${plan.size}",
                ),
            )
            // A dead session is retried package by package inside unpackBatch.
            // Whatever survives that is collected here and the remaining batches
            // still run: one bad session must not throw away the other 144
            // packages, and a retried install skips what already landed.
            failed += unpackBatch(batch)
        }
        if (failed.isNotEmpty()) error(unpackFailure(failed))

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

    /**
     * Unpacks one batch of packages in a single guest session.
     *
     * Each package is unpacked by Debian's own dpkg-deb; if that refuses, the
     * package is retried through `--fsys-tarfile | tar -xf`, which skips the
     * helper tar process dpkg-deb starts itself. A package that still fails is
     * named in the message together with the first thing proot or dpkg said,
     * because "exit 1" alone told us nothing on the phone in 0.9.6.
     */
    private suspend fun unpackBatch(paths: List<String>): List<String> {
        var shell = runInGuest(batchScript(paths))
        if (shell.ok) return emptyList()

        // proot's own child can be refused by the phone (fork/exec under memory
        // or process pressure). One clean retry costs seconds and is what the
        // owner should never see as an install failure.
        val blip = shell.output.contains("fork()") ||
            shell.output.contains("Resource temporarily unavailable")
        if (blip) {
            kotlinx.coroutines.delay(1_500)
            shell = runInGuest(batchScript(paths))
            if (shell.ok) return emptyList()
        }

        val lines = shell.output.lineSequence().toList()
        var missing = lines.firstOrNull { it.startsWith(MISSING_MARK) }
            ?.removePrefix(MISSING_MARK)
            ?.trim()
            ?.split(' ')
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        // 0.9.7 on the owner's phone: one batch of 25 died with "tar: not found"
        // even though /usr/bin/tar is in the rootfs (verified in the shipped
        // rootfs.tar). proot had refused to exec a child inside that session, so
        // every package in that batch looked broken. A batch-sized session is
        // convenient, not required — retry only the casualties, each alone, in a
        // session of its own, before anything is called failed.
        if (missing.isEmpty()) {
            // A failed session that printed no marker: retry the whole batch once
            // more alone, package at a time, rather than blaming the packages.
            missing = paths
        }
        val stillFailing = mutableListOf<String>()
        missing.forEach { name ->
            val path = paths.firstOrNull { it.endsWith("/$name") } ?: name
            var ok = false
            repeat(2) { attempt ->
                if (!ok) {
                    if (attempt > 0) kotlinx.coroutines.delay(700)
                    val one = runInGuest(batchScript(listOf(path)))
                    ok = one.ok
                    if (!ok && lastWhy.isEmpty()) {
                        lastWhy = one.output.lineSequence()
                            .filter {
                                it.contains("proot error") || it.startsWith("tar:") ||
                                    it.startsWith("dpkg-deb:")
                            }
                            .take(2).joinToString(" | ")
                    }
                }
            }
            if (!ok) stillFailing += File(name).name
        }
        if (stillFailing.isEmpty()) return emptyList()

        if (lastWhy.isEmpty()) {
            lastWhy = lines
                .filter {
                    it.contains("proot error") || it.startsWith("dpkg-deb:") ||
                        it.startsWith("tar:") || it.startsWith("sh:")
                }
                .take(4)
                .joinToString(" | ")
        }
        if (shell.timedOut) lastWhy = (lastWhy + " the guest timed out").trim()
        return stillFailing
    }

    /** One sentence the owner can read: what is still missing, and what the guest said. */
    private fun unpackFailure(names: List<String>): String {
        val headline = "${names.size} package(s) could not be unpacked inside the guest: " +
            names.joinToString(", ").take(300)
        return headline + if (lastWhy.isNotEmpty()) {
            " — proot/dpkg said: ${lastWhy.take(400)}"
        } else {
            " — the guest gave no reason; installing again skips what already landed"
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

        /** One marker per unpacked package, so a retried install resumes. */
        const val UNPACKED_DIR = "/var/cache/vorv-unpacked"

        /**
         * Packages unpacked per guest session. dpkg-deb asks the kernel for very
         * little, so a batch is cheap inside one proot; the number is about
         * keeping one failed session small enough to name its packages.
         */
        const val BATCH_SIZE = 25

        const val MISSING_MARK = "@@MISSING"

        /**
         * The shell program a batch of unpacks runs as. Built here, not inline,
         * so a test can pin the exact text that reaches the guest.
         */
        internal fun batchScript(paths: List<String>): String = buildString {
            append("d=").append(UNPACKED_DIR).append("; mkdir -p \"\$d\"; fail=''; ")
            append("for f in")
            paths.forEach { append(" '").append(it.replace("'", "")).append("'") }
            append("; do n=\${f##*/}; ")
            append("if [ -f \"\$d/\$n\" ]; then echo \"@@OK \$n (already unpacked)\"; continue; fi; ")
            append("echo \"@@PKG \$n\"; ")
            append("if dpkg-deb -x \"\$f\" / ; then : > \"\$d/\$n\"; echo \"@@OK \$n\"; ")
            append("elif dpkg-deb --fsys-tarfile \"\$f\" | tar -xf - -C / ; then : > \"\$d/\$n\"; echo \"@@OK \$n via tar\"; ")
            append("else echo \"@@FAILED \$n\"; fail=\"\$fail \$n\"; fi; done; ")
            append("if [ -n \"\$fail\" ]; then echo \"$MISSING_MARK\$fail\"; exit 4; fi; ")
            append("echo '@@BATCH OK'")
        }

        /** True when javac and aapt exist on disk, independent of any guest run. */
        fun looksInstalled(rootfs: File): Boolean =
            File(rootfs, "usr/bin/javac").isFile && File(rootfs, "usr/bin/aapt").isFile
    }
}
