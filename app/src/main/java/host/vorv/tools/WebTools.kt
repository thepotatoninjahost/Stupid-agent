package host.vorv.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import host.vorv.BuildConfig
import host.vorv.data.SecretsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * web_search — real web search via Z.ai's documented Web Search API
 * (audit finding I-3: the old code called an undocumented /functions
 * endpoint that does not exist in Z.ai's public API).
 *
 * Documented contract (docs.z.ai → API reference → Web Search):
 *   POST https://api.z.ai/api/paas/v4/web_search
 *   {"search_engine": "search-prime", "search_query": "...", "count": N}
 *   → {"search_result": [{"title","content","link","media","publish_date",...}]}
 */
class WebSearchTool(
    private val secrets: SecretsStore,
    private val baseUrl: String = "https://api.z.ai/api/paas/v4",
    client: OkHttpClient? = null,
) : Tool {
    override val name = "web_search"
    override val description =
        "Search the live web via Z.ai's Web Search API. Returns results with URL, " +
        "title, and a content snippet. Requires a Z.ai API key (Setup tab) and internet. " +
        "Use to answer questions about current events or facts you don't know."

    override val timeoutMs: Long get() = 30_000L
    override val maxCallsPerTask: Int get() = 10

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("query", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Search query.")
            })
            add("num", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Max results, 1-20. Default 5.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("query") })
    }

    private val client: OkHttpClient = client ?: defaultClient()

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val query = input.get("query")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing query",
                durationMs = System.currentTimeMillis() - t0)
        val num = (input.get("num")?.takeIf { !it.isJsonNull }?.asInt ?: 5).coerceIn(1, 20)

        val apiKey = secrets.zaiApiKey.first()
        if (apiKey.isBlank()) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "No Z.ai API key set. Add one in Setup tab to use web search.",
                durationMs = System.currentTimeMillis() - t0,
            )
        }

        val body = JsonObject().apply {
            addProperty("search_engine", "search-prime")
            addProperty("search_query", query)
            addProperty("count", num)
        }.toString()

        try {
            val req = Request.Builder()
                .url("$baseUrl/web_search")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept-Language", "en-US,en")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { res ->
                val responseBody = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    return@withContext ToolResult(
                        ok = false,
                        output = "",
                        error = "web_search HTTP ${res.code}: ${responseBody.take(200)}",
                        durationMs = System.currentTimeMillis() - t0,
                    )
                }
                val json = JsonParser.parseString(responseBody).asJsonObject
                val results = json.getAsJsonArray("search_result")
                if (results == null || results.size() == 0) {
                    return@withContext ToolResult(
                        ok = true,
                        output = "No results for '$query'.",
                        durationMs = System.currentTimeMillis() - t0,
                    )
                }

                val sb = StringBuilder()
                var firstUrl = ""
                results.forEach { r ->
                    val obj = r.asJsonObject
                    val link = obj.get("link")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val media = obj.get("media")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val date = obj.get("publish_date")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    if (firstUrl.isBlank()) firstUrl = link
                    val meta = listOfNotNull(
                        media.takeIf { it.isNotBlank() },
                        date.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    sb.appendLine("• $title" + if (meta.isNotBlank()) " — $meta" else "")
                    sb.appendLine("  URL: $link")
                    if (content.isNotBlank()) sb.appendLine("  ${content.take(400)}")
                }
                ToolResult(
                    ok = true,
                    output = sb.toString().trim(),
                    durationMs = System.currentTimeMillis() - t0,
                    citation = if (firstUrl.isNotBlank()) Citation("web", firstUrl, "web") else null,
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "web_search failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dns(NetworkGuard.PublicDns)
        .build()
}

/**
 * web_fetch — fetch a URL and return clean text content.
 *
 * v0.6.0 hardening:
 *  - response size capped (2 MB streamed; Content-Length precheck) — the old
 *    res.body?.string() could OOM the process on a huge body (audit H-8)
 *  - private/loopback/link-local egress blocked via NetworkGuard.PublicDns
 *    (SSRF guard: no localhost, LAN, or metadata endpoints)
 *  - content-type allowlist — binary bodies are refused with an honest error
 *  - HTML-to-text via jsoup instead of regex (audit L-4)
 *  - versioned User-Agent (audit I-4)
 */
class WebFetchTool(client: OkHttpClient? = null) : Tool {
    override val name = "web_fetch"
    override val description =
        "Fetch an http/https URL and return its text content (HTML stripped via jsoup). " +
        "Text and JSON content types only; responses over 2 MB are refused. " +
        "Private/localhost addresses are blocked. Use after web_search to read page content."

    override val timeoutMs: Long get() = 45_000L
    override val maxCallsPerTask: Int get() = 8

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("url", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "URL to fetch (http or https).")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("url") })
    }

    private val client: OkHttpClient = client ?: defaultClient()

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val url = input.get("url")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing url",
                durationMs = System.currentTimeMillis() - t0)

        val scheme = url.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") {
            return@withContext ToolResult(ok = false, output = "",
                error = "Refusing URL with scheme '$scheme' — only http/https are allowed.",
                durationMs = System.currentTimeMillis() - t0)
        }

        try {
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Vorv/${BuildConfig.VERSION_NAME} (Android; local-first agent)")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext ToolResult(ok = false, output = "",
                        error = "HTTP ${res.code}",
                        durationMs = System.currentTimeMillis() - t0)
                }
                // Precheck declared size before buffering anything.
                val contentLength = res.header("Content-Length")?.toLongOrNull() ?: -1
                if (contentLength > MAX_BYTES) {
                    return@withContext ToolResult(ok = false, output = "",
                        error = "Response too large ($contentLength bytes, cap $MAX_BYTES) — refusing to fetch.",
                        durationMs = System.currentTimeMillis() - t0)
                }
                val contentType = res.header("Content-Type")?.lowercase() ?: ""
                if (contentType.isNotBlank() && !isTextual(contentType)) {
                    return@withContext ToolResult(ok = false, output = "",
                        error = "Refusing non-textual content type '$contentType'.",
                        durationMs = System.currentTimeMillis() - t0)
                }

                val raw = readCapped(res.body?.byteStream(), MAX_BYTES)
                val isHtml = contentType.contains("html") ||
                    (contentType.isBlank() && raw.trimStart().startsWith("<"))
                val clean = if (isHtml) htmlToText(raw) else raw
                ToolResult(
                    ok = true,
                    output = clean.take(8000),
                    durationMs = System.currentTimeMillis() - t0,
                    citation = Citation("web", url, "url"),
                )
            }
        } catch (e: IOException) {
            ToolResult(ok = false, output = "", error = "web_fetch failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "web_fetch failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dns(NetworkGuard.PublicDns)
        .build()

    /** Streams at most [maxBytes] as UTF-8 text (audit H-8, M-6). */
    private fun readCapped(stream: java.io.InputStream?, maxBytes: Long): String {
        if (stream == null) return ""
        val sb = StringBuilder()
        stream.use { s ->
            // Audit M-6: decode through an InputStreamReader so multi-byte
            // UTF-8 sequences split across 16 KB chunk boundaries are decoded
            // incrementally, instead of becoming U+FFFD replacement chars per
            // chunk (the old per-chunk String(buf, 0, n, UTF_8)).
            val reader = java.io.InputStreamReader(
                java.io.BufferedInputStream(s), Charsets.UTF_8)
            var total = 0L
            val cbuf = CharArray(16 * 1024)
            while (total < maxBytes) {
                val n = reader.read(cbuf)
                if (n <= 0) break
                total += n
                sb.append(cbuf, 0, n)
            }
            if (total >= maxBytes) sb.append("\n…(truncated at $maxBytes chars)")
        }
        return sb.toString()
    }

    private fun isTextual(contentType: String): Boolean =
        contentType.startsWith("text/") ||
            contentType in setOf(
                "application/json", "application/xml", "application/xhtml+xml",
                "application/javascript", "application/x-yaml", "application/rss+xml",
                "application/atom+xml",
            ) ||
            contentType.endsWith("+xml") || contentType.endsWith("+json")

    /**
     * jsoup-based HTML → text: removes script/style/head, converts block
     * boundaries to newlines via a marker that survives text() whitespace
     * collapsing, then restores them (replaces the old regex approach —
     * audit L-4).
     */
    private fun htmlToText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script,style,noscript,template,head").remove()
        val marker = "⟦NL⟧"
        doc.select("br").append(marker)
        doc.select("p,div,h1,h2,h3,h4,h5,h6,li,tr,blockquote,pre,section,article,header,footer")
            .append(marker + marker)
        val text = doc.body()?.text() ?: doc.text()
        return text
            .replace(marker + marker, "\n\n")
            .replace(marker, "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private companion object {
        const val MAX_BYTES = 2_000_000L
    }
}
