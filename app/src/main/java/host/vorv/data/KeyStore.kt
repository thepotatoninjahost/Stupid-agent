package host.vorv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vorvPrefs by preferencesDataStore(name = "vorv_prefs")

/**
 * Locally persisted secrets for Vorv. Never leaves the device.
 */
class KeyStore(private val context: Context) {

    val zaiApiKey: Flow<String> = context.vorvPrefs.data.map { it[ZAI_KEY] ?: "" }

    /** URI of the SAF-granted workspace folder. Empty until user picks one in Files screen. */
    val workspaceUri: Flow<String> = context.vorvPrefs.data.map { it[WORKSPACE_URI] ?: "" }

    suspend fun setZaiApiKey(key: String) {
        context.vorvPrefs.edit { it[ZAI_KEY] = key.trim() }
    }

    suspend fun setWorkspaceUri(uri: String) {
        context.vorvPrefs.edit { it[WORKSPACE_URI] = uri }
    }

    companion object {
        private val ZAI_KEY = stringPreferencesKey("zai_api_key")
        private val WORKSPACE_URI = stringPreferencesKey("workspace_uri")
    }
}
