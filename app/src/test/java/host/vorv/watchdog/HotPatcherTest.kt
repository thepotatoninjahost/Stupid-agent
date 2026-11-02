package host.vorv.watchdog

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * HotPatcher gate tests (audits C-2 + the v0.6.8 consent round-trip fix).
 *
 * Pins: the consent registry round-trips through a GENUINE JSONArray (the
 * bug that silently deleted every approved patch at next boot), signing is
 * real (PatchKeys sign→verify over the software backend), and
 * registerApprovedPatch refuses non-dispatchable classes and bad
 * signatures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HotPatcherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `consent registry round-trips a real JSONArray`() {
        HotPatcher.markPendingApply(context, "fix_abc")
        HotPatcher.markPendingApply(context, "fix_def")
        val ids = HotPatcher.readApprovedIds(context)
        assertTrue(ids.contains("fix_abc"))
        assertTrue(ids.contains("fix_def"))

        // The file itself must parse with the "applied" key as an ARRAY —
        // the org.json bug wrote it as a STRING, silently deleting every
        // approved patch on the next boot.
        val patchDir = File(context.filesDir, "hotpatch")
        val approvedFile = File(patchDir, "approved.json")
        assertTrue(approvedFile.exists())
        val parsed = JSONObject(approvedFile.readText())
        val arr = parsed.get("applied")
        assertTrue("applied must serialize as a JSONArray, got: ${arr::class.java}",
            arr is JSONArray)
        assertEquals(2, (arr as JSONArray).length())
    }

    @Test
    fun `patch keys sign and verify over the software backend`() {
        val alg = PatchKeys.ensureKeys(context)
        assertNotNull("a signing backend must be available in tests", alg)
        val data = "hello patch".toByteArray()
        val signature = PatchKeys.sign(context, data)
        assertNotNull(signature)
        assertTrue(PatchKeys.verify(context, data, signature!!))
        // Tampered data must NOT verify.
        assertFalse(PatchKeys.verify(context, "hello patch!".toByteArray(), signature))
        // A garbage signature must not verify and must not throw.
        assertFalse(PatchKeys.verify(context, data, "Ed2559:AAAA"))
        assertFalse(PatchKeys.verify(context, data, "not-a-signature"))
    }

    @Test
    fun `registerApprovedPatch refuses a non-dispatchable class`() {
        val data = "dex-bytes".toByteArray()
        val sha = HotPatcher.sha256Hex(data)
        val sig = PatchKeys.sign(context, data)!!
        val ok = HotPatcher.registerApprovedPatch(
            context = context,
            patchId = "test_bad_class",
            targetClass = "host.vorv.MainActivity", // outside the dispatch surface
            dexBytes = data,
            sha256 = sha,
            signature = sig,
            description = "test",
        )
        assertFalse(ok)
    }

    @Test
    fun `registerApprovedPatch refuses an invalid signature`() {
        val data = "dex-bytes".toByteArray()
        val sha = HotPatcher.sha256Hex(data)
        val ok = HotPatcher.registerApprovedPatch(
            context = context,
            patchId = "test_bad_sig",
            targetClass = "host.vorv.tools.NotifyTool",
            dexBytes = data,
            sha256 = sha,
            signature = "Ed25519:" + android.util.Base64.encodeToString(
                ByteArray(64), android.util.Base64.NO_WRAP),
            description = "test",
        )
        assertFalse("a forged signature must be refused", ok)
    }

    @Test
    fun `a fully valid patch registers with a signed manifest entry`() {
        val data = "real-dex-bytes".toByteArray()
        val sha = HotPatcher.sha256Hex(data)
        val sig = PatchKeys.sign(context, data)!!
        val ok = HotPatcher.registerApprovedPatch(
            context = context,
            patchId = "test_ok",
            targetClass = "host.vorv.tools.NotifyTool",
            dexBytes = data,
            sha256 = sha,
            signature = sig,
            description = "test patch",
        )
        assertTrue(ok)

        // The manifest entry carries the signature and version gates.
        val manifest = JSONObject(
            File(context.filesDir, "hotpatch/manifest.json").readText())
        val patches = manifest.getJSONArray("patches")
        var entry: JSONObject? = null
        for (i in 0 until patches.length()) {
            val e = patches.getJSONObject(i)
            if (e.getString("id") == "test_ok") entry = e
        }
        assertNotNull(entry)
        assertEquals(sha, entry!!.getString("sha256"))
        assertEquals(sig, entry.getString("signature"))
        assertTrue(entry.getInt("minAppVersion") > 0)

        // And the consent registry contains the id.
        assertTrue(HotPatcher.readApprovedIds(context).contains("test_ok"))
    }
}
