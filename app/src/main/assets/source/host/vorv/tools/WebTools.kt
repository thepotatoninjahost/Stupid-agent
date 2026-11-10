package host.vorv.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import host.vorv.BuildConfig
import host.vorv.data.SecretsStore
import kotlinx.coroutines.CancellationException
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
        "Requires approval of the destination and query before sending."

    override val timeoutMs: Long get() = 90_000L
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

    // Approval covers one destination only; redirects require a separate request.
    private val client: OkHttpClient = (client ?: defaultClient()).newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

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

            if (!approveWebRequest(ctx, req.url.toString(), body)) {
                return@withContext ToolResult(ok = false, output = "",
                    error = "Web request denied: explicit user approval is required.",
                    durationMs = System.currentTimeMillis() - t0)
            }
            client.newCall(req).execute().use { res ->
                val responseBody = res.body.string()
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
        } catch (e: CancellationException) {
            throw e
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
 *  - private/loopback/link-local egress blocked for literals and DNS results on every redirect
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
        "Private/localhost addresses are blocked. Requires approval of the full URL before sending; each redirect requires separate approval."

    override val timeoutMs: Long get() = 120_000L
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

    private val client: OkHttpClient = (client ?: publicWebFetchClient()).newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

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
            val response = approvedFetch(req, ctx)
            response.use { res ->
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

                val raw = readCapped(res.body.byteStream(), MAX_BYTES)
                val isHtml = contentType.contains("html") ||
                    (contentType.isBlank() && raw.trimStart().startsWith("<"))
                val clean = if (isHtml) htmlToText(raw) else raw
                ToolResult(
                    ok = true,
                    // M-2 (code review, 2026-11): the 8 000-char cap now
                    // announces itself — a silently cut page reads as the
                    // COMPLETE text to the model.
                    output = capWithMarker(clean, MAX_OUTPUT_CHARS),
                    durationMs = System.currentTimeMillis() - t0,
                    citation = Citation("web", res.request.url.toString(), "url"),
                )
            }
        } catch (e: IOException) {
            ToolResult(ok = false, output = "", error = "web_fetch failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "web_fetch failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }

    private suspend fun approvedFetch(initial: Request, ctx: ToolContext): okhttp3.Response {
        var request = initial
        repeat(21) { hop ->
            if (!approveWebRequest(ctx, request.url.toString())) {
                throw IOException("Web request denied: explicit user approval is required.")
            }
            val response = client.newCall(request).execute()
            if (response.code !in setOf(300, 301, 302, 303, 307, 308)) return response
            request = response.use {
                if (hop == 20) throw IOException("Too many redirects")
                val target = response.header("Location")?.let { request.url.resolve(it) }
                    ?: throw IOException("Invalid redirect destination")
                request.newBuilder().url(target).build()
            }
        }
        error("Unreachable redirect state")
    }

    /** Streams at most [MAX_BYTES] bytes as UTF-8 text — see the top-level
     *  [readCappedUtf8] (M4) for the byte-true budget and tail trimming. */
    private fun readCapped(stream: java.io.InputStream?, maxBytes: Long): String =
        readCappedUtf8(stream, maxBytes)

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

        /** M-2: 8 000 before, same number now — with the honest marker. */
        const val MAX_OUTPUT_CHARS = 8_000
    }
}

/**
 * M4 (code review, 2026-11): streams at most [maxBytes] actual BYTES as
 * UTF-8 text. The previous loop counted decoded CHARS against the byte
 * budget, so multi-byte content (CJK, emoji) pulled up to 4x the nominal
 * cap through the network before stopping. A cap that splits a multi-byte
 * character mid-sequence is trimmed by [trimPartialUtf8Tail] so the text
 * never ends in U+FFFD. Top-level internal + pure so the byte-budget
 * behaviour is unit-testable.
 */
internal fun readCappedUtf8(stream: java.io.InputStream?, maxBytes: Long): String {
    if (stream == null) return ""
    stream.use { s ->
        val bos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (total < maxBytes) {
            val n = s.read(buf, 0, minOf(buf.size.toLong(), maxBytes - total).toInt())
            if (n <= 0) break
            total += n
            bos.write(buf, 0, n)
        }
        val truncated = total >= maxBytes && s.read() != -1
        val decoded = trimPartialUtf8Tail(bos.toByteArray()).toString(Charsets.UTF_8)
        return if (truncated) {
            decoded + "\n…(truncated at $maxBytes bytes)"
        } else decoded
    }
}

/**
 * M4 (code review, 2026-11): drops a trailing INCOMPLETE UTF-8 sequence (a
 * lead byte whose continuation bytes fell past the byte cap) so a capped
 * read never ends mid-character.
 */
internal fun trimPartialUtf8Tail(bytes: ByteArray): ByteArray {
    for (i in 1..minOf(4, bytes.size)) {
        val b = bytes[bytes.size - i].toInt() and 0xFF
        if (b and 0xC0 == 0x80) continue // continuation byte — keep looking left
        val expected = when {
            b and 0xF8 == 0xF0 -> 4
            b and 0xF0 == 0xE0 -> 3
            b and 0xE0 == 0xC0 -> 2
            else -> 1
        }
        // Lead byte present but fewer than `expected` bytes remain →
        // the sequence is incomplete: cut it off entirely.
        return if (i < expected) bytes.copyOf(bytes.size - i) else bytes
    }
    // Degenerate all-continuation tail — leave it; the decoder will do the
    // honest replacement-char thing.
    return bytes
}

/** Guard every fetch destination before OkHttp can open a connection, including redirects. */
internal fun publicWebFetchClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .dns(NetworkGuard.PublicDns)
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
        NetworkGuard.checkLiteralHostAllowed(chain.request().url.host)
        chain.proceed(chain.request())
    }
    .build()
/**
 * Memory is available even in the initial task prompt, so every model-selected
 * web request needs consent, regardless of tool order or apparent URL contents.
 * Show the complete payload, JSON escaped, rather than model-authored rationale.
 */
private suspend fun approveWebRequest(ctx: ToolContext, url: String, body: String? = null): Boolean {
    val disclosure = JsonObject().apply {
        addProperty("url", url)
        if (body != null) addProperty("body", body)
    }
    return ctx.confirm?.invoke(
        "Allow sending this web request? The URL and search data may contain private memory. " +
            "Approve only if you authorize disclosing the data below to this destination.\n$disclosure"
    ) == true
}
