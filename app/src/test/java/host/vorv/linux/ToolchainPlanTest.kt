package host.vorv.linux

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.9.4 shipped to the phone without this file because the whole
 * assets/forge/linux folder was git-ignored, so the installer said
 * "this build carries no toolchain plan". The plan is source: pin it here.
 */
class ToolchainPlanTest {

    private val plan = File("src/main/assets/forge/linux/toolchain.json")

    @Test
    fun `the toolchain plan is committed, parses, and pins every package`() {
        assertTrue("toolchain.json is missing from the source tree", plan.isFile)
        val root = JsonParser.parseString(plan.readText()).asJsonObject
        val packages = root.getAsJsonArray("packages")
        assertTrue("expected the full Debian closure, found ${packages.size()}", packages.size() > 100)
        val names = packages.map { it.asJsonObject["name"].asString }
        listOf("openjdk-17-jdk-headless", "aapt", "zipalign", "apksigner").forEach {
            assertTrue("$it is not in the plan", names.contains(it))
        }
        packages.forEach { p ->
            val o = p.asJsonObject
            assertEquals(
                "${o["name"].asString} has no usable sha256",
                64,
                o["sha256"].asString.length,
            )
            assertTrue(o["url"].asString.startsWith("http"))
            assertTrue(o["size"].asLong > 0)
        }
    }
}
