package host.vorv.llm.provider

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * What a provider actually serves, asked instead of guessed.
 *
 * `CloudModel.entries` was compiled in, so Setup could offer a model the
 * provider had retired, and the probe reported "(empty reply, HTTP 200)" as
 * success. The list now comes from `GET {base}/models`, is cached per provider
 * for a day, and a chosen id that is not in it is badged "not in the list".
 */
class ModelCatalog(
    private val client: OkHttpClient = OkHttpClient(),
    private val ttlMs: Long = 24 * 60 * 60 * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Entry(val id: String, val contextLength: Long? = null, val price: String? = null)
    data class Listing(val providerId: String, val entries: List<Entry>, val fetchedAt: Long, val error: String? = null)

    private val cache = ConcurrentHashMap<String, Listing>()

    fun cached(providerId: String): Listing? = cache[providerId]

    internal fun rememberForTest(listing: Listing) { cache[listing.providerId] = listing }

    fun isFresh(providerId: String): Boolean =
        cache[providerId]?.let { it.error == null && clock() - it.fetchedAt < ttlMs } == true

    /** Fetches and caches. A failure is a Listing with [Listing.error], never an empty "success". */
    suspend fun fetch(provider: Provider, apiKey: String): Listing {
        val url = ProviderRegistry.modelsUrl(provider.baseUrl, provider.modelsPath)
        val listing = try {
            val req = Request.Builder()
                .url(url)
                .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${apiKey.trim()}") }
                .addHeader("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    Listing(
                        provider.id,
                        emptyList(),
                        clock(),
                        error = "HTTP ${res.code} from $url: ${body.take(160)}",
                    )
                } else {
                    Listing(provider.id, parse(body), clock())
                }
            }
        } catch (e: Exception) {
            Listing(provider.id, emptyList(), clock(), error = "${e.javaClass.simpleName}: ${e.message}")
        }
        cache[provider.id] = listing
        return listing
    }

    /** Parses both `{"data":[{"id":…}]}` (OpenAI shape) and a bare array. */
    fun parse(body: String): List<Entry> = runCatching {
        val root = JsonParser.parseString(body)
        val arr = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("data")
                ?: root.asJsonObject.getAsJsonArray("models")
            else -> null
        } ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString
                ?: o.get("name")?.takeIf { !it.isJsonNull }?.asString
                ?: return@mapNotNull null
            Entry(id, contextLength = null, price = null)
        }.distinctBy { it.id }.sortedBy { it.id }
    }.getOrDefault(emptyList())

    /**
     * When the owner types a display name instead of an id ("NVIDIA: Nemotron 3
     * Ultra"), Setup must show what is close instead of saving a name the API
     * will answer 404 for.
     */
    fun closest(typed: String, entries: List<Entry>, limit: Int = 3): List<String> {
        if (typed.isBlank()) return emptyList()
        val needle = typed.lowercase().replace(":", " ").replace("-", " ").replace("_", " ").replace("/", " ")
            .split(" ").filter { it.length > 2 }
        if (needle.isEmpty()) return emptyList()
        val typedLength = needle.joinToString(" ").length
        return entries.map { e ->
            val hay = e.id.lowercase().replace(":", " ").replace("-", " ").replace("_", " ").replace("/", " ")
            val score = needle.count { hay.contains(it) }
            // Equal word hits are common ("glm 4.7" also matches "glm 4.7 flash");
            // the closer the length, the more likely it is what was meant.
            Triple(e.id, score, -kotlin.math.abs(hay.length - typedLength))
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
            .take(limit)
            .map { it.first }
    }
}
