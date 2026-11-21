package host.vorv.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import host.vorv.data.VorvPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * H-4: the brain-facts record must survive a restart without ever entering a
 * cloud backup, and the old SharedPreferences copy must be erased once moved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BrainFactsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `facts round-trip through the no-backup file`() {
        val store = BrainFactsStore(context)
        store.save("""{"lastError":"boom"}""")
        assertEquals("""{"lastError":"boom"}""", store.load())
    }

    @Test
    fun `the file lives under noBackupFilesDir`() {
        val store = BrainFactsStore(context)
        store.save("{}")
        val path = File(context.noBackupFilesDir, "llm/brain_facts.json")
        assertTrue("expected $path to exist", path.exists())
        assertTrue(path.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
    }

    @Test
    fun `a missing file loads as null`() {
        File(context.noBackupFilesDir, "llm/brain_facts.json").delete()
        assertNull(BrainFactsStore(context).load())
    }

    @Test
    fun `migrateLegacy moves the prefs value into the file and erases the key`() {
        seedLegacyPrefs("""{"lastError":"from-prefs"}""")
        val prefs = VorvPrefs(context)
        val store = BrainFactsStore(context)

        runBlocking { store.migrateLegacy(prefs) }

        assertEquals("""{"lastError":"from-prefs"}""", store.load())
        assertEquals("the backed-up key must be gone", "", runBlocking { prefs.readBrainFactsJson() })
    }

    @Test
    fun `migrateLegacy keeps an existing file and still erases the key`() {
        seedLegacyPrefs("""{"stale":true}""")
        val store = BrainFactsStore(context)
        store.save("""{"fresh":true}""")
        val prefs = VorvPrefs(context)

        runBlocking { store.migrateLegacy(prefs) }

        assertEquals("""{"fresh":true}""", store.load())
        assertEquals("", runBlocking { prefs.readBrainFactsJson() })
    }

    /**
     * Seeds the preference the deleted setter used to write. A dedicated
     * DataStore scope is cancelled before the test touches VorvPrefs, because
     * two active instances over one file is an error.
     */
    private fun seedLegacyPrefs(json: String) {
        val file = context.preferencesDataStoreFile("vorv_prefs")
        file.parentFile?.mkdirs()
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
        runBlocking {
            ds.edit { it[stringPreferencesKey("brain_facts_json")] = json }
            scope.coroutineContext.job.cancel()
            scope.coroutineContext.job.join()
        }
        scope.cancel()
    }
}
