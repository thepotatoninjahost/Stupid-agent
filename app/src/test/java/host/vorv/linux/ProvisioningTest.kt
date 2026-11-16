package host.vorv.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The provisioning order, which 0.9.2 got wrong: the code checked for the stamp
 * before writing it, so a fresh install could never become provisioned. These
 * tests fail against that ordering and pass against this one.
 */
class ProvisioningTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stampValue = "alpine-3.20.0-proot-5.1.107"

    private fun rootfs() = File(tmp.root, "rootfs")
    private fun stamp() = File(tmp.root, "provisioned")

    /** What a real extraction does, minus the 9 MB tar. */
    private fun extraction(): Pair<MutableList<Unit>, () -> Unit> {
        val calls = mutableListOf<Unit>()
        return calls to {
            calls += Unit
            File(rootfs(), "etc").mkdirs()
            File(rootfs(), RootfsProvisioner.MARKER).writeText("3.20.0\n")
        }
    }

    @Test
    fun `a first run provisions, writes the stamp and reports success`() {
        val (calls, extract) = extraction()
        val result = RootfsProvisioner(stampValue).provision(rootfs(), stamp(), extract = extract)

        assertTrue(result.isSuccess)
        assertEquals(1, calls.size)
        assertEquals(stampValue, stamp().readText().trim())
        assertTrue(File(rootfs(), RootfsProvisioner.MARKER).isFile)
    }

    @Test
    fun `a provisioned tree is recognised, so a second run does not extract again`() {
        val (calls, extract) = extraction()
        val provisioner = RootfsProvisioner(stampValue)
        provisioner.provision(rootfs(), stamp(), extract = extract)

        assertTrue(provisioner.isProvisioned(rootfs(), stamp()))
        assertTrue(provisioner.provision(rootfs(), stamp(), extract = extract).isSuccess)
        assertEquals("the second run must not touch the tree", 1, calls.size)
    }

    @Test
    fun `a stamp from an older payload forces a re-extract`() {
        val (calls, extract) = extraction()
        val provisioner = RootfsProvisioner(stampValue)
        provisioner.provision(rootfs(), stamp(), extract = extract)

        val newer = RootfsProvisioner("alpine-3.21.0-proot-5.1.200")
        assertFalse(newer.isProvisioned(rootfs(), stamp()))
        assertTrue(newer.provision(rootfs(), stamp(), extract = extract).isSuccess)
        assertEquals(2, calls.size)
        assertEquals("alpine-3.21.0-proot-5.1.200", stamp().readText().trim())
    }

    @Test
    fun `an unreadable stamp counts as not provisioned instead of throwing`() {
        // A directory where the stamp should be: reading it fails.
        assertTrue(stamp().mkdirs())
        File(rootfs(), "etc").mkdirs()
        File(rootfs(), RootfsProvisioner.MARKER).writeText("3.20.0\n")

        assertFalse(RootfsProvisioner(stampValue).isProvisioned(rootfs(), stamp()))
    }

    @Test
    fun `a tree that unpacked without the marker fails and writes no stamp`() {
        val result = RootfsProvisioner(stampValue)
            .provision(rootfs(), stamp(), extract = { File(rootfs(), "bin").mkdirs() })

        assertTrue(result.isFailure)
        assertEquals(RootfsProvisioner.INCOMPLETE, result.exceptionOrNull()?.message)
        assertFalse("a half-built tree must not be marked provisioned", stamp().exists())
    }

    @Test
    fun `a missing loader fails before the stamp is written`() {
        val (_, extract) = extraction()
        val fault = IllegalStateException("proot's loader cannot run on this install")
        val result = RootfsProvisioner(stampValue).provision(
            rootfs = rootfs(),
            stamp = stamp(),
            loaderOk = { false },
            loaderFailure = { fault },
            extract = extract,
        )

        assertEquals(fault, result.exceptionOrNull())
        assertFalse(stamp().exists())
    }

    @Test
    fun `a throwing extraction leaves no stamp`() {
        val result = RootfsProvisioner(stampValue).provision(
            rootfs = rootfs(),
            stamp = stamp(),
            extract = { throw IllegalStateException("asset gone") },
        )

        assertEquals("asset gone", result.exceptionOrNull()?.message)
        assertFalse(stamp().exists())
    }
}
