package host.vorv.project

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps one index per open project folder and refreshes it off the main thread.
 *
 * Why a cache and not a build per message: a first index of a ten-thousand-file
 * tree takes seconds, and doing that inside a prompt build would look like the
 * app had hung. Why [MAX_AGE_MS] rather than watching files: SAF has no cheap
 * change notification, so the index is rebuilt after a quiet interval instead,
 * and the rebuild is incremental — unchanged files are reused by fingerprint.
 *
 * Every failure is a stated [Snapshot] with its reason. Null means "do not know
 * yet, and the caller must say so", never "no project".
 */
object ProjectIndexCache {

    private const val MAX_AGE_MS = 10 * 60 * 1000L

    private val mutex = Mutex()
    private var uri: String? = null
    private var snapshot: IndexSnapshot? = null
    private var builtAt = 0L
    private var failure: String? = null

    /** The current index for [treeUri], building or refreshing it if needed. */
    suspend fun get(context: Context, treeUri: String, force: Boolean = false): Result {
        if (treeUri.isBlank()) return Result(null, "No project folder is open.")
        mutex.withLock {
            if (!force && uri == treeUri) {
                failure?.let { return Result(snapshot, it) }
                val fresh = snapshot != null && System.currentTimeMillis() - builtAt < MAX_AGE_MS
                if (fresh) return Result(snapshot, null)
            }
            return withContext(Dispatchers.IO) {
                val source = DocTreeFileSource(context, Uri.parse(treeUri))
                source.problem?.let {
                    uri = treeUri
                    failure = it
                    return@withContext Result(snapshot.takeIf { uri == treeUri }, it)
                }
                val previous = if (uri == treeUri) snapshot else null
                val next = try {
                    ProjectIndexer(source).build(previous).copy(root = treeUri)
                } catch (e: Exception) {
                    failure = "Indexing the project failed: ${e.message ?: e::class.java.simpleName}"
                    return@withContext Result(snapshot.takeIf { uri == treeUri }, failure!!)
                }
                uri = treeUri
                snapshot = next
                builtAt = System.currentTimeMillis()
                failure = null
                Result(next, null)
            }
        }
    }

    /** The prompt section, or a plain statement of why there is none. */
    suspend fun section(context: Context, treeUri: String, task: String, budgetTokens: Int = 1_200): String {
        val result = get(context, treeUri)
        val body = ProjectContext.build(result.snapshot, task, budgetTokens)
        val problem = result.problem ?: return body
        return "$body\n\nIndex status: $problem"
    }

    data class Result(val snapshot: IndexSnapshot?, val problem: String?)
}
