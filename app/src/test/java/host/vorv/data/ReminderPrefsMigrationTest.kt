package host.vorv.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ReminderPrefsMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `failed private write leaves legacy content available for retry`() = runBlocking {
        val key = stringPreferencesKey("scheduled_reminders")
        val source = preferencesOf(key to "private title")
        val brokenStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> get() = error("Not used")
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw IOException("Disk unavailable")
            }
        }
        try {
            ReminderPrefsMigration(brokenStore).migrate(source)
            fail("Expected failed destination write")
        } catch (_: IOException) {
            assertEquals("private title", source[key])
        }
    }

    @Test fun `migration preserves titles and settings and removes backup eligible copy`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val scheduled = stringPreferencesKey("scheduled_reminders")
            val mode = stringPreferencesKey("llm_mode")
            val file = temporary.newFolder().resolve("settings.preferences_pb")
            val privateFile = temporary.newFolder().resolve("reminders.preferences_pb")
            val privateStore = PreferenceDataStoreFactory.create(scope = scope) { privateFile }
            val migration = ReminderPrefsMigration(privateStore)
            val legacy = preferencesOf(
                scheduled to """[{"name":"work1","title":"Private appointment","etaMs":123}]""",
                mode to "local",
            )
            val migrated = migration.migrate(legacy)
            assertNull(migrated[scheduled])
            assertEquals("local", migrated[mode])
            assertEquals(legacy[scheduled], privateStore.data.first()[scheduled])
            assertFalse(migration.shouldMigrate(migrated))

            // Simulate a crash after the private write but before the source commit.
            privateStore.edit { it[scheduled] = "[]" }
            migration.migrate(legacy)
            assertEquals("[]", privateStore.data.first()[scheduled])

            // Exercise migration through DataStore initialization as used in production.
            val source = PreferenceDataStoreFactory.create(
                scope = scope,
                migrations = listOf(object : DataMigration<Preferences> {
                    override suspend fun shouldMigrate(currentData: Preferences) = true
                    override suspend fun migrate(currentData: Preferences) = legacy
                    override suspend fun cleanUp() = Unit
                }, migration),
            ) { file }
            assertNull(source.data.first()[scheduled])
            assertEquals("local", source.data.first()[mode])
        } finally {
            scope.cancel()
        }
    }
}
