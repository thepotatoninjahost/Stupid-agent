package host.vorv.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The guest tree check that turned proot's bare `execve("/usr/bin/sh"): No such
 * file or directory` into a named cause.
 */
class GuestTreeTest {

    private val rootfs: File = Files.createTempDirectory("vorv-guest-tree").toFile()

    private fun file(path: String): File =
        File(rootfs, path).apply {
            parentFile?.mkdirs()
            writeText("x")
        }

    private fun wholeTree() {
        file("etc/debian_version")
        file("usr/bin/dash")
        file("usr/bin/tar")
        file("usr/bin/dpkg-deb")
        file("usr/bin/apt-get")
        file("usr/lib/aarch64-linux-gnu/libc.so.6")
        file("usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        Files.createSymbolicLink(File(rootfs, "lib").toPath(), java.nio.file.Paths.get("usr/lib"))
        Files.createSymbolicLink(
            File(rootfs, "lib/ld-linux-aarch64.so.1").toPath(),
            java.nio.file.Paths.get("aarch64-linux-gnu/ld-linux-aarch64.so.1"),
        )
        Files.createDirectories(File(rootfs, "usr/bin").toPath())
        Files.createSymbolicLink(
            File(rootfs, "usr/bin/sh").toPath(),
            java.nio.file.Paths.get("dash"),
        )
        Files.createSymbolicLink(File(rootfs, "bin").toPath(), java.nio.file.Paths.get("usr/bin"))
    }

    @Test
    fun `a whole Debian tree passes`() {
        wholeTree()
        assertEquals(emptyList<String>(), GuestTree.broken(rootfs))
    }

    @Test
    fun `a missing program is named`() {
        wholeTree()
        File(rootfs, "usr/bin/dpkg-deb").delete()
        val broken = GuestTree.broken(rootfs)
        assertEquals(1, broken.size)
        assertTrue(broken[0], broken[0].startsWith("usr/bin/dpkg-deb — missing"))
    }

    @Test
    fun `a dangling shell symlink is named, not passed as fine`() {
        wholeTree()
        File(rootfs, "usr/bin/dash").delete()
        val broken = GuestTree.broken(rootfs)
        assertTrue("bin/sh must be reported", broken.any { it.startsWith("bin/sh — missing") })
        assertTrue("usr/bin/dash must be reported", broken.any { it.startsWith("usr/bin/dash — missing") })
    }

    @Test
    fun `a symlink pointing outside the tree is refused`() {
        wholeTree()
        File(rootfs, "usr/lib/aarch64-linux-gnu/libc.so.6").delete()
        Files.createSymbolicLink(
            File(rootfs, "usr/lib/aarch64-linux-gnu/libc.so.6").toPath(),
            java.nio.file.Paths.get("../../../../../../etc/passwd"),
        )
        val broken = GuestTree.broken(rootfs)
        assertTrue(
            broken.toString(),
            broken.any { it.startsWith("usr/lib/aarch64-linux-gnu/libc.so.6 — ") },
        )
    }

    @Test
    fun `the message tells the owner the system was refused, not half-started`() {
        val words = GuestTree.describe(listOf("bin/sh — missing"))
        assertTrue(words.contains("refused"))
        assertTrue(words.contains("bin/sh — missing"))
    }
}
