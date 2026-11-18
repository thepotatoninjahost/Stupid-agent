package host.vorv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /**
     * M5 (code review, 2026-11): the Keystore-backed EncryptedSharedPreferences
     * is built LAZILY and, on the production path, the StateFlow seed is read
     * on Dispatchers.IO. The store used to do both in its constructor — and
     * VorvApplication.onCreate constructs it on the MAIN thread, so every
     * app start paid the Android-Keystore handshake and the encrypted-file
     * open on the UI thread. The prefsOverride path (unit tests) stays fully
     * synchronous — the B-1 tests pin the immediately-visible seed there.
     */
    private val overridePrefs: SharedPreferences? = prefsOverride

    private val productionPrefs: Lazy<SharedPreferences> = lazy {
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

    private val prefs: SharedPreferences
        get() = overridePrefs ?: productionPrefs.value

    /** Seed scope — lives only until the one-shot seed read completes. */
    private val seedScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** M5: set the moment a WRITE is attempted; a seed still in flight must
     *  never overwrite a newer save or migration with a stale read. */
    @Volatile private var savedDuringSeed = false

    private val _zaiApiKey = kotlinx.coroutines.flow.MutableStateFlow("")

    /** 0.9.18 — one key per provider id, all in the same encrypted file. */
    private val _keys = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Why the key store could not be opened (Keystore reset, corrupted file).
     * Null means "read fine". This exists because "no key" and "key unreadable"
     * were the same sentence on screen, and they need different fixes.
     */
    private val _readError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    init {
        if (overridePrefs != null) {
            // Test path — synchronous, no Keystore, B-1 contract visible at
            // construction.
            _zaiApiKey.value = overridePrefs.getString(KEY_ZAI, "") ?: ""
            _keys.value = readAllKeys(overridePrefs)
        } else {
            // M5: production — read the encrypted file OFF the main thread.
            // First access builds EncryptedSharedPreferences here too.
            seedScope.launch {
                val stored = runCatching { prefs.getString(KEY_ZAI, "") ?: "" }
                    .onFailure { e ->
                        _readError.value = "the encrypted key store could not be opened: " +
                            (e.message ?: e.javaClass.simpleName)
                    }
                    .getOrDefault("")
                if (!savedDuringSeed) _zaiApiKey.value = stored
                if (!savedDuringSeed) _keys.value = runCatching { readAllKeys(prefs) }.getOrDefault(emptyMap())
                seedScope.cancel()
            }
        }
    }

    /** Every saved provider key. Never logged, never shown. */
    val keys: StateFlow<Map<String, String>> = _keys.asStateFlow()

    /** Non-null when the encrypted store itself failed — Setup shows this. */
    val readError: StateFlow<String?> = _readError.asStateFlow()

    /**
     * The key for one provider, from memory (seeded from the encrypted file).
     * "" means this provider has no key — not that the store failed; check
     * [readError] for that distinction.
     */
    fun keyFor(providerId: String): String =
        _keys.value[providerId] ?: if (providerId == "z.ai") _zaiApiKey.value else ""

    suspend fun setProviderKey(providerId: String, key: String) {
        val trimmed = key.trim()
        savedDuringSeed = true
        withContext(Dispatchers.IO) {
            prefs.edit().putString(prefName(providerId), trimmed).apply()
        }
        _keys.value = _keys.value + (providerId to trimmed)
        if (providerId == "z.ai") _zaiApiKey.value = trimmed
        _readError.value = null
    }

    suspend fun removeProviderKey(providerId: String) {
        savedDuringSeed = true
        withContext(Dispatchers.IO) {
            prefs.edit().remove(prefName(providerId)).apply()
        }
        _keys.value = _keys.value - providerId
        if (providerId == "z.ai") {
            _zaiApiKey.value = ""
            withContext(Dispatchers.IO) { prefs.edit().remove(KEY_ZAI).apply() }
        }
    }

    private fun readAllKeys(p: SharedPreferences): Map<String, String> =
        p.all.mapNotNull { (k, v) ->
            when {
                k.startsWith(PREFIX) -> k.removePrefix(PREFIX) to (v as? String ?: "")
                k == KEY_ZAI -> "z.ai" to (v as? String ?: "")
                else -> null
            }
        }.filter { it.second.isNotBlank() }.toMap()

    /** z.ai keeps its 0.6.0 slot so existing installs need no migration. */
    internal fun prefName(providerId: String) = prefNameFor(providerId)

    /**
     * The stored API key ("" when none). Hot state: the CURRENT value is
     * always available without suspension, every save is published to
     * live collectors, and the value survives process restarts because it
     * is seeded from the encrypted file (B-1 fix; M5: on the production
     * path the seed read runs on Dispatchers.IO instead of the caller's
     * — i.e. the app's main — thread).
     */
    val zaiApiKey: StateFlow<String> = _zaiApiKey.asStateFlow()

    suspend fun setZaiApiKey(key: String) = setProviderKey("z.ai", key)

    /**
     * One-shot migration from the pre-0.6.0 plaintext DataStore key.
     * Idempotent: once the legacy key is erased it reads as "" and this
     * becomes a no-op.
     */
    suspend fun migrateFromLegacy(legacy: VorvPrefs) {
        val legacyKey = legacy.readLegacyApiKeyOnce()
        if (legacyKey.isBlank()) return
        val current = withContext(Dispatchers.IO) { prefs.getString(KEY_ZAI, "") ?: "" }
        if (current.isBlank()) {
            // M5: same seed guard as setZaiApiKey.
            savedDuringSeed = true
            withContext(Dispatchers.IO) {
                prefs.edit().putString(KEY_ZAI, legacyKey).apply()
            }
            // B-1: a migrated key must reach collectors too, not only the
            // next process launch.
            _zaiApiKey.value = legacyKey
            _keys.value = _keys.value + ("z.ai" to legacyKey)
        }
        // Either way the plaintext copy must go.
        legacy.eraseLegacyApiKey()
    }

    companion object {
        private const val FILE_NAME = "vorv_secrets"
        private const val KEY_ZAI = "zai_api_key"
        private const val PREFIX = "key."

        /** The one place a provider id becomes a preference name. */
        internal fun prefNameFor(providerId: String) =
            if (providerId == "z.ai") KEY_ZAI else PREFIX + providerId
    }
}
