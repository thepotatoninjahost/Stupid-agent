package host.vorv.linux

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The toolchain is 203 MB from the internet, unpacked into the guest by dpkg-deb.
 * These tests cover what can silently ruin that: believing a wrong archive, never
 * noticing that dpkg-deb failed, and stamping a half-install as finished.
 *
 * The guest is simulated: `dpkg-deb -x` is stood in for by a plain tar extract, so
 * the download, sha256 check, exit-code handling and stamp logic are the real ones.
 * Whether Debian's own dpkg-deb accepts the packages is a phone question.
 */
class LinuxToolchainTest {

    private val server = MockWebServer()
    private val work: File = Files.createTempDirectory("vorv-toolchain").toFile()
    private val commands = mutableListOf<String>()

    @After fun tearDown() {
        server.shutdown()
        work.deleteRecursively()
    }

    private fun deb(files: Map<String, Pair<String, Int>>): ByteArray {
        val data = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(data)).use { tar ->
            files.forEach { (path, spec) ->
                val bytes = spec.first.toByteArray()
                tar.putArchiveEntry(
                    TarArchiveEntry(path).apply { size = bytes.size.toLong(); mode = spec.second },
                )
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        val out = ByteArrayOutputStream()
        ArArchiveOutputStream(out).use { ar ->
            val payload = data.toByteArray()
            ar.putArchiveEntry(ArArchiveEntry("data.tar.gz", payload.size.toLong()))
            ar.write(payload)
            ar.closeArchiveEntry()
        }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pkg(name: String, bytes: ByteArray, shaOverride: String? = null) =
        LinuxToolchain.Package(
            name = name,
            version = "1.0",
            url = server.url("/$name.deb").toString(),
            sha256 = shaOverride ?: sha(bytes),
            size = bytes.size.toLong(),
        )

    /** A guest that answers like Debian would, and unpacks what it is handed. */
    private fun guest(
        rootfs: File,
        hasDpkg: Boolean = true,
        unpackExit: Int = 0,
        failNames: Set<String> = emptySet(),
        failMulti: Boolean = false,
    ): suspend (String) -> LinuxSystem.Shell = { command ->
        commands += command
        when {
            command.startsWith("command -v dpkg-deb") ->
                if (hasDpkg) LinuxSystem.Shell(0, "/usr/bin/dpkg-deb", null, false)
                else LinuxSystem.Shell(1, "", "not found", false)
            command.startsWith("J=") ->
                LinuxSystem.Shell(0, "JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64", null, false)
            command.startsWith("test -x /usr/bin/javac") ->
                if (File(rootfs, "usr/bin/javac").canExecute() && File(rootfs, "usr/bin/aapt").canExecute()) {
                    LinuxSystem.Shell(0, "", null, false)
                } else {
                    LinuxSystem.Shell(1, "", null, false)
                }
            command.contains("for f in") -> {
                val paths = Regex("'([^']*\\.deb)'").findAll(command).map { it.groupValues[1] }.toList()
                if (failMulti && paths.size > 1) {
                    // A whole session dies, exactly as proot did on the phone in
                    // 0.9.7: every package in the batch looks broken, and none of
                    // them is.
                    val names = paths.map { File(it).name }
                    LinuxSystem.Shell(
                        4,
                        names.joinToString("\n") { "@@FAILED $it" } + "\n" +
                            LinuxToolchain.MISSING_MARK + " " + names.joinToString(" "),
                        null,
                        false,
                    )
                } else {
                    val log = StringBuilder()
                    val failed = mutableListOf<String>()
                    paths.forEach { path ->
                        val name = File(path).name
                        log.appendLine("@@PKG $name")
                        if (unpackExit == 0 && name !in failNames) {
                            val deb = File(rootfs, LinuxToolchain.DEBS_IN_GUEST.trim('/') + "/" + name)
                            unpackForTest(deb, rootfs)
                            log.appendLine("@@OK $name")
                        } else {
                            failed += name
                            log.appendLine("@@FAILED $name")
                            log.appendLine("proot error: execve(\"/bin/sh\"): No such file or directory")
                        }
                    }
                    if (failed.isEmpty()) LinuxSystem.Shell(0, log.toString(), null, false)
                    else LinuxSystem.Shell(
                        4,
                        log.appendLine("${LinuxToolchain.MISSING_MARK} ${failed.joinToString(" ")}").toString(),
                        null,
                        false,
                    )
                }
            }
            else -> LinuxSystem.Shell(127, "", "unexpected command", false)
        }
    }

    /** Stand-in for dpkg-deb: lay the payload down with the modes it carries. */
    private fun unpackForTest(deb: File, rootfs: File) {
        var payload: ByteArrayOutputStream? = null
        deb.inputStream().use { raw ->
            org.apache.commons.compress.archivers.ar.ArArchiveInputStream(raw).use { ar ->
                while (true) {
                    val entry = ar.nextEntry ?: break
                    if (!entry.name.startsWith("data.tar")) continue
                    payload = ByteArrayOutputStream().also { ar.copyTo(it) }
                }
            }
        }
        TarArchiveInputStream(GzipCompressorInputStream(ByteArrayInputStream(payload!!.toByteArray()))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val target = File(rootfs, entry.name)
                if (entry.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it) }
                target.setExecutable((entry.mode and 0b001_000_000) != 0, false)
            }
        }
    }

