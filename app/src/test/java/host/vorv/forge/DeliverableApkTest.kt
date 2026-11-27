package host.vorv.forge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `NativeTestTool` canonicalises the APK path and refuses anything that is not
 * under the project root. The builder used to write the signed APK to
 * `<project>-build/`, beside the project, so every successful build was
 * untestable. This pins the path contract, not the build itself.
 */
class DeliverableApkTest {

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
}
