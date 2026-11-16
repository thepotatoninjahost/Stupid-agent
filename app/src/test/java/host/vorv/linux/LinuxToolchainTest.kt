package host.vorv.linux

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The toolchain is 203 MB from the internet, unpacked into the guest. These tests
 * exercise the two things that can silently ruin that: believing a wrong archive,
 * and writing outside the root.
 */
class LinuxToolchainTest {

    private val server = MockWebServer()
    private val work: File = Files.createTempDirectory("vorv-toolchain").toFile()

    @After fun tearDown() {
        server.shutdown()
        work.deleteRecursively()
    }

    private fun deb(files: Map<String, Pair<String, Int>>): ByteArray {
        val data = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(data)).use { tar ->
            files.forEach { (path, spec) ->
                val bytes = spec.first.toByteArray()
                val entry = TarArchiveEntry(path).apply {
                    size = bytes.size.toLong()
                    mode = spec.second
                }
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        val out = ByteArrayOutputStream()
        ArArchiveOutputStream(out).use { ar ->
            val payload = data.toByteArray()
            val entry = org.apache.commons.compress.archivers.ar.ArArchiveEntry("data.tar.gz", payload.size.toLong())
            ar.putArchiveEntry(entry)
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

    @Test
    fun `a package whose bytes do not match its sha256 is refused and nothing lands`() {
        val bytes = deb(mapOf("usr/bin/javac" to ("#!/bin/sh\n" to 0b111_101_101)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(rootfs, File(work, "cache")).install(
            listOf(pkg("openjdk", bytes, shaOverride = "0".repeat(64))),
        ) { }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.startsWith("REFUSED"))
        assertFalse(File(rootfs, "usr/bin/javac").exists())
    }

    @Test
    fun `a verified package is unpacked with its exec bit and only stamps when javac and aapt exist`() {
        val bytes = deb(
            mapOf(
                "usr/bin/javac" to ("#!/bin/sh\n" to 0b111_101_101),
                "usr/bin/aapt" to ("#!/bin/sh\n" to 0b111_101_101),
                "usr/lib/jvm/java-17-openjdk-arm64/lib/modules" to ("mods" to 0b110_100_100),
            ),
        )
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val rootfs = File(work, "rootfs")
        val toolchain = LinuxToolchain(rootfs, File(work, "cache"))
        val result = toolchain.install(listOf(pkg("jdk", bytes))) { }
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertTrue(File(rootfs, "usr/bin/javac").canExecute())
        assertTrue(File(rootfs, "usr/lib/jvm/java-17-openjdk-arm64/lib/modules").isFile)
        assertFalse(File(rootfs, "usr/lib/jvm/java-17-openjdk-arm64/lib/modules").canExecute())
        assertTrue(toolchain.isInstalled())
        assertTrue(File(rootfs, LinuxToolchain.STAMP_PATH).isFile)
    }

    @Test
    fun `a payload that tries to escape the guest is refused`() {
        val bytes = deb(mapOf("../escaped.txt" to ("x" to 0b110_100_100)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(rootfs, File(work, "cache")).install(listOf(pkg("evil", bytes))) { }
        assertTrue(result.isFailure)
        assertFalse(File(work, "escaped.txt").exists())
    }

    @Test
    fun `installing without javac or aapt is reported, not stamped`() {
        val bytes = deb(mapOf("usr/share/doc/readme" to ("x" to 0b110_100_100)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val rootfs = File(work, "rootfs")
        val result = LinuxToolchain(rootfs, File(work, "cache")).install(listOf(pkg("docs", bytes))) { }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("still missing"))
        assertFalse(File(rootfs, LinuxToolchain.STAMP_PATH).exists())
        assertEquals(1, server.requestCount)
    }
}
