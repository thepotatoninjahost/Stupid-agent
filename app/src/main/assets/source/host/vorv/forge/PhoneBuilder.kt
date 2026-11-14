package host.vorv.forge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Turns a folder of Java source into a signed, installable APK, on the phone.
 *
 * The pipeline is the one the Android SDK uses, only with Vorv's own copies of
 * each tool: javac compiles against the Android API jar, d8 turns the class
 * files into classes.dex, that dex replaces the placeholder inside a
 * resource-free template APK, and apksigner signs the result.
 *
 * The template exists so resources never have to be compiled on the device:
 * its manifest, label and launcher activity are already built. An app built
 * here therefore starts at `host.vorv.app.Main` and builds its screen in code.
 */
class PhoneBuilder(
    private val context: Context,
    private val runtime: ForgeRuntime,
) {

    data class Build(
        val ok: Boolean,
        val apk: File?,
        val steps: List<String>,
        val error: String?,
    )

    suspend fun build(project: File, outputName: String = "app"): Build = withContext(Dispatchers.IO) {
        val steps = mutableListOf<String>()
        fun fail(step: String, detail: String): Build {
            steps += "FAIL $step: $detail"
            return Build(false, null, steps, detail)
        }

        runtime.provision().onFailure { return@withContext fail("runtime", it.message ?: it.javaClass.simpleName) }

        val sources = project.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        if (sources.isEmpty()) return@withContext fail("sources", "no .java files in ${project.name}")
        val entry = sources.firstOrNull { it.name == "Main.java" }
            ?: return@withContext fail("sources", "no Main.java: a built app starts at host.vorv.app.Main")

        val work = File(project.parentFile, "${project.name}-build").apply { deleteRecursively(); mkdirs() }
        val classes = File(work, "classes").apply { mkdirs() }
        val dex = File(work, "dex").apply { mkdirs() }

        val javac = runtime.exec(
            runtime.tool(
                "javac",
                "-source", "8", "-target", "8",
                "-bootclasspath", runtime.androidJar.absolutePath,
                "-classpath", runtime.androidJar.absolutePath,
                "-encoding", "UTF-8",
                "-nowarn",
                "-d", classes.absolutePath,
                *sources.map { it.absolutePath }.toTypedArray(),
            ),
        )
        if (!javac.ok) return@withContext fail("javac", javac.tail())
        steps += "javac: ${sources.size} file(s) → ${javac.ms}ms"

        val classFiles = classes.walkTopDown().filter { it.extension == "class" }.toList()
        if (classFiles.isEmpty()) return@withContext fail("javac", "compiled nothing")
        val dexed = runtime.exec(
            runtime.tool(
                "java",
                "-cp", runtime.d8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--lib", runtime.androidJar.absolutePath,
                "--min-api", "24",
                "--release",
                "--output", dex.absolutePath,
                *classFiles.map { it.absolutePath }.toTypedArray(),
            ),
        )
        if (!dexed.ok) return@withContext fail("d8", dexed.tail())
        val dexFile = File(dex, "classes.dex")
        if (!dexFile.isFile) return@withContext fail("d8", "no classes.dex produced")
        steps += "d8: ${classFiles.size} class(es) → classes.dex ${dexFile.length()}b"

        val unsigned = File(work, "$outputName-unsigned.apk")
        try {
            packApk(dexFile, unsigned)
        } catch (e: Exception) {
            return@withContext fail("package", e.message ?: "could not write the apk")
        }
        steps += "package: ${unsigned.length()}b"

        val keystore = ensureKeystore() ?: return@withContext fail("sign", "could not create the signing key")
        val signed = File(work, "$outputName.apk")
        val sign = runtime.exec(
            listOf(
                runtime.tool("java").first(), "-Djava.home=${runtime.home.absolutePath}",
                "-jar", runtime.apksignerJar.absolutePath,
                "sign",
                "--ks", keystore.file.absolutePath,
                "--ks-pass", "pass:${keystore.password}",
                "--key-pass", "pass:${keystore.password}",
                "--ks-key-alias", KEY_ALIAS,
                "--out", signed.absolutePath,
                unsigned.absolutePath,
            ),
        )
        if (!sign.ok) return@withContext fail("sign", sign.tail())
        steps += "sign: ${signed.length()}b"

        // A signature that was not checked is a guess: ask apksigner itself.
        val verify = runtime.exec(
            listOf(
                runtime.tool("java").first(), "-Djava.home=${runtime.home.absolutePath}",
                "-jar", runtime.apksignerJar.absolutePath,
                "verify", "--print-certs", signed.absolutePath,
            ),
        )
        if (!verify.ok) return@withContext fail("verify", verify.tail())
        val signer = verify.output.lineSequence().firstOrNull { it.contains("certificate DN") }?.trim()
            ?: return@withContext fail("verify", "signature verified but no signer was reported")
        steps += "verify: $signer"

        Build(true, signed, steps, null)
    }

    /** Copies the template, swaps in the freshly built dex, and stores it uncompressed. */
    private fun packApk(dex: File, out: File) {
        val template = File.createTempFile("template", ".apk", context.cacheDir)
        context.assets.open("forge/template.apk").use { input -> template.outputStream().use { input.copyTo(it) } }
        ZipFile(template).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { writer ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name == "classes.dex") return@forEach
                    writer.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(writer) }
                    writer.closeEntry()
                }
                val dexEntry = ZipEntry("classes.dex").apply {
                    size = dex.length()
                    time = 315532800000L // fixed stamp: identical source → identical apk
                }
                writer.putNextEntry(dexEntry)
                dex.inputStream().use { it.copyTo(writer) }
                writer.closeEntry()
            }
        }
        template.delete()
    }

    /**
     * One signing key per app install, created on first build and kept in
     * private storage. Android identifies an app by this key, so it must never
     * be regenerated under a name already in use — an update signed with a new
     * key is rejected, which is the correct, visible failure.
     */
    private fun ensureKeystore(): Key? {
        val file = File(context.filesDir, "forge/phone.jks")
        val secret = File(context.filesDir, "forge/phone.pass")
        if (file.isFile && secret.isFile) return Key(file, secret.readText().trim())
        val password = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val made = runtime.exec(
            runtime.tool(
                "keytool",
                "-genkeypair",
                "-keystore", file.absolutePath,
                "-storepass", password,
                "-keypass", password,
                "-alias", KEY_ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-dname", "CN=Vorv",
            ),
            timeoutMs = 120_000,
        )
        if (!made.ok || !file.isFile) return null
        secret.parentFile?.mkdirs()
        secret.writeText(password)
        return Key(file, password)
    }

    private data class Key(val file: File, val password: String)

    private companion object {
        const val KEY_ALIAS = "vorv"
    }
}
