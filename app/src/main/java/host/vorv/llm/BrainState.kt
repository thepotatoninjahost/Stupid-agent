package host.vorv.llm

import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * The live set of facts the brains publish, and the only place they are read.
 *
 * Anything that learns something about a brain — an HTTP reply, a failed
 * engine build, the compute unit that actually ran — writes it here, so the
 * header, the send gate, the Setup health card and the agent's own episode log
 * all describe the same reality. A restart keeps the last value.
 */
object BrainState {

    private val gson = Gson()
    private val _facts = MutableStateFlow(BrainFacts())
    val facts: StateFlow<BrainFacts> = _facts.asStateFlow()

    /** Persistence is injected (prefs on device, no-op in tests). */
    private val saver = AtomicReference<((String) -> Unit)?>(null)
    private val loader = AtomicReference<(() -> String?)?>(null)

    fun attachStore(save: (String) -> Unit, load: () -> String?) {
        saver.set(save)
        loader.set(load)
        load()?.let { json ->
            runCatching { gson.fromJson(json, BrainFacts::class.java) }
                .getOrNull()
                ?.let { restored ->
                    // Device facts are re-measured every boot; the rest is history.
                    _facts.value = restored.copy(
                        localBytes = 0,
                        localExpectedBytes = 0,
                        freeStorageBytes = -1,
                        abis = emptyList(),
                    )
                }
        }
    }

    fun update(block: (BrainFacts) -> BrainFacts) {
        val next = _facts.updateAndGet { block(it) }
        saver.get()?.let { save -> runCatching { save(gson.toJson(next)) } }
    }

    fun recordProbe(e: BrainFacts.Exchange) = update { it.copy(lastProbe = e) }

    fun recordExchange(e: BrainFacts.Exchange) = update { it.copy(lastExchange = e) }

    /** Clears the history a stale failure would otherwise keep asserting. */
    fun forgetFailures() = update { it.copy(lastProbe = null, lastExchange = null, lastRuntimeError = null) }

    /** Tests only: back to a blank slate. */
    fun resetForTest(facts: BrainFacts = BrainFacts()) {
        _facts.value = facts
    }
}
