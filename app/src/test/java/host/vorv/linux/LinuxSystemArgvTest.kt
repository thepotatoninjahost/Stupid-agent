package host.vorv.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The proot command line, pinned without a device.
 *
 * proot is what makes "its own computer" true; if an argument is wrong the
 * guest either refuses to start or silently runs outside the root filesystem.
 * These tests hold the exact instruction sequence, the bind syntax for guest
 * paths, and the rule that the command is ONE argv element (never re-parsed
 * by a shell in the middle).
 */
class LinuxSystemArgvTest {

    private val proot = File("/data/app/lib/arm64/libforge-proot.so")
    private val rootfs = File("/data/user/0/host.vorv/files/linux/rootfs")
    private val home = File("/data/user/0/host.vorv/files/linux/home")
    private val tmp = File("/data/user/0/host.vorv/files/linux/tmp")

    private fun argv(
        binds: List<Pair<String, String>> = emptyList(),
        workDir: String = "/root",
        command: String = "uname -a",
    ) = LinuxSystem.prootArgv(proot, rootfs, home, tmp, binds, workDir, command)

    @Test
    fun `proot opens the rootfs unprivileged with the fixed binds`() {
        val a = argv()
        assertEquals(proot.absolutePath, a[0])
        // -0 fakes root inside the guest without real privileges.
        assertEquals("-0", a[1])
        // Android storage refused hard links and proot said "Permission denied"
        // for libgl1-mesa-dri; --link2symlink turns a link into a symlink, which
        // is the Termux fix for exactly that.
        assertEquals("--link2symlink", a[2])
        // #9 (code review, 2026-11-20): proot must take its tracees with it,
        // otherwise a cancelled compile keeps writing into the rootfs.
        assertEquals("--kill-on-exit", a[3])
        assertEquals(listOf("-r", rootfs.absolutePath), a.subList(4, 6))
        for (sys in listOf("/dev", "/proc", "/sys")) {
            val i = a.indexOf(sys)
            assertTrue("$sys must be bound", i > 0 && a[i - 1] == "-b")
        }
        assertTrue(a.contains("$home:/root"))
        assertTrue(a.contains("$tmp:/tmp"))
    }

    @Test
    fun `a package full of hard links must be able to unpack`() {
        // libgl1-mesa-dri ships twelve drivers hard-linked to armada-drm_dri.so.
        // Without --link2symlink every one of them fails with "Permission denied"
        // and the whole package is reported as un-unpackable.
        assertTrue(argv().contains("--link2symlink"))
    }

    @Test
    fun `the command is one argv element after the workdir`() {
        val sneaky = "echo hi; rm -rf /workspace"
        val a = argv(command = sneaky)
        val tail = a.takeLast(3)
        assertEquals(listOf("/bin/sh", "-lc", sneaky), tail)
        val w = a.indexOf("-w")
        assertEquals("/root", a[w + 1])
    }

    @Test
    fun `a bound host folder appears at its guest path`() {
        val a = argv(binds = listOf("/storage/emulated/0/Vorv" to "/workspace"), workDir = "/workspace")
        assertTrue(a.contains("/storage/emulated/0/Vorv:/workspace"))
        assertEquals("/workspace", a[a.indexOf("-w") + 1])
    }

    @Test
    fun `a folder name with shell metacharacters stays one inert argv element`() {
        val raw = "/storage/emulated/0/x'\$(touch /data/pwned)'"
        val a = argv(binds = listOf(raw to "/workspace"))
        assertTrue(a.contains("$raw:/workspace"))
        // Nothing concatenated a command string: the bind is exactly one element.
        assertFalse(a.any { it.contains("; touch") })
    }

    @Test
    fun `environment names the loader and resolves proot's own libraries`() {
        val native = File("/data/app/~~x==/host.vorv-y==/lib/arm64")
        val loader = File(native, "libforge-proot-loader.so")
        val env = LinuxSystem.guestEnv(loader, tmp, native)
        // The exact path is passed through: proot execs it, and only
        // nativeLibraryDir is executable on Android 10+.
        assertEquals(loader.absolutePath, env["PROOT_LOADER"])
        assertEquals(tmp.absolutePath, env["PROOT_TMP_DIR"])
        // proot's compiled-in runpath is a Termux path that does not exist in
        // this app; its dependencies must be looked for where they sit.
        assertEquals(native.absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals("/root", env["HOME"])
        assertTrue(env.getValue("PATH").contains("/usr/bin"))
    }

    @Test
    fun `the loader is taken from nativeLibraryDir and never from app storage`() {
        val native = Files.createTempDirectory("loader-native").toFile()
        val appStorage = Files.createTempDirectory("loader-app-storage").toFile()
        val installed = File(native, "libforge-proot-loader.so")
            .apply { writeText("payload"); setExecutable(true) }
        // A copy in app storage must not win, even when it exists and is +x:
        // Android's exec policy, not the mode bits, is what refuses it.
        File(appStorage, "loader").apply { writeText("payload"); setExecutable(true) }

        val candidates = LinuxSystem.loaderCandidates(native, appStorage)
        assertEquals(installed.canonicalPath, candidates.first().canonicalPath)
        assertEquals(
            installed.canonicalPath,
            candidates.first { it.isFile && it.canExecute() }.canonicalPath,
        )
    }

    @Test
    fun `a copy already in app storage is only a last resort`() {
        val native = Files.createTempDirectory("loader-native-empty").toFile()
        val appStorage = Files.createTempDirectory("loader-legacy").toFile()
        val candidates = LinuxSystem.loaderCandidates(native, appStorage)
        assertFalse(candidates.any { it.isFile })
        assertEquals(File(appStorage, "loader").canonicalPath, candidates.last().canonicalPath)
    }
}
