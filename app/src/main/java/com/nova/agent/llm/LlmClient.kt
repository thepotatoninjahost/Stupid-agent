package com.nova.agent.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * GLM-4.6 chat client — calls the Z.ai OpenAI-compatible endpoint directly
 * from the device. No server, no proxy — pure client-side HTTP.
 *
 * The API key is injected at runtime (see ApiKey.kt).
 */
class LlmClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.z.ai/api/paas/v4",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Message(val role: String, val content: String)

    data class ChatOptions(
        val temperature: Double = 0.7,
        val maxTokens: Int = 2048,
    )

    /**
     * Single chat completion. Returns the assistant message content.
     * Retries on 429 / 5xx with exponential backoff (1.5s, 3s, 6s, 12s + jitter).
     */
    suspend fun chat(messages: List<Message>, opts: ChatOptions = ChatOptions()): String =
        withContext(Dispatchers.IO) {
            val body = JsonObject().apply {
                addProperty("model", "glm-4.6")
                addProperty("temperature", opts.temperature)
                addProperty("max_tokens", opts.maxTokens)
                add("messages", com.google.gson.JsonArray().apply {
                    messages.forEach { m ->
                        add(JsonObject().apply {
                            addProperty("role", m.role)
                            addProperty("content", m.content)
                        })
                    }
                })
            }.toString()

            var lastErr: String? = null
            for (attempt in 0..4) {
                try {
                    val req = Request.Builder()
                        .url("$baseUrl/chat/completions")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()

                    client.newCall(req).execute().use { res ->
                        val responseBody = res.body?.string().orEmpty()
                        if (res.code == 429 || res.code >= 500) {
                            lastErr = "HTTP ${res.code}: ${responseBody.take(200)}"
                            val backoff = (1500L * (1L shl attempt)) + (Math.random() * 500).toLong()
                            Thread.sleep(backoff)
                            return@use
                        }
                        if (!res.isSuccessful) {
                            throw RuntimeException("LLM call failed: HTTP ${res.code}: ${responseBody.take(300)}")
                        }
                        val json = JsonParser.parseString(responseBody).asJsonObject
                        val choices = json.getAsJsonArray("choices") ?: throw RuntimeException("No choices in response")
                        val first = choices[0].asJsonObject
                        return@withContext first.getAsJsonObject("message")?.get("content")?.asString ?: ""
                    }
                } catch (e: Exception) {
                    lastErr = e.message
                    val backoff = (1500L * (1L shl attempt)) + (Math.random() * 500).toLong()
                    Thread.sleep(backoff)
                }
            }
            throw RuntimeException("LLM call failed after retries: $lastErr")
        }

    /**
     * Real internet search via Z.ai's web_search function.
     */
    suspend fun webSearch(query: String, num: Int = 4): List<WebSearchHit> =
        withContext(Dispatchers.IO) {
            val body = JsonObject().apply {
                addProperty("name", "web_search")
                add("input", JsonObject().apply {
                    addProperty("query", query)
                    addProperty("num", num)
                })
            }.toString()

            val req = Request.Builder()
                .url("$baseUrl/functions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(req).execute().use { res ->
                    val responseBody = res.body?.string().orEmpty()
                    if (!res.isSuccessful) return@withContext emptyList()
                    val json = JsonParser.parseString(responseBody).asJsonObject
                    val results = json.getAsJsonArray("output") ?: return@withContext emptyList()
                    return@withContext results.map { r ->
                        val obj = r.asJsonObject
                        WebSearchHit(
                            url = obj.get("url")?.asString ?: "",
                            title = obj.get("name")?.asString ?: obj.get("title")?.asString ?: "",
                            snippet = obj.get("snippet")?.asString ?: "",
                            host = obj.get("host_name")?.asString ?: "",
                        )
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    /**
     * Read a webpage's content via Z.ai's page_reader function.
     */
    suspend fun readPage(url: String): PageReaderResult =
        withContext(Dispatchers.IO) {
            val body = JsonObject().apply {
                addProperty("name", "page_reader")
                add("input", JsonObject().apply {
                    addProperty("url", url)
                })
            }.toString()

            val req = Request.Builder()
                .url("$baseUrl/functions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(req).execute().use { res ->
                    val responseBody = res.body?.string().orEmpty()
                    if (!res.isSuccessful) return@withContext PageReaderResult("", url, "")
                    val json = JsonParser.parseString(responseBody).asJsonObject
                    val data = json.getAsJsonObject("output")?.getAsJsonObject("data") ?: json
                    PageReaderResult(
                        title = data.get("title")?.asString ?: "",
                        url = data.get("url")?.asString ?: url,
                        html = data.get("html")?.asString ?: "",
                    )
                }
            } catch (e: Exception) {
                PageReaderResult("", url, "")
            }
        }
}

data class WebSearchHit(
    val url: String,
    val title: String,
    val snippet: String,
    val host: String,
)

data class PageReaderResult(
    val title: String,
    val url: String,
    val html: String,
)
