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

    /** "local" | "cloud" — which reasoning engine to use. */
    val llmMode: Flow<String> = context.vorvPrefs.data.map { it[LLM_MODE] ?: "cloud" }

    /** "qwen7b" | "qwen3b" — which local model to use when llmMode = "local". See ModelManager.ModelChoice. */
    val localModelId: Flow<String> = context.vorvPrefs.data.map { it[LOCAL_MODEL_ID] ?: "qwen7b" }

    suspend fun setZaiApiKey(key: String) {
        context.vorvPrefs.edit { it[ZAI_KEY] = key.trim() }
    }

    suspend fun setWorkspaceUri(uri: String) {
        context.vorvPrefs.edit { it[WORKSPACE_URI] = uri }
    }

    suspend fun setLlmMode(mode: String) {
        context.vorvPrefs.edit { it[LLM_MODE] = if (mode == "local") "local" else "cloud" }
    }

    suspend fun setLocalModelId(id: String) {
        context.vorvPrefs.edit { it[LOCAL_MODEL_ID] = id }
    }

    companion object {
        private val ZAI_KEY = stringPreferencesKey("zai_api_key")
        private val WORKSPACE_URI = stringPreferencesKey("workspace_uri")
        private val LLM_MODE = stringPreferencesKey("llm_mode")
        private val LOCAL_MODEL_ID = stringPreferencesKey("local_model_id")
    }
}
