package host.vorv.watchdog

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * H-6: the software patch key and the signed patches must not be restorable
 * onto another device. The key moves to noBackupFilesDir, and both backup
 * rule files exclude the patch directories.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PatchKeyBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `backup rules exclude the patch key and the patch artifacts`() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val text = xml(name)
            for (path in listOf("hotpatch", "hotpatch_quarantine", "patchkeys")) {
                assertTrue(
                    "$name must exclude the file domain path \"$path\"",
                    text.contains("""<exclude domain="file" path="$path" />"""),
                )
            }
        }
    }

    @Test
    fun `the legacy key moves out of filesDir and still parses`() {
        val oldDir = File(context.filesDir, "patchkeys").apply { mkdirs() }
        val pair = newKeyPair()
        File(oldDir, "patch.key").writeBytes(pair.first)
        File(oldDir, "patch.pub").writeBytes(pair.second)

        val newDir = File(context.noBackupFilesDir, "patchkeys").apply { mkdirs() }
        PatchKeys.migrateLegacyFallback(context, newDir)

        assertTrue(File(newDir, "patch.key").exists())
        assertTrue(File(newDir, "patch.pub").exists())
        assertFalse("the backed-up copy must be gone", File(oldDir, "patch.key").exists())
        assertFalse("the backed-up copy must be gone", File(oldDir, "patch.pub").exists())

        val kf = KeyFactory.getInstance(algorithm())
        kf.generatePrivate(PKCS8EncodedKeySpec(File(newDir, "patch.key").readBytes()))
        kf.generatePublic(X509EncodedKeySpec(File(newDir, "patch.pub").readBytes()))
    }

    @Test
    fun `an unparseable legacy key is left where it was, not deleted`() {
        val oldDir = File(context.filesDir, "patchkeys").apply { mkdirs() }
        File(oldDir, "patch.key").writeBytes(byteArrayOf(1, 2, 3))
        File(oldDir, "patch.pub").writeBytes(byteArrayOf(4, 5, 6))

        val newDir = File(context.noBackupFilesDir, "patchkeys").apply { mkdirs() }
        PatchKeys.migrateLegacyFallback(context, newDir)

        assertTrue("a key that cannot be parsed is still the only key", File(oldDir, "patch.key").exists())
        assertFalse(File(newDir, "patch.key").exists())
    }

    private fun algorithm() = if (Build.VERSION.SDK_INT >= 33) "Ed25519" else "EC"

    private fun newKeyPair(): Pair<ByteArray, ByteArray> {
        val alg = algorithm()
        val gen = KeyPairGenerator.getInstance(alg)
        if (alg == "EC") gen.initialize(ECGenParameterSpec("secp256r1"))
        val kp = gen.generateKeyPair()
        return kp.private.encoded to kp.public.encoded
    }

    /** Gradle's test cwd is the module directory; accept either layout. */
    private fun xml(name: String): String {
        val candidates = listOf(
            File("src/main/res/xml/$name"),
            File("app/src/main/res/xml/$name"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("no $name under ${candidates.joinToString { it.absolutePath }}")
        return file.readText()
    }
}
