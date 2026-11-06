package host.vorv.tools

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-1 regression tests (code review, 2026-11): the SAF tree-URI → path
 * mapping exists in exactly ONE place ([SafPaths]) and every consumer
 * (ShellTool, TermuxProbe, HealingAgent) delegates to it.
 *
 * The mapping's history makes the colon-less case sacred: the pre-v0.6.18
 * copies returned volume "" for a docId without a colon, so a ROOT grant
 * ("tree/primary") was rejected as not-primary by the parser while the
 * Setup checklist showed gate 1 PASS. The stale third copy (healer) kept
 * that bug until this consolidation — these tests make any regression of
 * any copy impossible, because there is only one copy left to break.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // plain Application: VorvApplication eagerly touches the Keystore
class SafPathsTest {

    // --------------------------------------------- root grant (the M-1 case)

    @Test
    fun `root grant of primary storage maps to the storage root`() {
        // docId "primary" — NO colon. This is the exact input the stale
        // third copy got wrong (volume "" → refused the workspace).
        val uri = "content://com.android.externalstorage.documents/tree/primary"
        assertEquals("/storage/emulated/0", SafPaths.rawWorkspacePath(uri))
    }

    @Test
    fun `root grant with empty subfolder part maps to the storage root too`() {
        // Some pickers emit "primary:" (colon, empty rest). volume=primary,
        // rest="" — same result as the bare colon-less docId.
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A"
        assertEquals("/storage/emulated/0", SafPaths.rawWorkspacePath(uri))
    }

    // ---------------------------------------------------------- subtree grants

    @Test
    fun `subtree grant maps to the folder path`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        assertEquals("/storage/emulated/0/Documents", SafPaths.rawWorkspacePath(uri))
    }

    @Test
    fun `raw colon form is equivalent to the encoded form`() {
        // Unencoded colon inside ONE path segment — same docId as the
        // %3A form. (A raw "/" inside a docId would split the path into
        // two segments; real SAF pickers always encode it, and the mapper
        // reads segments[1] exactly like the audited originals did.)
        val uri = "content://com.android.externalstorage.documents/tree/primary:Documents"
        assertEquals("/storage/emulated/0/Documents", SafPaths.rawWorkspacePath(uri))
    }

    @Test
    fun `spaces and unicode in the subfolder survive decoding`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMy%20Vorv%20Workshop"
        assertEquals(
            "/storage/emulated/0/Documents/My Vorv Workshop",
            SafPaths.rawWorkspacePath(uri),
        )
    }

    // ------------------------------------------------------------- rejections

    @Test
    fun `non-primary volume (SD card serial) is rejected`() {
        val uri = "content://com.android.externalstorage.documents/tree/9016-4EF8%3ADCIM"
        assertNull(SafPaths.rawWorkspacePath(uri))
    }

    @Test
    fun `other authority is rejected`() {
        val uri = "content://com.android.providers.downloads.documents/tree/downloads"
        assertNull(SafPaths.rawWorkspacePath(uri))
    }

    @Test
    fun `document URIs are not tree grants and are rejected`() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fnote.txt"
        assertNull(SafPaths.rawWorkspacePath(uri))
    }

    @Test
    fun `truncated tree URI is rejected without crashing`() {
        val uri = "content://com.android.externalstorage.documents/tree"
        assertNull(SafPaths.rawWorkspacePath(uri))
    }

    @Test
    fun `plain string that is not a URI is rejected`() {
        assertNull(SafPaths.rawWorkspacePath("/storage/emulated/0/Documents"))
    }
}
