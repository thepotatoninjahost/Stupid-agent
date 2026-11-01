package host.vorv.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single owner of the local LLM engine lifecycle (audit finding H-4).
 *
 * Before v0.6.0, three different code paths (model selection collector,
 * download completion, model deletion) could each call localLlm?.release()
 * while a task was mid-inference — releasing native memory out from under
 * a running generation. ModelRepository serializes every engine transition
 * behind one Mutex and refuses to swap while a task is running.
 */
class ModelRepository(private val appContext: Context) {

    private val mutex = Mutex()

    private var localLlm: LocalLlm? = null

    /** True while a kernel.run() is in flight. Set/cleared by AgentViewModel. */
    @Volatile
    var busy: Boolean = false
        private set

    /** Wired by the ViewModel so the UI shows real tok/s telemetry. */
    var onTelemetry: ((tokensPerSecond: Double, generateMs: Double) -> Unit)? = null

    /**
     * Returns the engine to use for a task. In local mode this lazily
     * (re)builds the LocalLlm if the selection changed since last use —
     * under the lock, so it can never race a concurrent swap.
     */
    suspend fun activeEngine(mode: String, apiKey: String, choice: ModelChoice): Llm = mutex.withLock {
        if (mode != "local") {
            require(apiKey.isNotBlank()) {
                "Cloud mode needs a Z.ai API key — add one in Setup."
            }
            return@withLock CloudLlm(apiKey)
        }
        val ready = ModelManager.isDownloaded(choice)
        require(ready) {
            "Local mode is selected but ${choice.displayName.substringBefore("(").trim()} isn't downloaded. " +
                "Open Setup → Download local model, or switch to Cloud."
        }
        val existing = localLlm
        if (existing != null && existing.modelId == choice.id) {
            return@withLock existing
        }
        existing?.release()
        val created = buildEngine(choice)
        localLlm = created
        created
    }

    /**
     * Switch the selected local model. Refuses to run while a task is
     * in flight (the UI also disables the controls, but the repository
     * enforces it — the UI is not a security boundary).
     */
    suspend fun swapModel(choice: ModelChoice) {
        mutex.withLock {
            check(!busy) {
                "A task is running — wait for it to finish before switching models."
            }
            val current = localLlm
            if (current != null && current.modelId == choice.id) return@withLock
            current?.release()
            localLlm = null
            if (ModelManager.isDownloaded(choice)) {
                localLlm = buildEngine(choice)
            }
        }
    }

    /**
     * Delete a downloaded model from disk. If it's the active engine,
     * release it first (but only when no task is running).
     */
    suspend fun deleteModel(choice: ModelChoice) {
        mutex.withLock {
            val current = localLlm
            if (current != null && current.modelId == choice.id) {
                check(!busy) {
                    "A task is running on this model — wait for it to finish before deleting it."
                }
                current.release()
                localLlm = null
            }
            ModelManager.remove(choice)
        }
    }

    /** Whether an engine is currently instantiated for the given choice. */
    fun hasEngineFor(choiceId: String): Boolean = localLlm?.modelId == choiceId

    /** Release native memory now (ViewModel.onCleared / process trim). */
    suspend fun shutdown() {
        mutex.withLock {
            localLlm?.release()
            localLlm = null
        }
    }

    /**
     * Memory-pressure hook. Only releases when no task is running; the next
     * local-mode task rebuilds the engine lazily via [activeEngine].
     */
    suspend fun onTrimMemory() {
        if (busy) {
            Log.i(TAG, "onTrimMemory: task running, keeping engine alive")
            return
        }
        shutdown()
        Log.i(TAG, "onTrimMemory: released local engine early")
    }

    fun markBusy(value: Boolean) {
        busy = value
    }

    private fun buildEngine(choice: ModelChoice): LocalLlm {
        val llm = LocalLlm(
            appContext,
            choice,
            displayLabel = "${choice.displayName.substringBefore("(").trim()} (NPU, offline)",
        )
        llm.onTelemetry = onTelemetry
        return llm
    }

    private companion object {
        const val TAG = "Vorv/ModelRepository"
    }
}
