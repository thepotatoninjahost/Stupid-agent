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
    // L-2: the guarded client is the default, not something one call site
    // remembers to pass. Redirects stay off for the request below.
    client: OkHttpClient = OkHttpClient.Builder()
        .dns(host.vorv.tools.NetworkGuard.PublicDns)
        .followRedirects(false)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
    private val ttlMs: Long = 24 * 60 * 60 * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Entry(
        val id: String,
        val contextLength: Long? = null,
        val price: String? = null,
        /** Both prices are "0": the owner can use it without paying. */
        val free: Boolean = false,
    )
    data class Listing(val providerId: String, val entries: List<Entry>, val fetchedAt: Long, val error: String? = null)

    // H-5: the listing carries the key in a header, so it must not follow a
    // redirect to another host either — not even on an injected client.
    private val client: OkHttpClient = client.newBuilder().followRedirects(false).build()

    private val cache = ConcurrentHashMap<String, Listing>()

    companion object {
        /** Ceiling for one `GET /models` body. */
        internal const val MAX_LISTING_BYTES = 2_000_000
    }

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
                // A provider list is a few KB; a server that streams megabytes
                // must not be able to fill the app's heap before it is parsed.
                val body = host.vorv.llm.CloudLlm.readBounded(res.body, MAX_LISTING_BYTES)
                if (res.code in 300..399) {
                    val location = res.header("Location") ?: "(no Location header)"
                    Listing(
                        provider.id,
                        emptyList(),
                        clock(),
                        error = "provider redirected the request to $location — redirects are refused",
                    )
                } else if (!res.isSuccessful) {
                    Listing(
                        provider.id,
                        emptyList(),
                        clock(),
                        error = "HTTP ${res.code} from $url: ${body.take(160)}",
                    )
                } else {
                    val entries = parseStrict(body)
                    if (entries == null) {
                        Listing(
                            provider.id,
                            emptyList(),
                            clock(),
                            error = "HTTP ${res.code} from $url, but the body is not a model list " +
                                "(${body.length} chars read): ${body.take(160)}",
                        )
                    } else {
                        Listing(provider.id, entries, clock())
                    }
                }
            }
        } catch (e: Exception) {
            Listing(provider.id, emptyList(), clock(), error = "${e.javaClass.simpleName}: ${e.message}")
        }
        cache[provider.id] = listing
        return listing
    }

    /**
     * Parses both `{"data":[{"id":…}]}` (OpenAI shape) and a bare array, and
     * answers null when [body] is not a list this app can read. It used to
     * answer an empty list instead: a body cut at the byte ceiling, or a
     * proxy's HTML error page, then reached Setup as "the provider serves no
     * models" — a claim with no evidence behind it. [Listing] carries the
     * error line instead.
     *
     * `{"data":null}` and `{}` are readable lists that happen to be empty.
     * An object that carries neither (`{"error":{…}}`, `{"data":"unavailable"}`)
     * is a failure, and answers null.
     */
    fun parseStrict(body: String): List<Entry>? = runCatching {
        val root = JsonParser.parseString(body)
        // A recognised envelope with no list in it is an empty catalogue (`{}`,
        // `{"data":null}` are both answers). Anything that is not an envelope at
        // all — an HTML error page, a body cut mid-object — is null, so the
        // caller says what happened instead of "this provider serves nothing".
        val arr = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val obj = root.asJsonObject
                val member = listOf("data", "models").firstOrNull { obj.get(it) != null }
                val v = member?.let { obj.get(it) }
                when {
                    // `{}` is an empty answer; anything else without a list is
                    // not a catalogue at all, so it must not read as "serves
                    // no models".
                    v == null -> if (obj.size() == 0) emptyList() else return@runCatching null
                    v.isJsonNull -> emptyList()
                    v.isJsonArray -> v.asJsonArray
                    // `{"error":{…}}`, `{"data":"unavailable"}`: a failure the
                    // caller must report, not a fresh empty listing.
                    else -> return@runCatching null
                }
            }
            else -> return@runCatching null
        }
        arr.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString
                ?: o.get("name")?.takeIf { !it.isJsonNull }?.asString
                ?: return@mapNotNull null
            // A model the owner can never afford must not be offered as the
            // default or as a rescue candidate. `pricing` is absent on some
            // providers, so "unknown" is not "free".
            val pricing = o.get("pricing")?.takeIf { it.isJsonObject }?.asJsonObject
            fun zero(key: String): Boolean =
                pricing?.get(key)?.takeIf { !it.isJsonNull }?.asString?.trim()?.let { it == "0" || it == "0.0" } == true
            Entry(
                id,
                contextLength = o.get("context_length")?.takeIf { !it.isJsonNull }
                    ?.let { host.vorv.tools.Attempt.of("reading a model's context_length") { it.asLong } },
                price = null,
                free = zero("prompt") && zero("completion"),
            )
        }.distinctBy { it.id }.sortedBy { it.id }
    }.getOrNull()

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
            // Equal word hits are common ("model 4.7" also matches "model 4.7 flash");
            // the closer the length, the more likely it is what was meant.
            Triple(e.id, score, -kotlin.math.abs(hay.length - typedLength))
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
            .take(limit)
            .map { it.first }
    }
}
