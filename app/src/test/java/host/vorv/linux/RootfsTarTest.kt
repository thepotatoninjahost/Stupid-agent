package host.vorv.linux

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * The root filesystem is a tar of an operating system, unpacked on the user's
 * phone. Getting a symlink or an exec bit wrong leaves a system that looks
 * present and does nothing, so those two are checked, along with the rule that
 * no entry may escape the destination.
 */
class RootfsTarTest {

    private fun tarGz(build: TarArchiveOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        GzipCompressorOutputStream(bytes).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.build()
            }
        }
        return bytes.toByteArray()
    }

    private fun TarArchiveOutputStream.file(name: String, body: String, mode: Int = 0b110_100_100) {
        val data = body.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { setSize(data.size.toLong()); setMode(mode) })
        write(data)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.dir(name: String) {
        putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_DIR).apply { setMode(0b111_101_101) })
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.symlink(name: String, target: String) {
        putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target })
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.hardLink(name: String, target: String) {
        putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_LINK).apply { linkName = target })
        closeArchiveEntry()
    }

    @Test
    fun `unpacks files, keeps exec bits and recreates symlinks`() {
        val dest = Files.createTempDirectory("rootfs").toFile()
        val tar = tarGz {
            dir("./")
            dir("./etc/")
            file("./etc/alpine-release", "3.20.0\n")
            dir("./bin/")
            file("./bin/busybox", "#!/bin/sh\n", mode = 0b111_101_101)
            symlink("./bin/sh", "/bin/busybox")
        }
        tar.inputStream().use { RootfsTar.extract(it, dest) }

        assertEquals("3.20.0\n", File(dest, "etc/alpine-release").readText())
        assertTrue("executable bit lost", File(dest, "bin/busybox").canExecute())
        val sh = File(dest, "bin/sh").toPath()
        assertTrue("symlink not preserved", Files.isSymbolicLink(sh))
        assertEquals("/bin/busybox", Files.readSymbolicLink(sh).toString())
        dest.deleteRecursively()
    }

    @Test
    fun `reads a plain uncompressed tar, which is what the asset in the apk is`() {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.file("./etc/os-release", "ID=alpine\n")
        }
        val dest = Files.createTempDirectory("rootfs").toFile()
        bytes.toByteArray().inputStream().use { RootfsTar.extract(it, dest) }
        assertEquals("ID=alpine\n", File(dest, "etc/os-release").readText())
        dest.deleteRecursively()
    }

    @Test
    fun `recreates hard links instead of dropping them`() {
        // R-2: the Debian rootfs we ship carries exactly this shape
        // (usr/bin/perl5.36.0 -> usr/bin/perl). Dropping the link entry left the
        // path missing in the guest, so Debian tooling that expects it failed
        // with "not found" long after provisioning looked successful.
        val dest = Files.createTempDirectory("rootfs").toFile()
        val tar = tarGz {
            dir("./")
            dir("./usr/bin/")
            file("./usr/bin/perl", "#!/usr/bin/perl\nprint 1;\n", mode = 0b111_101_101)
            hardLink("./usr/bin/perl5.36.0", "./usr/bin/perl")
        }
        tar.inputStream().use { RootfsTar.extract(it, dest) }

        val linked = File(dest, "usr/bin/perl5.36.0")
        assertTrue("the hard link was dropped, so the path is missing", linked.isFile)
        assertEquals(File(dest, "usr/bin/perl").readText(), linked.readText())
        assertTrue("the linked copy is not executable", linked.canExecute())
        dest.deleteRecursively()
    }

    @Test
    fun `a hard link with no earlier target fails loudly`() {
        val dest = Files.createTempDirectory("rootfs").toFile()
        val tar = tarGz { hardLink("./usr/bin/uncompress", "./usr/bin/gunzip") }
        try {
            tar.inputStream().use { RootfsTar.extract(it, dest) }
            fail("a hard link pointing at something that was never extracted was accepted")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("uncompress"))
        }
        dest.deleteRecursively()
    }

    @Test
    fun `repair puts back the Debian hard links an older build dropped`() {
        // An install provisioned before R-2 keeps its stamp and is never unpacked
        // again, so the repair is the only thing that can fix it in place.
        val dest = Files.createTempDirectory("rootfs").toFile()
        val bin = File(dest, "usr/bin").apply { mkdirs() }
        File(bin, "perl").writeText("#!/usr/bin/perl\n")
        File(bin, "perl").setExecutable(true)
        assertTrue("fixture: perl5.36.0 should start absent", !File(bin, "perl5.36.0").exists())

        RootfsLinks.repair(dest)

        assertTrue("repair did not recreate perl5.36.0", File(bin, "perl5.36.0").isFile)
        assertEquals("#!/usr/bin/perl\n", File(bin, "perl5.36.0").readText())
        // gunzip is absent in this fixture: repair must skip it, not throw.
        assertTrue("repair invented a link with no target", !File(bin, "uncompress").exists())

        // Idempotent, and it never rewrites an existing file.
        File(bin, "perl5.36.0").writeText("local change\n")
        RootfsLinks.repair(dest)
        assertEquals("local change\n", File(bin, "perl5.36.0").readText())
        dest.deleteRecursively()
    }

    @Test
    fun `refuses an entry that would escape the destination`() {
        val parent = Files.createTempDirectory("rootfs").toFile()
        val dest = File(parent, "inner").apply { mkdirs() }
        val tar = tarGz { file("../outside.txt", "nope") }
        try {
            tar.inputStream().use { RootfsTar.extract(it, dest) }
            fail("a tar entry escaping the rootfs was written")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("outside.txt"))
        }
        assertTrue("the escaping file was still created", !File(parent, "outside.txt").exists())
        parent.deleteRecursively()
    }

    /**
     * A tar can escape through a symlinked directory rather than through the
     * name of an entry on its own: point a directory at the outside world, then
     * write a file "inside" it. Entries are unpacked in order, so this is the
     * shape an attacker would use.
     */
    @Test
    fun `refuses to write through a symlink that leads outside`() {
        val outside = Files.createTempDirectory("outside").toFile()
        val dest = Files.createTempDirectory("rootfs").toFile()
        val tar = tarGz {
            symlink("./escape", outside.absolutePath)
            file("./escape/pwned.txt", "boom")
        }
        try {
            tar.inputStream().use { RootfsTar.extract(it, dest) }
            fail("a file was written outside the rootfs through a symlink")
        } catch (expected: SecurityException) {
        }
        assertTrue("the file was still created outside", !File(outside, "pwned.txt").exists())
        dest.deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun `an absolute entry name stays inside the destination`() {
        val dest = Files.createTempDirectory("rootfs").toFile()
        val tar = tarGz { file("/etc/hostname", "vorv\n") }
        tar.inputStream().use { RootfsTar.extract(it, dest) }
        assertEquals("vorv\n", File(dest, "etc/hostname").readText())
        dest.deleteRecursively()
    }
}
