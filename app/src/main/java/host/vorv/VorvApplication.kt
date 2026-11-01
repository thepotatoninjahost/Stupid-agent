package host.vorv

import android.app.Application
import android.content.ComponentCallbacks2
import host.vorv.data.SecretsStore
import host.vorv.data.VorvDatabase
import host.vorv.data.VorvPrefs
import host.vorv.llm.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-scoped wiring (audit findings H-4, L-7):
 *  - owns the DB, prefs, secrets and the ModelRepository as singletons
 *  - migrates the legacy plaintext API key into encrypted storage, once
 *  - releases the local LLM's native memory under system memory pressure
 *    (when no task is running — the engine rebuilds lazily)
 */
class VorvApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var db: VorvDatabase
        private set
    lateinit var prefs: VorvPrefs
        private set
    lateinit var secrets: SecretsStore
        private set
    lateinit var modelRepository: ModelRepository
        private set

    override fun onCreate() {
        super.onCreate()

        prefs = VorvPrefs(this)
        secrets = SecretsStore(this)
        db = androidx.room.Room.databaseBuilder(
            this,
            VorvDatabase::class.java,
            "vorv.db"
        )
            // Real migrations only — NO destructive fallback (audit H-5).
            .addMigrations(*VorvDatabase.ALL_MIGRATIONS)
            .build()
        modelRepository = ModelRepository(this)

        // One-shot migration: plaintext DataStore API key → encrypted storage (C-5).
        appScope.launch {
            runCatching { secrets.migrateFromLegacy(prefs) }
                .onFailure { android.util.Log.e(TAG, "legacy key migration failed: ${it.message}", it) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            appScope.launch {
                runCatching { modelRepository.onTrimMemory() }
            }
        }
    }

    private companion object {
        const val TAG = "Vorv/Application"
    }
}
