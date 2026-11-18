package host.vorv.llm.provider

/**
 * Remembers which provider answered, and how recently — so a task does not
 * re-walk a wall it already hit.
 *
 * This is deliberately small and deterministic: the router is a health table,
 * not a scheduler. It never invents a provider, never retries a request whose
 * *content* was refused (400/401/403), and always leaves the final choice with
 * the owner.
 */
class ProviderRouter(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cooldownMs: Long = 60_000L,
    private val noCreditCooldownMs: Long = 30 * 60_000L,
) {
    enum class Health { UNKNOWN, OK, BUSY, NO_CREDIT, BROKEN_KEY, NOT_FOUND, DEAD_ADDRESS }

    data class State(
        val health: Health = Health.UNKNOWN,
        val code: Int? = null,
        val detail: String = "",
        val until: Long = 0L,
        val wins: Int = 0,
        val losses: Int = 0,
    ) {
        val cooling: Boolean get() = until > 0L
    }

    private val states = LinkedHashMap<String, State>()

    fun state(id: String): State = states[id] ?: State()

    fun all(): Map<String, State> = states.toMap()

    /**
     * Records the outcome of one real call. `code` is the HTTP status, or null
     * for a transport failure. Returns the state after recording, so callers
     * can report the same truth they store.
     */
    fun record(id: String, code: Int?, errorText: String = ""): State {
        val now = clock()
        val prev = states[id] ?: State()
        val next = when {
            code == null -> prev.copy(
                health = Health.DEAD_ADDRESS,
                detail = errorText.ifBlank { "the address did not answer" },
                until = now + cooldownMs,
                losses = prev.losses + 1,
                code = null,
            )
            code in 200..299 -> prev.copy(health = Health.OK, detail = "", until = 0L, wins = prev.wins + 1, code = code)
            code == 429 -> prev.copy(
                health = Health.BUSY,
                detail = "the provider is rate-limiting this key",
                until = now + cooldownMs,
                losses = prev.losses + 1,
                code = code,
            )
            code in 500..599 -> prev.copy(
                health = Health.BUSY,
                detail = "the provider is having an outage (HTTP $code)",
                until = now + cooldownMs,
                losses = prev.losses + 1,
                code = code,
            )
            code == 401 || code == 403 -> prev.copy(
                health = Health.BROKEN_KEY,
                detail = "this provider refused the key (HTTP $code)",
                until = 0L,
                losses = prev.losses + 1,
                code = code,
            )
            code == 404 -> prev.copy(
                health = Health.NOT_FOUND,
                detail = "no such model at this provider (HTTP 404)",
                until = now + noCreditCooldownMs,
                losses = prev.losses + 1,
                code = code,
            )
            code == 402 -> prev.copy(
                health = Health.NO_CREDIT,
                detail = "this account has no credit for that model (HTTP 402)",
                until = now + noCreditCooldownMs,
                losses = prev.losses + 1,
                code = code,
            )
            else -> prev.copy(
                health = Health.BUSY,
                detail = errorText.ifBlank { "HTTP $code" },
                until = 0L,
                losses = prev.losses + 1,
                code = code,
            )
        }
        states[id] = next
        return next
    }

    fun forget(id: String) {
        states.remove(id)
    }

    fun clear() = states.clear()

    /**
     * Providers in the order worth trying: the chosen one first, then others
     * the owner has a key for and which are not cooling, then cooling ones.
     * Only providers with a key are ever returned — the router cannot invent
     * a credential.
     */
    fun order(chosen: String, candidates: List<Provider>, hasKey: (Provider) -> Boolean): List<Provider> {
        val now = clock()
        val live = candidates.filter { hasKey(it) }
        val first = live.firstOrNull { it.id == chosen }?.let { listOf(it) } ?: emptyList()
        val rest = live.filter { it.id != chosen }
        val (ready, cooling) = rest.partition { !(states[it.id]?.until ?: 0L).let { u -> u > now } }
        return first + ready.filter { states[it.id]?.health != Health.BROKEN_KEY } +
            ready.filter { states[it.id]?.health == Health.BROKEN_KEY } + cooling
    }
}
