package host.vorv.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C-2 (code review, 2026-11-20): a model call must not outlive its task.
 * Before this, `execute()` blocked a thread for the full 120 s read timeout,
 * retries multiplied it, and pressing STOP did nothing until the read
 * returned — the user saw a spinner with no error.
 */
class CloudLlmTimeoutTest {

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

    private fun llm(budgetMs: Long) = CloudLlm(
        apiKey = "k",
        baseUrl = server.url("/v4").toString().trimEnd('/'),
        client = OkHttpClient(),
        maxAttempts = 2,
        backoffMs = 10L,
        requestBudgetMs = budgetMs,
    )

    private val messages = listOf(Llm.Message("user", "hi"))

    @Test
    fun `a provider that never answers is cut off inside the budget`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val started = System.currentTimeMillis()
        val failure = runCatching { llm(budgetMs = 400).step(messages, emptyList()) }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("the call should have failed", failure.isFailure)
        assertTrue(
            "the error must say it timed out, got: ${failure.exceptionOrNull()?.message}",
            failure.exceptionOrNull()?.message?.contains("did not answer within") == true,
        )
        assertTrue("the budget was not honoured: took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `stopping the task cancels the in-flight call at once`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = llm(budgetMs = 60_000)

        val job = launch(Dispatchers.IO) { runCatching { call.step(messages, emptyList()) } }
        // Wait until the request is actually open, so cancellation has something
        // to cancel rather than racing the enqueue.
        var waited = 0L
        while (server.requestCount == 0 && waited < 5_000) {
            delay(10)
            waited += 10
        }
        assertTrue("the request never reached the server", server.requestCount > 0)

        val started = System.currentTimeMillis()
        job.cancelAndJoin()
        val elapsed = System.currentTimeMillis() - started

        assertTrue("cancellation waited ${elapsed}ms for the blocked read", elapsed < 2_000)
    }
}
