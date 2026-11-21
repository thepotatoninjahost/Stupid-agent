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
            approval = "EC:" + android.util.Base64.encodeToString(
                ByteArray(70), android.util.Base64.NO_WRAP),
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
            approval = "EC:" + android.util.Base64.encodeToString(
                ByteArray(70), android.util.Base64.NO_WRAP),
        )
        assertFalse("a forged signature must be refused", ok)
    }

    @Test
    fun `a patch with a valid dex signature but no device-signed approval is refused`() {
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
            approval = "",
        )
        assertFalse("approval is required — an in-app yes is not consent", ok)
        assertFalse(File(context.filesDir, "hotpatch/test_ok.dex").exists())
        assertEquals(0, HotPatcher.readApprovedIds(context).size)
    }

    @Test
    fun `a forged approval token is refused`() {
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
            approval = "EC:" + android.util.Base64.encodeToString(
                ByteArray(70), android.util.Base64.NO_WRAP),
        )
        assertFalse("no device can have signed this", ok)
    }

    /**
     * The write half of registration, pinned separately: this machine has no
     * device-bound consent key, so the public entry point always refuses
     * here. The manifest/consent-registry round trip (the org.json bug that
     * once deleted every approved patch) is still covered, because the boot
     * path re-verifies the token and a bad one cannot install anything.
     */
    @Test
    fun `the write half records the manifest entry and the consent id`() {
        val data = "real-dex-bytes".toByteArray()
        val sha = HotPatcher.sha256Hex(data)
        val sig = PatchKeys.sign(context, data)!!
        val ok = HotPatcher.writeRegistration(
            context = context,
            patchId = "test_ok",
            targetClass = "host.vorv.tools.NotifyTool",
            dexBytes = data,
            sha256 = sha,
            signature = sig,
            description = "test patch",
            approval = "EC:" + android.util.Base64.encodeToString(
                ByteArray(70), android.util.Base64.NO_WRAP),
        )
        assertTrue(ok)

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
        assertTrue(entry.has("approval"))

        // And the consent registry contains the id.
        assertTrue(HotPatcher.readApprovedIds(context).contains("test_ok"))
    }

    /**
     * Boot path: a dex that is listed, approved by id, and hash-correct is
     * still refused when the manifest carries no device-signed approval for
     * it. This is the gate that makes "the user approved this" impossible to
     * fake by editing the app's own files.
     */
    @Test
    fun `boot refuses a patch whose approval token is missing`() {
        val data = "dex-bytes".toByteArray()
        val sha = HotPatcher.sha256Hex(data)
        val patchDir = File(context.filesDir, "hotpatch").apply { mkdirs() }
        // A manifest entry and a consent record, both written as if by the app,
        // and no approval token — exactly what a forged consent would look like.
        File(patchDir, "forged_1.dex").writeBytes(data)
        File(patchDir, "manifest.json").writeText(
            JSONObject().put("version", 2).put("patches", JSONArray().put(
                JSONObject().apply {
                    put("id", "forged_1")
                    put("sha256", sha)
                    put("signature", "EC:AAAA")
                    put("classes", JSONArray().put("host.vorv.tools.NotifyTool"))
                },
            )).toString())
        HotPatcher.markPendingApply(context, "forged_1")

        val applied = HotPatcher.loadPendingPatches(context)

        assertTrue("nothing may be applied", applied.isEmpty())
        assertFalse(File(patchDir, "forged_1.dex").exists())
        val quarantined = File(context.filesDir, "hotpatch_quarantine")
            .listFiles()?.any { it.name.endsWith("forged_1.dex") } == true
        assertTrue("the refused patch is quarantined, not silently skipped", quarantined)
    }
}
