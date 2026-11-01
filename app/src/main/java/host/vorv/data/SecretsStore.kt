package host.vorv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 */
class SecretsStore(context: Context) {

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Emits the stored API key ("" when none). Cold flow; cheap to collect. */
    val zaiApiKey: Flow<String> = flow {
        emit(prefs.getString(KEY_ZAI, "") ?: "")
    }

    suspend fun setZaiApiKey(key: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_ZAI, key.trim()).apply()
        }
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
        }
        // Either way the plaintext copy must go.
        legacy.eraseLegacyApiKey()
    }

    companion object {
        private const val FILE_NAME = "vorv_secrets"
        private const val KEY_ZAI = "zai_api_key"
    }
}
