package host.vorv.tools

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
 * web_search — real web search.
 * Uses Z.ai's web_search function (cloud mode only — requires API key).
 * Returns list of {url, title, snippet}.
 */
class WebSearchTool(private val apiKeyProvider: () -> String) : Tool {
    override val name = "web_search"
    override val description = "Search the live web. Returns top results with URLs, titles, and snippets. Use to answer questions about current events or facts you don't know."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("query", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Search query.")
            })
            add("num", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "Max results. Default 5.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("query") })
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val query = input.get("query")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing query", durationMs = System.currentTimeMillis() - t0)
        val num = input.get("num")?.takeIf { !it.isJsonNull }?.asInt ?: 5

        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            return@withContext ToolResult(
                ok = false,
                output = "",
                error = "No Z.ai API key set. Add one in Setup tab to use web search.",
                durationMs = System.currentTimeMillis() - t0,
            )
        }

        // Z.ai function invocation format
        val body = JsonObject().apply {
            addProperty("name", "web_search")
            add("input", JsonObject().apply {
                addProperty("query", query)
                addProperty("num", num)
            })
        }.toString()

        try {
            val req = Request.Builder()
                .url("https://api.z.ai/api/paas/v4/functions")
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
                        error = "HTTP ${res.code}: ${responseBody.take(200)}",
                        durationMs = System.currentTimeMillis() - t0,
                    )
                }
                val json = JsonParser.parseString(responseBody).asJsonObject
                val results = json.getAsJsonArray("output")
                    ?: return@withContext ToolResult(
                        ok = true,
                        output = "No results for '$query'.",
                        durationMs = System.currentTimeMillis() - t0,
                    )

                if (results.size() == 0) {
                    return@withContext ToolResult(
                        ok = true,
                        output = "No results for '$query'.",
                        durationMs = System.currentTimeMillis() - t0,
                    )
                }

                val sb = StringBuilder()
                val firstUrl = results[0].asJsonObject.get("url")?.asString ?: ""
                results.forEach { r ->
                    val obj = r.asJsonObject
                    val url = obj.get("url")?.asString ?: ""
                    val title = obj.get("name")?.asString ?: obj.get("title")?.asString ?: ""
                    val snippet = obj.get("snippet")?.asString ?: ""
                    sb.appendLine("• $title")
                    sb.appendLine("  URL: $url")
                    sb.appendLine("  $snippet")
                }
                ToolResult(
                    ok = true,
                    output = sb.toString().trim(),
                    durationMs = System.currentTimeMillis() - t0,
                    citation = if (firstUrl.isNotBlank()) Citation("web", firstUrl, "web") else null,
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "web_search failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
        }
    }
}

/**
 * web_fetch — fetch a URL and return clean text content.
 * Strips HTML tags but preserves text structure.
 */
class WebFetchTool : Tool {
    override val name = "web_fetch"
    override val description = "Fetch a URL and return its text content. HTML stripped. Use after web_search to read full page content."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("url", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "URL to fetch.")
            })
        })
        add("required", com.google.gson.JsonArray().apply { add("url") })
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val url = input.get("url")?.asString
            ?: return@withContext ToolResult(ok = false, output = "", error = "missing url", durationMs = System.currentTimeMillis() - t0)

        try {
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "Vorv/0.2 (Android; local-first agent)")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext ToolResult(ok = false, output = "", error = "HTTP ${res.code}", durationMs = System.currentTimeMillis() - t0)
                }
                val raw = res.body?.string().orEmpty()
                val clean = htmlToText(raw).take(8000)
                ToolResult(
                    ok = true,
                    output = clean,
                    durationMs = System.currentTimeMillis() - t0,
                    citation = Citation("web", url, "url"),
                )
            }
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "web_fetch failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
        }
    }

    /**
     * Lightweight HTML-to-text: removes scripts/styles, strips tags, collapses whitespace.
     */
    private fun htmlToText(html: String): String {
        var out = html
        // Remove script and style blocks entirely
        out = Regex("""(?is)<script.*?>.*?</script>""").replace(out, "")
        out = Regex("""(?is)<style.*?>.*?</style>""").replace(out, "")
        out = Regex("""(?is)<head.*?>.*?</head>""").replace(out, "")
        // Block-level tags → newline
        out = Regex("""(?i)</(p|div|h[1-6]|li|tr|br|blockquote|pre)>""").replace(out, "\n")
        out = out.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
        // Strip all remaining tags
        out = Regex("""(?s)<[^>]+>""").replace(out, "")
        // Decode common entities
        out = out.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        // Collapse whitespace
        out = out.replace(Regex("""\n\s*\n+"""), "\n\n").replace(Regex("""[ \t]+"""), " ")
        return out.trim()
    }
}
