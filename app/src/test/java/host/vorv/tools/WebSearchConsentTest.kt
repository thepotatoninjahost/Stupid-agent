package host.vorv.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import host.vorv.data.SecretsStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WebSearchConsentTest {
    @Test fun `search requires separate approval of destination and complete query`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("web_consent_test", Context.MODE_PRIVATE)
        val secrets = SecretsStore(context, prefsOverride = prefs)
        secrets.setZaiApiKey("synthetic-test-key")
        val server = MockWebServer()
        server.start()
        try {
            val tool = WebSearchTool(secrets, server.url("/").toString().trimEnd('/'), OkHttpClient())
            val input = JsonObject().apply { addProperty("query", "operator-private-memory") }
            assertFalse(tool.run(input, ToolContext("t", 1, null)).ok)
            assertFalse(tool.run(input, ToolContext("t", 1, null, confirm = { false })).ok)
            assertEquals(0, server.requestCount)
            var prompt = ""
            server.enqueue(MockResponse().setBody("{\"search_result\":[]}"))
            assertTrue(tool.run(input, ToolContext("t", 1, null, confirm = {
                prompt = it
                true
            })).ok)
            assertTrue(prompt.contains(server.url("/web_search").toString()))
            assertTrue(prompt.contains("operator-private-memory"))
            assertFalse(prompt.contains("synthetic-test-key"))
            assertEquals(1, server.requestCount)
            assertTrue(server.takeRequest().body.readUtf8().contains("operator-private-memory"))
            server.enqueue(MockResponse().setResponseCode(307)
                .setHeader("Location", server.url("/unapproved")))
            assertFalse(tool.run(input, ToolContext("t", 1, null, confirm = { true })).ok)
            assertEquals("approved search must not forward its body to a redirect", 2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
