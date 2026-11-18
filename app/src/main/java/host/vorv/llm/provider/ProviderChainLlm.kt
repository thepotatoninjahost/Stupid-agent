package host.vorv.llm.provider

import host.vorv.llm.CloudChainLlm
import host.vorv.llm.CloudLlm
import host.vorv.llm.Llm
import host.vorv.llm.LlmHttpException

/**
 * Gate 0: two independent providers, one of them broken, chat still answers.
 *
 * [CloudChainLlm] walks the models of ONE provider. This walks the providers
 * themselves, in the order [ProviderRouter] says is worth trying, so a dead
 * address, a refused key or an outage at the chosen provider is a detour
 * instead of the end of the task.
 *
 * What it refuses to hide:
 *  - A request the provider says is malformed (400/422 that is not about the
 *    model id) is rethrown: every provider would say the same, and hopping
 *    would turn the owner's bug into silence.
 *  - Every failure is recorded in the router with its HTTP code and sentence,
 *    and [lastFailure] keeps the reason for the header and the episode log.
 *  - When a later provider answers, [answeredBy] names it, so the label the
 *    header shows is the provider that actually spoke, never the chosen one.
 *  - When every provider fails, the error names each one and why. "Nothing
 *    answered" is never reported as "something went wrong".
 */
class ProviderChainLlm(
    routes: List<Route>,
    private val router: ProviderRouter = ProviderRouter(),
    private val engineFor: (Route) -> Llm = { route ->
        if (route.useModelChain) {
            CloudChainLlm(route.apiKey, listOf(host.vorv.llm.CloudModel.fromId(route.modelId)))
        } else {
            CloudLlm(apiKey = route.apiKey, baseUrl = route.baseUrl, modelName = route.modelId)
        }
    },
) : Llm {

    /**
     * One place a request can go. [label] is what the header shows, so it names
     * both the provider and the model: "OpenRouter · qwen/qwen3-coder".
     */
    data class Route(
        val providerId: String,
        val label: String,
        val baseUrl: String,
        val apiKey: String,
        val modelId: String,
        val useModelChain: Boolean = false,
    ) {
        /** The chosen route, or the first one when the chosen has no key. */
        val keyless: Boolean get() = apiKey.isBlank() || baseUrl.isBlank()
    }

    init {
        require(routes.isNotEmpty()) { "A provider chain needs at least one provider." }
    }

    private val ordered: List<Route> = routes.filter { !it.keyless }.ifEmpty { routes }

    private val engines: List<Llm> by lazy { ordered.map(engineFor) }

    /** Which provider answered the most recent step, named for a human. */
    @Volatile
    var answeredBy: String? = null
        private set

    /**
     * Called when a later provider answers, with its name and why the earlier
     * ones did not. The episode log and the header use it, so a detour is
     * visible instead of looking like the chosen provider answered.
     */
    @Volatile
    var onFailover: ((answeredBy: String, reason: String) -> Unit)? = null

    /** Why the last step had to move on, or null when the first provider answered. */
    @Volatile
    var lastFailure: String? = null
        private set

    override val label: String
        get() = answeredBy?.let { "$it (cloud)" } ?: "${ordered.first().label} (cloud)"

    /** The routes in the order they will be tried, for the header and the tests. */
    fun routeLabels(): List<String> = ordered.map { it.label }

    override suspend fun step(messages: List<Llm.Message>, tools: List<Llm.ToolSpec>): Llm.LlmResponse {
        val failures = ArrayList<String>()
        var lastCode: Int? = null

        for (i in ordered.indices) {
            val route = ordered[i]
            try {
                val response = engines[i].step(messages, tools)
                router.record(route.providerId, 200)
                answeredBy = route.label
                lastFailure = if (failures.isEmpty()) null else failures.joinToString("; ")
                if (failures.isNotEmpty()) {
                    val reason = failures.joinToString("; ")
                    host.vorv.tools.Attempt.of("tell the owner which provider answered") {
                        onFailover?.invoke(route.label, reason)
                    }
                }
                return response
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LlmHttpException) {
                router.record(route.providerId, e.code, e.message ?: "")
                lastCode = e.code
                if (!worthAnotherProvider(e)) {
                    lastFailure = "${route.label}: ${e.message}"
                    throw e
                }
                failures += "${route.label}: ${reasonFor(e)}"
            } catch (e: Exception) {
                router.record(route.providerId, null, e.message ?: "")
                lastCode = null
                failures += "${route.label}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        val detail = failures.joinToString("; ")
        lastFailure = detail
        answeredBy = null
        throw RuntimeException(
            "No provider could answer just now${
                lastCode?.let { " (last status HTTP $it)" } ?: ""
            }: $detail. " +
                "That is the providers being unreachable, busy or out of credit — not your workspace. " +
                "Every provider and its reason is named above.",
        )
    }

    /**
     * Whether the same request sent to a different company is a genuine second
     * chance. A wrong key at provider A says nothing about provider B; a
     * malformed request is wrong everywhere, so it is not retried.
     */
    private fun worthAnotherProvider(e: LlmHttpException): Boolean = when {
        e.code == 401 || e.code == 403 -> true // this key, not this request
        e.code == 402 -> true // no credit here; another provider may serve it
        e.code == 404 -> true // this provider has no such model id
        e.unknownModel -> true // 400 naming a model: another provider may have it
        e.overloaded -> true // 429 or 5xx
        else -> false
    }

    companion object {
        /**
         * The chosen provider first, then every other provider the owner holds
         * a key for. Only real keys are used — the chain cannot invent a
         * credential — and the order after the first is the ship list, which is
         * strongest-coder-first.
         */
        fun routes(
            chosen: Provider,
            chosenBaseUrl: String,
            chosenModelId: String,
            chosenUsesModelChain: Boolean,
            keyFor: (Provider) -> String,
            modelFor: (Provider) -> String = { it.suggestions.firstOrNull() ?: "" },
            others: List<Provider> = ProviderRegistry.presets,
        ): List<Route> {
            val first = Route(
                providerId = chosen.id,
                label = if (chosenModelId.isBlank()) chosen.label else "${chosen.label} · $chosenModelId",
                baseUrl = chosenBaseUrl,
                apiKey = keyFor(chosen),
                modelId = chosenModelId,
                useModelChain = chosenUsesModelChain,
            )
            val rest = others
                .filter { it.id != chosen.id }
                .mapNotNull { p ->
                    val key = keyFor(p)
                    val model = modelFor(p)
                    if (key.isBlank() || model.isBlank() || p.baseUrl.isBlank()) return@mapNotNull null
                    Route(
                        providerId = p.id,
                        label = "${p.label} · $model",
                        baseUrl = p.baseUrl,
                        apiKey = key,
                        modelId = model,
                    )
                }
            return listOf(first) + rest
        }
    }

    private fun reasonFor(e: LlmHttpException): String = when {
        e.code == 401 || e.code == 403 -> "refused the key (HTTP ${e.code})"
        e.code == 402 -> "no credit for this model (HTTP 402)"
        e.code == 404 || e.unknownModel -> "does not serve that model id (HTTP ${e.code})"
        else -> "failed (HTTP ${e.code})"
    }
}
