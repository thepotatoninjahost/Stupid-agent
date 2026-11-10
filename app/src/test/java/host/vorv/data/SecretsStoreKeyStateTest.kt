package host.vorv.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B-1 regression tests (code review, 2026-11).
 *
 * THE BUG: SecretsStore.zaiApiKey used to be a cold Flow that emitted the
 * stored key exactly once and completed. AgentViewModel collects it once at
 * init to drive `hasApiKey`, so a key the user saved in Setup never reached
 * that state until the process restarted — and send() rejected every
 * cloud-mode message with "Cloud mode needs a Z.ai API key" even though the
 * probe had just proven the key works. The DEFAULT first-run path was dead
 * on arrival.
 *
 * THE CONTRACT THESE TESTS PIN:
 *  1. a collector that is ALREADY running sees a save without
 *     re-subscribing (this is the exact step that used to fail),
 *  2. the current value is readable at any time via first(),
 *  3. a fresh instance (the restart path) still seeds from disk,
 *  4. saving an empty key clears the state just as loudly.
 *
 * Robolectric supplies the Android framework pieces; the Keystore-backed
 * EncryptedSharedPreferences path is bypassed via the store's test-only
 * prefs override because Robolectric cannot exercise the Android Keystore.
 * The encrypted path itself is unchanged from the audited C-5 fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // plain Application: VorvApplication eagerly touches the Keystore
class SecretsStoreKeyStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Real SharedPreferences (XML-backed under Robolectric) — no Keystore. */
    private fun newPrefs() = context.getSharedPreferences("vorv_secrets_test", Context.MODE_PRIVATE)

    @Test
    fun `a live collector sees the saved key without resubscribing - the B-1 loop`() = runTest {
        val store = SecretsStore(context, prefsOverride = newPrefs())
        assertEquals("", store.zaiApiKey.value) // seeded empty on a fresh install

        val seen = mutableListOf<String>()
        val collector = launch { store.zaiApiKey.take(2).toList(seen) }
        advanceUntilIdle()
        assertEquals("collector must receive the initial (empty) state", listOf(""), seen)

        store.setZaiApiKey("sk-live-collector-proof")
        advanceUntilIdle()
        collector.join()

        // THE assertion that fails on the old code: the already-running
        // collector received the save. Old cold flow: completes after the
        // first emission, take(2) never completes → seen == [""].
        assertEquals(
            "a running collector must observe the save without resubscribing",
            listOf("", "sk-live-collector-proof"),
            seen,
        )
    }

    @Test
    fun `current value is readable immediately after save`() = runTest {
        val store = SecretsStore(context, prefsOverride = newPrefs())
        store.setZaiApiKey("sk-now")
        advanceUntilIdle()
        assertEquals("sk-now", store.zaiApiKey.first())
        assertEquals("sk-now", store.zaiApiKey.value)
    }

    @Test
    fun `a fresh instance - the restart path - seeds from disk`() = runTest {
        val store = SecretsStore(context, prefsOverride = newPrefs())
        store.setZaiApiKey("sk-persisted")
        advanceUntilIdle()

        // Simulates the next process: a NEW store over the SAME prefs file.
        val restarted = SecretsStore(context, prefsOverride = newPrefs())
        assertEquals("sk-persisted", restarted.zaiApiKey.first())
    }

    @Test
    fun `saving an empty key clears the observable state`() = runTest {
        val store = SecretsStore(context, prefsOverride = newPrefs())
        store.setZaiApiKey("sk-clear-me")
        advanceUntilIdle()

        val seen = mutableListOf<String>()
        val collector = launch { store.zaiApiKey.take(2).toList(seen) }
        advanceUntilIdle()

        store.setZaiApiKey("   ") // whitespace-only: saved trimmed to ""
        advanceUntilIdle()
        collector.join()

        assertEquals(
            "clearing the key must be observable, not just on disk",
            listOf("sk-clear-me", ""),
            seen,
        )
        assertTrue("trimmed empty key must read as no-key", store.zaiApiKey.value.isBlank())
    }

    @Test
    fun `failed disk writes cannot publish a successful save`() = runTest {
        val real = newPrefs()
        val failing = object : android.content.SharedPreferences by real {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = real.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit() = false
                }
            }
        }
        val store = SecretsStore(context, prefsOverride = failing)
        val result = runCatching { store.setZaiApiKey("synthetic-unsaved-key") }
        assertTrue(result.isFailure)
        assertEquals("", store.zaiApiKey.value)
    }

    @Test
    fun `value is whitespace-trimmed on save`() = runTest {
        val store = SecretsStore(context, prefsOverride = newPrefs())
        store.setZaiApiKey("  sk-padded  \n")
        advanceUntilIdle()
        assertEquals("sk-padded", store.zaiApiKey.value)
    }
}
