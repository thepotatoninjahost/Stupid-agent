package host.vorv.forge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `NativeTestTool` canonicalises the APK path and refuses anything that is not
 * under the project root. The builder used to write the signed APK to
 * `<project>-build/`, beside the project, so every successful build was
 * untestable. This pins the path contract, not the build itself.
 *
 * It also pins the clearing rule: a build deletes the APK the previous build
 * delivered before it starts, so a failed build can never be "tested" by
 * installing the old one.
 */
class DeliverableApkTest {

    private fun tempProject(): File =
        Files.createTempDirectory("vorv-project").toFile().apply { deleteOnExit() }

    @Test
    fun `the deliverable lands inside the project folder`() {
        val project = File("/data/user/0/host.vorv/files/forge/projects/calc")
        val apk = deliverableApk(project, "app")
        assertEquals(File(project, "app.apk"), apk)
        assertTrue(
            "native_test only accepts an APK under the project root",
            apk.canonicalPath.startsWith(project.canonicalPath + File.separator),
        )
    }

    @Test
    fun `the previous apk is deleted before the build`() {
        val project = tempProject()
        val previous = File(project, "app.apk").apply { writeText("the old app") }
        assertTrue("fixture must exist", previous.isFile)

        assertNull("clearing a real file must succeed", clearPreviousApk(previous))
        assertFalse("a failed build must not find this APK to test", previous.exists())
    }

    @Test
    fun `nothing to delete is not a failure`() {
        val project = tempProject()
        assertNull(clearPreviousApk(deliverableApk(project, "app")))
    }

    @Test
    fun `only the deliverable is deleted`() {
        val project = tempProject()
        val previous = File(project, "app.apk").apply { writeText("the old app") }
        val source = File(project, "Main.java").apply { writeText("class Main {}") }
        val notes = File(project, "notes.txt").apply { writeText("keep me") }

        assertNull(clearPreviousApk(previous))
        assertFalse(previous.exists())
        assertTrue("source must survive a build", source.isFile)
        assertTrue("anything that is not the deliverable must survive", notes.isFile)
    }
}
