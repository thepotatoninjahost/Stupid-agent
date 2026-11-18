package host.vorv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.vorvPrefs by preferencesDataStore(name = "vorv_prefs")

/**
 * Non-secret persisted settings for Vorv (workspace URI, LLM mode, selected
 * local model, reminder registry). API keys live in [SecretsStore], NOT here.
 *
 * Renamed from the old misnamed `KeyStore` class (audit finding L-1): this
 * is a preferences wrapper, not a keystore, and never holds secrets.
 */
class VorvPrefs(private val context: Context) {

    /** URI of the SAF-granted workspace folder. Empty until user picks one in Files screen. */
    val workspaceUri: Flow<String> = context.vorvPrefs.data.map { it[WORKSPACE_URI] ?: "" }

    /** "local" | "cloud" — which reasoning engine to use. */
    val llmMode: Flow<String> = context.vorvPrefs.data.map { it[LLM_MODE] ?: "cloud" }

    /**
     * v0.7.0 — which Z.ai cloud model to use in cloud mode.
     * Defaults to the FREE glm-4.7-flash tier; paid models are opt-in.
     * See host.vorv.llm.CloudModel for the catalog.
     */
    val cloudModelId: Flow<String> = context.vorvPrefs.data.map { it[CLOUD_MODEL_ID] ?: "glm-4.7-flash" }

    /**
     * v0.7.0 — when true and a local model is downloaded, cloud tasks wrap
     * their engine in FallbackLlm: if the cloud call fails mid-task (signal
     * drop, 5xx, exhausted retries), the same step reruns on the offline
     * engine instead of killing the task. Auth errors never fall back.
     */
    val autoFallback: Flow<Boolean> = context.vorvPrefs.data.map { it[AUTO_FALLBACK] ?: true }

    /**
     * 0.9.13 — any address that speaks the same language as Z.ai
     * (`POST <base>/chat/completions`, `Authorization: Bearer <key>`).
     * Blank means Z.ai's own address, so nothing changes for an existing setup.
     */
    val cloudBaseUrl: Flow<String> = context.vorvPrefs.data.map { it[CLOUD_BASE_URL] ?: "" }

    /**
     * 0.9.13 — the exact model name that address serves. Blank means the Z.ai
     * chain in [host.vorv.llm.CloudModel]. Set both to run a different coder
     * (any provider whose API matches the above) without a code change.
     */
    val cloudCustomModel: Flow<String> = context.vorvPrefs.data.map { it[CLOUD_CUSTOM_MODEL] ?: "" }

    /** "qwen7b" | "qwen3b" — which local model to use when llmMode = "local". */
    val localModelId: Flow<String> = context.vorvPrefs.data.map { it[LOCAL_MODEL_ID] ?: "qwen7b" }

    /** 0.9.18 — which cloud provider is selected ("" = the Z.ai preset). */
    val cloudProviderId: Flow<String> = context.vorvPrefs.data.map { it[CLOUD_PROVIDER_ID] ?: "" }
    /** Whether the POST_NOTIFICATIONS runtime prompt has been shown (Android 13+). */
    val notifPermissionAsked: Flow<Boolean> = context.vorvPrefs.data.map { it[NOTIF_ASKED] ?: false }
    /** JSON array of pending scheduled reminders: [{"name":..,"title":..,"etaMs":..}] */
    val scheduledReminders: Flow<String> = context.vorvPrefs.data.map { it[SCHEDULED] ?: "[]" }

    suspend fun setWorkspaceUri(uri: String) {
        context.vorvPrefs.edit { it[WORKSPACE_URI] = uri }
    }

    suspend fun setCloudBaseUrl(url: String) {
        context.vorvPrefs.edit { it[CLOUD_BASE_URL] = url.trim() }
    }

    suspend fun setCloudCustomModel(id: String) {
        context.vorvPrefs.edit { it[CLOUD_CUSTOM_MODEL] = id.trim() }
    }

    suspend fun setLlmMode(mode: String) {
        context.vorvPrefs.edit { it[LLM_MODE] = if (mode == "local") "local" else "cloud" }
    }

    /** v0.7.0 — selects the cloud model (id must match a CloudModel entry). */
    suspend fun setCloudModelId(id: String) {
        context.vorvPrefs.edit { it[CLOUD_MODEL_ID] = id }
    }

    /** v0.7.0 — arms/disarms the offline fallback for cloud tasks. */
    suspend fun setAutoFallback(enabled: Boolean) {
        context.vorvPrefs.edit { it[AUTO_FALLBACK] = enabled }
    }

    suspend fun setLocalModelId(id: String) {
        context.vorvPrefs.edit { it[LOCAL_MODEL_ID] = id }
    }

    suspend fun markNotifPermissionAsked() {
        context.vorvPrefs.edit { it[NOTIF_ASKED] = true }
    }

    suspend fun setScheduledReminders(json: String) {
        context.vorvPrefs.edit { it[SCHEDULED] = json }
    }

    // ------------------------------------------------------------ legacy migration

    /** Reads the pre-0.6.0 plaintext API key (for one-shot move to SecretsStore). */
    suspend fun readLegacyApiKeyOnce(): String =
        context.vorvPrefs.data.first()[LEGACY_ZAI_KEY] ?: ""

    /** Erases the pre-0.6.0 plaintext API key. */
    suspend fun eraseLegacyApiKey() {
        context.vorvPrefs.edit { it.remove(LEGACY_ZAI_KEY) }
    }

    /** Last observed brain facts, so Setup → Brain health survives a restart. */
    suspend fun readBrainFactsJson(): String =
        context.vorvPrefs.data.first()[BRAIN_FACTS] ?: ""

    suspend fun setBrainFactsJson(json: String) {
        context.vorvPrefs.edit { it[BRAIN_FACTS] = json }
    }

    /** Specific cloud provider chosen in Setup ("" = the built-in Z.ai preset). */
    suspend fun readCloudProviderId(): String =
        context.vorvPrefs.data.first()[CLOUD_PROVIDER_ID] ?: ""

    suspend fun setCloudProviderId(id: String) {
        context.vorvPrefs.edit { it[CLOUD_PROVIDER_ID] = id }
    }

    companion object {
        private val BRAIN_FACTS = stringPreferencesKey("brain_facts_json")
        private val CLOUD_PROVIDER_ID = stringPreferencesKey("cloud_provider_id")
        private val WORKSPACE_URI = stringPreferencesKey("workspace_uri")
        private val LLM_MODE = stringPreferencesKey("llm_mode")
        private val CLOUD_MODEL_ID = stringPreferencesKey("cloud_model_id")
        private val CLOUD_BASE_URL = stringPreferencesKey("cloud_base_url")
        private val CLOUD_CUSTOM_MODEL = stringPreferencesKey("cloud_custom_model")
        private val AUTO_FALLBACK = booleanPreferencesKey("auto_fallback")
        private val LOCAL_MODEL_ID = stringPreferencesKey("local_model_id")
        private val NOTIF_ASKED = booleanPreferencesKey("notif_permission_asked")
        private val SCHEDULED = stringPreferencesKey("scheduled_reminders")
        private val LEGACY_ZAI_KEY = stringPreferencesKey("zai_api_key")
    }
}
