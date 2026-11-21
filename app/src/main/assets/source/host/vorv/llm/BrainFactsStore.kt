package host.vorv.llm

import android.content.Context
import android.util.Log
import host.vorv.data.VorvPrefs
import java.io.File
import java.io.IOException

/**
 * H-4 (code review, 2026-11): the brain-facts record used to live in
 * SharedPreferences, which Android uploads to the user's cloud backup and
 * carries over on device-to-device transfer. It names the provider host, the
 * model and the last error text — a diagnostic record about the phone, not
 * something that should ever leave it.
 *
 * It lives in [Context.noBackupFilesDir] now: the same survival across a
 * restart, and excluded from both backup paths by where it is, not by a rule
 * someone can forget to keep.
 */
class BrainFactsStore(private val context: Context) {

    private val file: File get() = File(context.noBackupFilesDir, "llm/brain_facts.json")

    /**
     * Synchronous on purpose: [BrainState.attachStore] takes a plain lambda.
     * Writes a `.tmp` file and renames it, so a kill mid-write cannot leave a
     * half JSON document that load() would then hand to Gson.
     */
    fun save(json: String) {
        val target = file
        try {
            val dir = target.parentFile ?: return
            dir.mkdirs()
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                target.writeText(json)
                tmp.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "could not save the brain facts: ${e.message}")
        }
    }

    /** Null when there is nothing to restore (first run, or the file is unreadable). */
    fun load(): String? {
        val target = file
        if (!target.exists()) return null
        return try {
            target.readText().ifBlank { null }
        } catch (e: IOException) {
            Log.w(TAG, "could not read the brain facts: ${e.message}")
            null
        }
    }

    /**
     * Moves a value the old build left in SharedPreferences into the new file,
     * then erases the key so it cannot ride along in the next backup.
     *
     * The old key is erased only once the new file really holds the value; if
     * the write failed, the value stays where it was rather than being lost.
     */
    suspend fun migrateLegacy(prefs: VorvPrefs) {
        val legacy = try {
            prefs.readBrainFactsJson()
        } catch (e: Exception) {
            Log.w(TAG, "could not read the legacy brain facts: ${e.message}")
            ""
        }
        if (legacy.isBlank()) return
        if (!file.exists()) save(legacy)
        if (file.exists()) prefs.eraseLegacyBrainFacts()
    }

    private companion object {
        const val TAG = "VorvBrainFacts"
    }
}