    @Test
    fun `a package whose bytes do not match its sha256 is refused and nothing lands`() = runTest {
        val bytes = deb(mapOf("usr/bin/javac" to ("#!/bin/sh\n" to 0b111_101_101)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(rootfs, runInGuest = guest(rootfs))
            .install(listOf(pkg("openjdk", bytes, shaOverride = "0".repeat(64)))) { }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.startsWith("REFUSED"))
        assertFalse(File(rootfs, "usr/bin/javac").exists())
        assertFalse(commands.any { it.startsWith("dpkg-deb -x") })
    }

    @Test
    fun `every verified package is handed to dpkg-deb inside the guest and an exit 0 stamps the install`() = runTest {
        val bytes = deb(
            mapOf(
                "usr/bin/javac" to ("#!/bin/sh\n" to 0b111_101_101),
                "usr/bin/aapt" to ("#!/bin/sh\n" to 0b111_101_101),
            ),
        )
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val rootfs = File(work, "rootfs")
        val seen = mutableListOf<String>()
        val result = LinuxToolchain(rootfs, runInGuest = guest(rootfs))
            .install(listOf(pkg("jdk", bytes))) { seen += it.line() }

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertTrue(File(rootfs, "usr/bin/javac").canExecute())
        assertTrue(
            commands.any {
                it.contains("dpkg-deb -x") && it.contains("'${LinuxToolchain.DEBS_IN_GUEST}/jdk_1.0.deb'")
            },
        )
        assertTrue(File(rootfs, LinuxToolchain.STAMP_PATH).isFile)
        // The link step is what makes /usr/bin/javac exist after dpkg-deb -x.
        assertTrue(commands.any { it.startsWith("J=") })
        assertTrue(File(rootfs, LinuxToolchain.STAMP_PATH).readText().contains("JAVA_HOME=/usr/lib/jvm/"))
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.last().contains("1/1 packages"))
    }

