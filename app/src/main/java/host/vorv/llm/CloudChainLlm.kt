package host.vorv.llm

/**
 * Cloud mode with more than one model behind it.
 *
 * The reported failure was not the key: Z.ai was overloaded, so every request
 * to the one selected model came back 429/5xx and the task died even though the
 * account was healthy and three other models it can serve existed. This wrapper
 * does what a person with several keys and one busy provider would do — try the
 * next model — and remembers which ones just failed so the following steps do
 * not walk into the same wall.
 *
 * Rules that keep it honest:
 *  - A 401/403/400 is NOT retried on another model: the request itself is
 *    wrong, and hopping models would hide that. [FallbackLlm.isAuthError]
 *    already rethrows those; this class does the same.
 *  - The label names every model in order, so a trace never claims "cloud"
 *    while quietly using a different model than the one picked in Setup.
 *  - When everything is busy the error names each model and its status. "All
 *    busy" is the truth; "something went wrong" is not.
 */
class CloudChainLlm(
    private val apiKey: String,
    models: List<CloudModel>,
    baseUrl: String = "https://api.z.ai/api/paas/v4",
    private val cooldownMs: Long = 60_000L,
    private val notEntitledCooldownMs: Long = 30 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val engineFor: (CloudModel) -> Llm = { CloudLlm(apiKey, baseUrl, it.id) },
) : Llm {

    private val ordered: List<CloudModel> = models.distinct().ifEmpty { listOf(CloudModel.GLM_4_7_FLASH) }
    private val engines: List<Llm> = ordered.map { engineFor(it) }
    private val coolingUntil = LongArray(ordered.size)

    override val label: String = ordered.joinToString(" → ") { it.displayName } + " (cloud)"

    /** Which model actually answered the most recent step — for the episode log. */
    @Volatile
    var lastModelUsed: String = ordered.first().displayName
        private set

    /** The reason of the last overload this chain had to route around, if any. */
    @Volatile
    var lastOverloadReason: String? = null
        private set

    override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse {
        val now = clock()
        val fresh = engines.indices.filter { coolingUntil[it] <= now }
        val order = fresh + engines.indices.filter { coolingUntil[it] > now }
        val failures = ArrayList<String>()

        for (i in order) {
            try {
                val response = engines[i].step(messages, tools)
                coolingUntil[i] = 0L
                lastModelUsed = ordered[i].displayName
                return response
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LlmHttpException) {
                if (e.notEntitled) {
                    // No credit for this model — the account is on the free
                    // tier. Skip it without treating it as an outage and
                    // without pretending the key is broken.
                    coolingUntil[i] = clock() + notEntitledCooldownMs
                    failures += "${ordered[i].displayName}: no credit (HTTP ${e.code})"
                    continue
                }
                if (e.unknownModel) {
                    // This id is retired or renamed. The next id in the chain may
                    // well be alive, so skip it rather than failing the request.
                    coolingUntil[i] = clock() + notEntitledCooldownMs
                    failures += "${ordered[i].displayName}: model id not recognised (HTTP ${e.code})"
                    lastOverloadReason =
                        "${ordered[i].displayName}: model id not recognised by the provider (HTTP ${e.code})"
                    continue
                }
                if (!e.overloaded) throw e // the request or key is wrong; another model will say the same
                coolingUntil[i] = clock() + cooldownMs
                failures += "${ordered[i].displayName}: ${e.message}"
                lastOverloadReason = "${ordered[i].displayName} was busy (HTTP ${e.code})"
            } catch (e: Exception) {
                // Network drop, timeout, malformed reply: the next model is a
                // genuine second chance, and the reason is recorded.
                failures += "${ordered[i].displayName}: ${e.message ?: e.javaClass.simpleName}"
                lastOverloadReason = "${ordered[i].displayName} failed (${e.message ?: "no detail"})"
            }
        }
        if (failures.isNotEmpty() && failures.all { it.contains("no credit") }) {
            throw RuntimeException(
                "Every model tried is a paid one and this account has no credit for it — " +
                    failures.joinToString("; ") +
                    ". Nothing is broken and no key is wrong: switch to GLM-4.7-Flash (free) " +
                    "or to the on-device model in Setup.",
            )
        }
        throw RuntimeException(
            "Z.ai did not serve any model just now — " + failures.joinToString("; ") +
                ". That is the provider being busy or unreachable, not your key. " +
                "Try again in a minute, or switch to the on-device model in Setup.",
        )
    }
}
