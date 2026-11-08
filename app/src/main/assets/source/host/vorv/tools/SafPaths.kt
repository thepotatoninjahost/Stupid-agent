package host.vorv.tools

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
 *
 * L-6 (code review, 2026-11): the parsing is now PURE STRING LOGIC with
 * ONE percent-decoder — Uri.decode semantics, written once here. Before,
 * [rawWorkspacePath] decoded with android.net.Uri.decode while
 * TermuxProbe.workspaceOnPrimaryStorage decoded the SAME URI family with
 * java.net.URLDecoder — two decoders with drifting semantics ('+' → space
 * vs literal '+', throw-on-malformed vs pass-through) for one URI family
 * is exactly how the three-copies bug happened. Both consumers now share
 * [primaryStorageTreeDocId]; pure string logic also means unit tests pin
 * it without Robolectric.
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
        val docId = primaryStorageTreeDocId(uriStr) ?: return null
        val rest = docId.substringAfter(":", "")
        return if (rest.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$rest"
    }

    /**
     * L-6: THE single primary-storage tree-URI recognizer. Returns the
     * DECODED docId when [uriStr] is a tree grant on the primary volume
     * ("primary" or "primary:…"), null for everything else — other
     * authorities, non-tree URIs, SD-card serials, malformed input.
     * Pure; both [rawWorkspacePath] and the Termux Setup checklist
     * delegate here so the two can never disagree again.
     */
    fun primaryStorageTreeDocId(uriStr: String?): String? {
        if (uriStr.isNullOrBlank()) return null
        val schemeEnd = uriStr.indexOf("://")
        if (schemeEnd < 0) return null
        if (uriStr.substring(0, schemeEnd) != "content") return null
        // Segment boundaries are raw '/' characters (matching how
        // Uri.pathSegments splits a content URI's path).
        var i = schemeEnd + 3
        val authorityEnd = uriStr.indexOf('/', i)
        if (authorityEnd < 0) return null
        if (uriStr.substring(i, authorityEnd) != "com.android.externalstorage.documents") return null
        val segments = uriStr.substring(authorityEnd + 1).split('/')
        if (segments.size < 2 || segments[0] != "tree") return null
        val docId = decodePercent(segments[1])
        if (docId != "primary" && !docId.startsWith("primary:")) return null
        return docId
    }

    /**
     * Percent-decoding with android.net.Uri.decode semantics (L-6): %XX
     * byte sequences are decoded as UTF-8; MALFORMED sequences pass through
     * unchanged (never throw — a hostile URI must not crash the parser);
     * '+' stays a literal plus (NOT a space, unlike java.net.URLDecoder —
     * a folder named "A+B" round-trips correctly through the mapper).
     */
    internal fun decodePercent(s: String): String {
        if (!s.contains('%')) return s
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 3 <= s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                bytes.add(((hexVal(s[i + 1]) shl 4) or hexVal(s[i + 2])).toByte())
                i += 3
            } else {
                // Literal char (including a malformed '%' run): UTF-8 bytes.
                for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
                i++
            }
        }
        // Invalid multi-byte sequences become U+FFFD — same replacement
        // behavior as Uri.decode, never a throw.
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> c - 'A' + 10
    }
}
