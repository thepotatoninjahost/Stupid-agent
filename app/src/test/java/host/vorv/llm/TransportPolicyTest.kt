package host.vorv.llm

import host.vorv.llm.BrainDoctor.State
import host.vorv.llm.provider.BrainTarget
import host.vorv.llm.provider.ModelCatalog
import host.vorv.llm.provider.Provider
import host.vorv.llm.provider.ProviderUrl
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * H-5: a prompt and its key only ever go to an https address, and a redirect
 * is refused instead of followed to some other host.
 */
class TransportPolicyTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val messages = listOf(Llm.Message("user", "hi"))

    // ------------------------------------------------------------- addresses

    @Test
    fun `https, loopback and blank are accepted, cleartext is not`() {
        assertNull(ProviderUrl.rejectionReason("https://api.example/v4"))
        assertNull(ProviderUrl.rejectionReason("http://127.0.0.1:8080/v4"))
        assertNull(ProviderUrl.rejectionReason("http://localhost:8080"))
        assertNull(ProviderUrl.rejectionReason("  "))
        val reason = ProviderUrl.rejectionReason("http://api.example/v4")
        assertTrue(reason!!.contains("must start with https://"))
        // A name that merely starts with "localhost" is still cleartext off-device.
        assertTrue(ProviderUrl.rejectionReason("http://localhost.evil.example/") != null)
    }

    @Test
    fun `Setup refuses a cleartext address in the same words`() {
        assertEquals(
            ProviderUrl.rejectionReason("http://api.example/v4"),
            BrainTarget.refusal("http://api.example/v4", "some-model"),
        )

        val check = BrainDoctor.diagnose(
            BrainFacts(
                llmMode = "cloud",
                providerLabel = "Z.ai",
                baseUrl = "http://api.example/v4",
                modelId = "some-model",
                keyPresent = true,
            ),
        ).first { it.name == "address" }
        assertEquals(State.FAIL, check.state)
        assertTrue(check.detail.contains("must start with https://"))
    }

    // ------------------------------------------------------- no wire, no send

    @Test
    fun `a cleartext CloudLlm fails before any request is attempted`() = runBlocking {
        val noNetwork = OkHttpClient.Builder().dns { host ->
            fail("a request was attempted to $host")
            error("unreachable")
        }.build()
        val llm = CloudLlm(
            apiKey = "k",
            baseUrl = "http://api.example/v4",
            modelName = "some-model",
            client = noNetwork,
            maxAttempts = 1,
            backoffMs = 0L,
        )
        try {
            llm.step(messages, emptyList())
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must start with https://"))
        }
    }

    @Test
    fun `probeAt refuses a cleartext address without a request`() = runBlocking {
        val result = CloudLlm.probeAt("k", "http://api.example/v4", "some-model")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("must start with https://"))
    }

    // -------------------------------------------------------------- redirects

    @Test
    fun `a redirect is refused and never followed`() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.path.orEmpty()
                return if (request.path.orEmpty() == "/v4/chat/completions") {
                    MockResponse()
                        .setResponseCode(307)
                        .addHeader("Location", "https://elsewhere.example/steal")
                        .setBody("""{"error":"moved"}""")
                } else {
                    MockResponse().setResponseCode(200).setBody("""{"choices":[]}""")
                }
            }
        }
        val llm = CloudLlm(
            apiKey = "k",
            baseUrl = server.url("/v4").toString().trimEnd('/'),
            modelName = "some-model",
            client = OkHttpClient(),
            maxAttempts = 3,
            backoffMs = 0L,
        )
        try {
            llm.step(messages, emptyList())
            fail("expected the redirect to be refused")
        } catch (e: LlmHttpException) {
            assertTrue(e.message!!.contains("redirected the request to https://elsewhere.example/steal"))
            assertTrue(e.message!!.contains("redirects are refused"))
        }
        assertEquals("only the original request, never the redirect target", listOf("/v4/chat/completions"), seen)
    }

    @Test
    fun `a model listing names the redirect instead of following it`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://elsewhere.example/models")
                .setBody(""),
        )
        val provider = Provider(id = "p", label = "P", baseUrl = server.url("/v4").toString().trimEnd('/'))
        val listing = ModelCatalog(client = OkHttpClient()).fetch(provider, "k")
        assertTrue(listing.entries.isEmpty())
        assertTrue(listing.error!!.contains("redirected the request to https://elsewhere.example/models"))
        assertTrue(listing.error!!.contains("redirects are refused"))
        assertEquals(1, server.requestCount)
    }
}
