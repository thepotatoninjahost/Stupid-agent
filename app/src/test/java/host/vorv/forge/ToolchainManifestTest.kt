package host.vorv.forge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolchainManifestTest {

    private val sha = "a".repeat(64)

    @Test
    fun `parses entries and ignores comments and blank lines`() {
        val parsed = ToolchainManifest.parse(
            """
            # pinned payload
            lib/d8.jar $sha 1024

            lib/kotlin-stdlib-2.4.21.jar ${"b".repeat(64)} 2048

            """.trimIndent(),
        )
        val ok = parsed as ToolchainManifest.Parsed.Ok
        assertEquals(2, ok.entries.size)
        assertEquals("lib/d8.jar", ok.entries[0].name)
        assertEquals(1024L, ok.entries[0].size)
    }

    @Test
    fun `rejects a payload name that escapes the toolchain root`() {
        for (name in listOf("../evil.jar", "/etc/passwd", "lib/../../x.jar", "lib\\evil.jar")) {
            val parsed = ToolchainManifest.parse("$name $sha 10")
            assertTrue("expected rejection for $name", parsed is ToolchainManifest.Parsed.Invalid)
        }
    }

    @Test
    fun `rejects duplicates, bad hashes and bad sizes`() {
        assertTrue(ToolchainManifest.parse("a.jar $sha 1\na.jar $sha 1") is ToolchainManifest.Parsed.Invalid)
        assertTrue(ToolchainManifest.parse("a.jar nothex 1") is ToolchainManifest.Parsed.Invalid)
        assertTrue(ToolchainManifest.parse("a.jar $sha 0") is ToolchainManifest.Parsed.Invalid)
        assertTrue(ToolchainManifest.parse("a.jar $sha") is ToolchainManifest.Parsed.Invalid)
    }

    @Test
    fun `empty manifest is invalid rather than vacuously verified`() {
        assertTrue(ToolchainManifest.parse("# nothing here\n") is ToolchainManifest.Parsed.Invalid)
    }

    @Test
    fun `verify reports missing, wrong size and wrong hash`() {
        val root = File(System.getProperty("java.io.tmpdir"), "forge-manifest-test-${System.nanoTime()}")
        root.mkdirs()
        File(root, "good.jar").writeText("abc")

        File(root, "good.jar.bad").writeText("x")
        val entries = listOf(
            ToolchainManifest.Entry("good.jar", sha256Of(File(root, "good.jar")), 3),
            ToolchainManifest.Entry("missing.jar", sha, 1),
            ToolchainManifest.Entry("good.jar.bad", sha, 99),
        )
        val problems = ToolchainManifest.verify(entries, root) { sha256Of(it) }
        assertEquals(problems.toString(), 2, problems.size)
        assertTrue(problems.any { it.startsWith("missing: missing.jar") })
        assertTrue(problems.any { it.startsWith("size mismatch: good.jar.bad") })
        assertFalse(problems.any { it.contains("good.jar (") })
    }

    private fun sha256Of(f: File): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
}
