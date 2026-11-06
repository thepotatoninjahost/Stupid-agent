package host.vorv.tools

import android.net.Uri

/**
 * THE single SAF (Storage Access Framework) tree-URI → device-path mapper
 * for primary storage.
 *
 * M-1 (code review, 2026-11): ShellTool, TermuxProbe and HealingAgent each
 * carried a private `rawWorkspacePath()` copy of this mapping. The v0.6.18
 * root-grant fix landed in the first two but NOT in the healer's stale
 * third copy — so the self-repair path kept using
 * `docId.substringBefore(":", "")`, returned volume "" for a colon-less
 * docId (a ROOT grant of primary storage, docId "primary"), and REFUSED
 * the very workspace the Setup checklist called green. Three duplicated
 * parsers, one still buggy, exactly the failure mode duplication predicts.
 * One shared mapper means this can never drift again; the fix also UPGRADES
 * self-repair, which now accepts root-granted workspaces like every other
 * shell path.
 */
object SafPaths {

    /**
     * Maps a primary-storage SAF tree URI to its /storage/emulated/0/...
     * path, or null when the URI is anything else (other authority, not a
     * tree, or a non-primary volume such as the SD card).
     *
     * Deliberate asymmetry in the missing-delimiter case: a docId WITHOUT a
     * colon ("primary" = the storage root) means the volume IS the whole
     * docId and the subfolder is empty. Passing "" to BOTH parts (the
     * pre-v0.6.18 form) made a root grant's volume "" and wrongly rejected
     * every root-of-primary-storage workspace — latent since v0.6.14, in
     * BOTH original copies, caught by TermuxSetupTest's SAF-parsing cases
     * and fixed in lockstep back then; the stale healer copy is the reason
     * this function now exists in exactly one place.
     */
    fun rawWorkspacePath(uriStr: String): String? {
        val uri = Uri.parse(uriStr)
        if (uri.authority != "com.android.externalstorage.documents") return null
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != "tree") return null
        val docId = Uri.decode(segments[1])
        val volume = docId.substringBefore(":")
        val rest = docId.substringAfter(":", "")
        if (volume != "primary") return null
        return if (rest.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$rest"
    }
}
