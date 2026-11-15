package host.vorv.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        assertEquals(listOf("-r", rootfs.absolutePath), a.subList(2, 4))
        for (sys in listOf("/dev", "/proc", "/sys")) {
            val i = a.indexOf(sys)
            assertTrue("$sys must be bound", i > 0 && a[i - 1] == "-b")
        }
        assertTrue(a.contains("$home:/root"))
        assertTrue(a.contains("$tmp:/tmp"))
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
    fun `environment names the loader and keeps a plain PATH`() {
        val env = LinuxSystem.guestEnv(File("/data/user/0/host.vorv/files/linux/loader"), tmp)
        assertEquals("/data/user/0/host.vorv/files/linux/loader/loader", env["PROOT_LOADER"])
        assertEquals(tmp.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals("/root", env["HOME"])
        assertTrue(env.getValue("PATH").contains("/usr/bin"))
    }
}
