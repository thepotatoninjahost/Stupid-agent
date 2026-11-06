package host.vorv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * At-rest-encrypted storage for the Z.ai API key (audit finding C-5).
 *
 * The key is written to an EncryptedSharedPreferences file whose master key
 * lives in the Android Keystore (hardware-backed on the S25's StrongBox).
 * The file on disk is AES-256-GCM ciphertext; reading it with `adb pull`
 * or from a forensic image yields encrypted bytes, not the key.
 *
 * The previous storage (plain DataStore protobuf) is migrated once, on
 * first launch of the fixed build: [migrateFromLegacy] moves the value and
 * erases the plaintext copy.
 *
 * B-1 (code review, 2026-11): the stored key is now ALSO observable. The
 * original API exposed a cold Flow that emitted once and completed, so the
 * UI's `hasApiKey` state was frozen at whatever the process started with:
 * after the user pasted and SAVED a working key, `send()` kept refusing
 * every cloud-mode message with "Cloud mode needs a Z.ai API key" until
 * the app was killed and reopened. The first run of the DEFAULT engine was
 * therefore broken. [zaiApiKey] is now a hot [StateFlow], seeded from disk
 * at construction and updated by every writer ([setZaiApiKey],
 * [migrateFromLegacy]) after its disk write lands. Existing collectors
 * (AgentViewModel init) see saves immediately; `.first()` readers
 * (WebTools, setCloudModel, send) get the live value.
 *
 * Instance model: the app constructs exactly ONE SecretsStore
 * (VorvApplication) and shares it, so the in-memory flow cannot go stale
 * against the encrypted file within a process. A second instance would
 * only see disk state as of ITS construction — that is why the constructor
 * keeps disk as the single source of truth and why the flow is never
 * written without the disk write landing first.
 *
 * The optional [prefsOverride] constructor parameter exists ONLY for unit
 * tests (Robolectric cannot exercise the Android Keystore that
 * EncryptedSharedPreferences requires). Production callers never pass it,
 * so the encrypted path is byte-for-byte the one audited at C-5.
 */
class SecretsStore(context: Context, prefsOverride: SharedPreferences? = null) {

    private val prefs: SharedPreferences = prefsOverride ?: run {
        val masterKey: MasterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _zaiApiKey = kotlinx.coroutines.flow.MutableStateFlow(
        prefs.getString(KEY_ZAI, "") ?: ""
    )

    /**
     * The stored API key ("" when none). Hot state: the CURRENT value is
     * always available without suspension, every save is published to
     * live collectors, and the value survives process restarts because it
     * is seeded from the encrypted file at construction (B-1 fix).
     */
    val zaiApiKey: StateFlow<String> = _zaiApiKey.asStateFlow()

    suspend fun setZaiApiKey(key: String) {
        val trimmed = key.trim()
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_ZAI, trimmed).apply()
        }
        // Publish AFTER the disk write succeeded — the flow never claims a
        // value the encrypted file does not hold.
        _zaiApiKey.value = trimmed
    }

    /**
     * One-shot migration from the pre-0.6.0 plaintext DataStore key.
     * Idempotent: once the legacy key is erased it reads as "" and this
     * becomes a no-op.
     */
    suspend fun migrateFromLegacy(legacy: VorvPrefs) {
        val legacyKey = legacy.readLegacyApiKeyOnce()
        if (legacyKey.isBlank()) return
        val current = prefs.getString(KEY_ZAI, "") ?: ""
        if (current.isBlank()) {
            withContext(Dispatchers.IO) {
                prefs.edit().putString(KEY_ZAI, legacyKey).apply()
            }
            // B-1: a migrated key must reach collectors too, not only the
            // next process launch.
            _zaiApiKey.value = legacyKey
        }
        // Either way the plaintext copy must go.
        legacy.eraseLegacyApiKey()
    }

    companion object {
        private const val FILE_NAME = "vorv_secrets"
        private const val KEY_ZAI = "zai_api_key"
    }
}
