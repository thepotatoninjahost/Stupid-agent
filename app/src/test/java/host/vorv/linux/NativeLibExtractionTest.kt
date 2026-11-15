package host.vorv.linux

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest's binaries are executed from `nativeLibraryDir`, which only exists
 * if the installer extracted them. Without `extractNativeLibs="true"` Android
 * maps the libraries straight out of the APK, that directory is empty, and the
 * Setup card reports "NOT PACKAGED — this build has no Linux system" on a
 * perfectly good APK. That is what shipped in 0.8.0 and is why this test
 * exists.
 */
class NativeLibExtractionTest {

    private fun manifest(): String {
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `application asks the installer to extract native libraries`() {
        assertTrue(
            "add android:extractNativeLibs=\"true\" to <application>: the Linux system execs " +
                "proot from nativeLibraryDir, which stays empty when libraries are left inside the APK",
            manifest().contains("android:extractNativeLibs=\"true\""),
        )
    }
}
