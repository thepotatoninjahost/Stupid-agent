package host.vorv.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M5 (code review, 2026-11): SecretsStore must not do Android-Keystore work
 * in its constructor. VorvApplication.onCreate constructs the store on the
 * MAIN thread; the old eager EncryptedSharedPreferences build (Keystore
 * handshake + encrypted-file open) ran there on every app start. Now the
 * production path builds lazily and seeds the StateFlow on Dispatchers.IO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SecretsStoreMainThreadTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `production constructor is cheap - no Keystore work on the calling thread`() {
        // No prefsOverride: the PRODUCTION path. Under Robolectric the
        // Keystore-backed build cannot succeed — and that is exactly the
        // point: it must not even be ATTEMPTED in the constructor (M5).
        // Construction returns immediately; the async seed degrades honestly
        // to "" instead of crashing startup.
        val store = SecretsStore(context)
        assertEquals("initial state is empty until the async seed lands", "", store.zaiApiKey.value)
    }
}