    @Test
    fun `a dpkg-deb that fails is reported and nothing is stamped`() = runTest {
        val bytes = deb(mapOf("usr/bin/javac" to ("x" to 0b111_101_101)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(rootfs, runInGuest = guest(rootfs, unpackExit = 2))
            .install(listOf(pkg("jdk", bytes))) { }

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("could not be unpacked inside the guest: jdk_1.0.deb"))
        assertFalse(File(rootfs, LinuxToolchain.STAMP_PATH).exists())
    }

    @Test
    fun `a guest without dpkg-deb is reported, not half-installed`() = runTest {
        val bytes = deb(mapOf("usr/bin/javac" to ("x" to 0b111_101_101)))
        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(rootfs, runInGuest = guest(rootfs, hasDpkg = false))
            .install(listOf(pkg("jdk", bytes))) { }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no dpkg-deb"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a package set without javac or aapt is reported by the guest check, not stamped`() = runTest {
        val bytes = deb(mapOf("usr/share/doc/readme" to ("x" to 0b110_100_100)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(rootfs, runInGuest = guest(rootfs))
            .install(listOf(pkg("docs", bytes))) { }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no runnable javac/aapt"))
        assertFalse(File(rootfs, LinuxToolchain.STAMP_PATH).exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a package the guest refuses is named, with what proot said, and nothing is stamped`() = runTest {
        val bytes = deb(mapOf("usr/share/doc/a" to ("a" to 0b110_100_100)))
        val other = deb(mapOf("usr/share/doc/b" to ("b" to 0b110_100_100)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(other)))
        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(
            rootfs,
            runInGuest = guest(rootfs, failNames = setOf("bad_1.0.deb")),
        ).install(listOf(pkg("good", bytes), pkg("bad", other))) { }

        val message = result.exceptionOrNull()?.message ?: error("expected a failure")
        assertTrue(message, message.contains("1 package(s) could not be unpacked"))
        assertTrue(message, message.contains("bad_1.0.deb"))
        assertTrue(message, message.contains("proot error"))
        assertFalse(File(rootfs, LinuxToolchain.STAMP_PATH).exists())
    }

    @Test
    fun `packages are unpacked a batch per guest session, not one proot each`() = runTest {
        val bytes = deb(mapOf("usr/bin/javac" to ("#!/bin/sh\n" to 0b111_101_101), "usr/bin/aapt" to ("x" to 0b111_101_101)))
        val count = 60
        repeat(count) { server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes))) }
        val rootfs = File(work, "rootfs")
        val plan = (1..count).map { pkg("p$it", bytes) }
        val result = LinuxToolchain(rootfs, runInGuest = guest(rootfs)).install(plan) { }

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        val sessions = commands.count { it.contains("for f in") }
        assertEquals((count + LinuxToolchain.BATCH_SIZE - 1) / LinuxToolchain.BATCH_SIZE, sessions)
        // Every package still reached dpkg-deb exactly once.
        val handed = commands.sumOf { c -> Regex("'/var/cache/vorv-debs/[^']+\\.deb'").findAll(c).count() }
        assertEquals(count, handed)
    }

    @Test
    fun `a batch session that dies is retried package by package before anything is called failed`() = runTest {
        // 0.9.7 on the owner's phone reported 20 packages as broken when the
        // truth was one dead proot session: /usr/bin/tar was in the rootfs all
        // along. The install must recover on its own.
        val bytes = deb(
            mapOf(
                "usr/bin/javac" to ("#!/bin/sh\n" to 0b111_101_101),
                "usr/bin/aapt" to ("#!/bin/sh\n" to 0b111_101_101),
            ),
        )
        val other = deb(mapOf("usr/lib/libfoo.so" to ("x" to 0b110_100_100)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(other)))
        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(
            rootfs,
            runInGuest = guest(rootfs, failMulti = true),
        ).install(listOf(pkg("jdk", bytes), pkg("libfoo", other))) { }

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertTrue(File(rootfs, "usr/bin/javac").exists())
        assertTrue(File(rootfs, "usr/lib/libfoo.so").exists())
        // One batch session that dies, then one session per casualty: alone, in
        // a fresh session, each package unpacks first time.
        val sessions = commands.count { it.contains("for f in") }
        assertEquals(3, sessions)
        assertTrue(File(rootfs, LinuxToolchain.STAMP_PATH).exists())
    }

    @Test
    fun `a package that still refuses stops the install and no later batch runs`() = runTest {
        // The owner's rule: a computer missing one piece is never handed over, so
        // the install must stop at the batch that failed instead of unpacking the
        // rest and leaving a half-built guest behind.
        val bytes = deb(
            mapOf(
                "usr/bin/javac" to ("#!/bin/sh\n" to 0b111_101_101),
                "usr/bin/aapt" to ("#!/bin/sh\n" to 0b111_101_101),
            ),
        )
        val count = LinuxToolchain.BATCH_SIZE + 5
        val plan = (1..count).map { pkg("p$it", bytes) }
        plan.forEach { server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes))) }

        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(
            rootfs,
            runInGuest = guest(rootfs, failNames = setOf("p1_1.0.deb")),
        ).install(plan) { }

        val message = result.exceptionOrNull()?.message ?: error("expected a failure")
        assertTrue(message, message.contains("1 package(s) could not be unpacked"))
        // One batch session, then four attempts at the single bad package, and
        // nothing from the second batch was ever touched.
        val sessions = commands.count { it.contains("for f in") }
        assertEquals(1 + 4, sessions)
        val handed = commands.flatMap { c -> Regex("'([^']*\\.deb)'").findAll(c).map { it.groupValues[1] } }
        assertTrue(handed.none { it.contains("p${LinuxToolchain.BATCH_SIZE + 1}_") })
        assertFalse(File(rootfs, LinuxToolchain.STAMP_PATH).exists())
    }

    @Test
    fun `the unpack script skips a package whose marker is already in the guest`() {
        val script = LinuxToolchain.batchScript(listOf("/var/cache/vorv-debs/a_1.0.deb"))
        assertTrue(script.contains(LinuxToolchain.UNPACKED_DIR))
        assertTrue(script.contains("-f \"\$d/\$n\""))
    }

    @Test
    fun `an archive that is not there is named by the guest, not blamed on dpkg`() {
        // 0.9.13 on the owner's phone: 20 archives that had just downloaded came
        // back from dpkg-deb as "No such file or directory", which reads as a
        // corrupt package. The script now says GONE, with the path, first.
        val script = LinuxToolchain.batchScript(listOf("/var/cache/vorv-debs/a_1.0.deb"))
        val gone = script.indexOf("@@GONE")
        val unpack = script.indexOf("dpkg-deb -x")
        assertTrue("the script must test for the archive", gone > 0)
        assertTrue("the GONE test must come before dpkg-deb", gone < unpack)
        assertTrue(script.contains("[ ! -f \"\$f\" ]"))
    }

    @Test
    fun `a downloaded package that vanished is fetched again before its batch`() = runTest {
        // Nothing on disk, so every path in the plan must be fetched again.
        val rootfs = File(work, "rootfs")
        val pkg = LinuxToolchain.Package(
            name = "a",
            version = "1.0",
            url = "https://invalid.test/a_1.0.deb",
            sha256 = "00",
            size = 1,
        )
        val result = LinuxToolchain(rootfs, runInGuest = guest(rootfs)).install(listOf(pkg)) { }
        // The fetch is attempted before any guest command runs, and its failure
        // names the package rather than reaching dpkg-deb with a dead path.
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue(message, message.contains("a"))
        assertTrue("no unpack must be attempted: $commands", commands.none { it.contains("dpkg-deb") })
    }

    @Test
    fun `an empty plan is refused before anything runs`() = runTest {
        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(rootfs, runInGuest = guest(rootfs)).install(emptyList()) { }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no package list"))
        assertTrue(commands.isEmpty())
    }
}
