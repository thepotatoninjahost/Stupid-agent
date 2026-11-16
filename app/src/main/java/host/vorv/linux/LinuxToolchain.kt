package host.vorv.linux

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Installs the Android build tools into the Debian guest.
 *
 * The toolchain is 203 MB of Debian packages — too much to ship inside the APK —
 * so it is downloaded once, on the owner's connection, and unpacked into the
 * guest's own filesystem. Every download is checked against the sha256 apt
 * published for it before a single byte is written into the root, so a swapped,
 * truncated or hijacked response fails here with a sentence instead of leaving a
 * half-installed javac behind.
 *
 * Nothing here runs on its own: the owner presses INSTALL BUILD TOOLS, and every
 * package name and byte count is reported as it happens.
 */
class LinuxToolchain(
    private val rootfs: File,
    private val cacheDir: File,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
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

    private val stampFile: File get() = File(rootfs, STAMP_PATH)
    private val javac: File get() = File(rootfs, "usr/bin/javac")
    private val aapt: File get() = File(rootfs, "usr/bin/aapt")

    /** True when javac and aapt are really present, not merely believed to be. */
    fun isInstalled(): Boolean = javac.isFile && aapt.isFile

    fun plan(planJson: String): List<Package> =
        (gson.fromJson(planJson, Plan::class.java)?.packages ?: emptyList())

    fun install(
        plan: List<Package>,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> = runCatching {
        if (plan.isEmpty()) error("the toolchain plan is empty — this build carries no package list")
        rootfs.mkdirs()
        cacheDir.mkdirs()
        val total = plan.sumOf { it.size }
        var done = 0L
        plan.forEachIndexed { index, pkg ->
            onProgress(Progress(index, plan.size, done, total, "fetching ${pkg.name}"))
            val file = download(pkg)
            onProgress(Progress(index, plan.size, done, total, "unpacking ${pkg.name}"))
            extractDataTar(file)
            done += pkg.size
            onProgress(Progress(index + 1, plan.size, done, total, pkg.name))
        }
        // The stamp is written only after javac and aapt exist, so a half-install
        // is never mistaken for a finished one.
        val missing = listOf(javac, aapt).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            error(
                "the packages unpacked but ${missing.joinToString { it.name }} is still missing — " +
                    "the toolchain plan and the guest do not agree",
            )
        }
        stampFile.parentFile?.mkdirs()
        stampFile.writeText(plan.joinToString("\n") { it.name + " " + it.version })
    }

    private fun download(pkg: Package): File {
        val dest = File(cacheDir, pkg.name + "-" + pkg.version + ".deb")
        if (dest.isFile && sha256(dest) == pkg.sha256) return dest
        dest.delete()
        val part = File(cacheDir, dest.name + ".part")
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
        part.renameTo(dest)
        return dest
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

    /** A .deb is an ar archive; the payload is `data.tar.<xz|gz|bz2>`. */
    private fun extractDataTar(deb: File) {
        deb.inputStream().buffered().use { raw ->
            ArArchiveInputStream(raw).use { ar ->
                while (true) {
                    val entry = ar.nextEntry ?: break
                    if (!entry.name.startsWith("data.tar")) continue
                    val decompressed: InputStream = when {
                        entry.name.endsWith(".xz") -> XZCompressorInputStream(ar)
                        entry.name.endsWith(".gz") -> GzipCompressorInputStream(ar)
                        entry.name.endsWith(".bz2") -> BZip2CompressorInputStream(ar)
                        entry.name.endsWith(".tar") -> ar
                        else -> error("unexpected payload ${entry.name} in ${deb.name}")
                    }
                    unpack(decompressed)
                    return
                }
            }
        }
        error("${deb.name} has no data.tar payload")
    }

    private fun unpack(stream: InputStream) {
        val base = rootfs.canonicalFile
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || name == ".") continue
                val target = File(base, name).canonicalFile
                if (target != base && !target.path.startsWith(base.path + File.separator)) {
                    error("payload entry escapes the guest: ${entry.name}")
                }
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        target.delete()
                        runCatching {
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(), java.nio.file.Paths.get(entry.linkName),
                            )
                        }.onFailure {
                            // A link we cannot create is not fatal as long as the
                            // real file it points at is packaged too.
                        }
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val source = File(base, entry.linkName.removePrefix("./")).canonicalFile
                        if (source.isFile) {
                            target.delete()
                            source.copyTo(target, overwrite = true)
                            target.setExecutable(source.canExecute(), false)
                        }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { tar.copyTo(it) }
                        // Debian debs carry real modes; javac without the exec bit
                        // is a file that exists and cannot run.
                        val mode = entry.mode
                        target.setReadable(true, false)
                        target.setWritable((mode and 0b010_000_000) != 0, false)
                        target.setExecutable((mode and 0b001_000_000) != 0, false)
                    }
                }
            }
        }
    }

    companion object {
        const val STAMP_PATH = "etc/vorv-toolchain"
        const val ASSET = "forge/linux/toolchain.json"
    }
}
