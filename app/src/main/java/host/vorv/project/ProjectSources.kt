package host.vorv.project

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * The workspace as the indexer sees it, read through the storage permission the
 * owner already granted (a SAF tree URI). The doc comment in ChunkStore promised
 * this file; until now it did not exist and the whole project package was
 * unreachable from the running app.
 *
 * Hard limits, all of them reported rather than swallowed: the walk stops at
 * [MAX_DIRS], never follows a symlink loop (DocumentFile does not expose them),
 * and skips anything the ignore rules name. Files that cannot be read are
 * returned as null from [read] and the indexer records the skip with its reason.
 */
class DocTreeFileSource(
    private val context: Context,
    treeUri: Uri,
    private val maxFiles: Int = 20_000,
    private val maxDirs: Int = 4_000,
) : FileSource {

    private val root: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    /** Why the walk could not even start, for the screen and the prompt. */
    val problem: String? =
        if (root == null) "The project folder could not be opened — pick it again in Settings."
        else if (!root.isDirectory) "The saved workspace is a file, not a folder."
        else if (!root.canRead()) "The storage permission for this folder was lost — grant it again."
        else null

    override fun list(): List<FileMeta> {
        val top = root ?: return emptyList()
        val out = ArrayList<FileMeta>()
        var dirs = 0
        val queue = ArrayDeque<Pair<DocumentFile, String>>()
        queue.addLast(top to "")
        while (queue.isNotEmpty() && out.size < maxFiles && dirs < maxDirs) {
            val (dir, prefix) = queue.removeFirst()
            val children = dir.listFiles()
            for (child in children) {
                val name = child.name ?: continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    dirs++
                    queue.addLast(child to path)
                } else {
                    out += FileMeta(path, child.length(), child.lastModified())
                }
                if (out.size >= maxFiles) break
            }
        }
        return out.sortedBy { it.path }
    }

    override fun read(meta: FileMeta): String? {
        val file = find(meta.path) ?: return null
        if (!file.isFile || file.length() > ProjectIndexer.MAX_FILE_BYTES) return null
        return openLimited(file.uri, ProjectIndexer.MAX_FILE_BYTES.toInt())
    }

    /** The change fingerprint needs the first bytes only, not the whole file. */
    override fun readHead(meta: FileMeta, bytes: Int): ByteArray? {
        val file = find(meta.path) ?: return null
        if (!file.isFile) return null
        return openBytes(file.uri, bytes)
    }

    private fun find(path: String): DocumentFile? {
        var current = root ?: return null
        for (segment in path.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private object Attempt {
        /**
         * A content provider can throw for a file that vanished mid-walk; the
         * indexer records a skip, so reads must not throw.
         */
        fun bytes(context: Context, uri: Uri, max: Int): ByteArray? = try {
            context.contentResolver.openInputStream(uri)?.use { stream -> readAtMost(stream, max) }
        } catch (e: Exception) {
            null
        }

        /** No readNBytes: that method is API 33 and this app runs on API 31. */
        private fun readAtMost(stream: java.io.InputStream, max: Int): ByteArray {
            val buffer = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
            val chunk = ByteArray(16 * 1024)
            while (buffer.size() < max) {
                val wanted = minOf(chunk.size, max - buffer.size())
                val read = stream.read(chunk, 0, wanted)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }

    private fun openBytes(uri: Uri, max: Int): ByteArray? = Attempt.bytes(context, uri, max)

    private fun openLimited(uri: Uri, max: Int): String? =
        openBytes(uri, max)?.let { if (it.isEmpty()) "" else String(it, Charsets.UTF_8) }
}
