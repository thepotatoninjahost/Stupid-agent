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
     *
     * v0.7.0 cloud mode: builds the SELECTED [CloudModel] (free tier by
     * default) and, when autoFallback is armed and the local model is on
     * disk, wraps it in [FallbackLlm] — cloud first, offline backup per
     * step, so a mid-task signal drop degrades instead of dying. The local
     * engine is built/reused under the same mutex (H-4 discipline).
     */
    suspend fun activeEngine(
        mode: String,
        apiKey: String,
        choice: ModelChoice,
        cloudModel: CloudModel = CloudModel.GLM_4_7_FLASH,
        autoFallback: Boolean = true,
    ): Llm = mutex.withLock {
        if (mode != "local") {
            require(apiKey.isNotBlank()) {
                "Cloud mode needs a Z.ai API key — add one in Setup."
            }
            val cloud = CloudLlm(apiKey, model = cloudModel.id)
            if (!autoFallback || !ModelManager.isDownloaded(choice)) {
                return@withLock cloud
            }
            val local = localLlm?.takeIf { it.modelId == choice.id }
                ?: run {
                    // Different model resident (or none): release the old
                    // native engine before building the new one — no leak.
                    localLlm?.release()
                    buildEngine(choice).also { localLlm = it }
                }
            FallbackLlm(cloud, local)
        } else {
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

    /**
     * M-3 (code review, 2026-11): single-owner acquisition of the REPAIR
     * engine (self_fix / crash-dialog self-repair / crash analysis).
     *
     * Before this, every repair site constructed its own
     * `LocalLlm(appContext, bestRepairModel(), …)` DIRECTLY — outside this
     * repository, outside the mutex. Two consequences:
     *
     *  1. Lifecycle discipline (audit H-4: "ModelRepository serializes every
     *     engine transition behind one Mutex") silently had a hole: repair
     *     engines were born and died outside the ledger.
     *  2. Wasted NPU memory: when the resident engine IS the strongest
     *     repair model (local mode with the 7B selected), the repair path
     *     STILL built a SECOND native engine for the same weights — two
     *     GenieX instances on the NPU at once, exactly the doubling the
     *     crash-dialog busy-check (N-L8) exists to prevent.
     *
     * Now the acquisition runs under the mutex, and when the resident
     * engine is already the best repair model it is REUSED (owned =
     * false — the repository keeps owning it; the caller must NOT release
     * it). Otherwise a dedicated engine is built under the lock and handed
     * to the caller with owned = true — release it in a finally block
     * exactly like the old direct construction did.
     *
     * Strongest-model semantics are unchanged (v0.6.9): the 7B writes the
     * repairs whenever it is on the device; a weak model never writes
     * repairs while a stronger one sits on disk. Reuse only happens when
     * the resident engine IS that strongest model.
     */
    suspend fun acquireRepairEngine(): RepairEngineHandle = mutex.withLock {
        val choice = ModelManager.bestRepairModel()
            ?: return RepairEngineHandle(null, owned = false)
        val resident = localLlm
        if (resident != null && resident.modelId == choice.id) {
            Log.i(TAG, "repair engine: reusing resident ${choice.id} (no second native instance)")
            RepairEngineHandle(resident, owned = false)
        } else {
            if (resident != null) {
                Log.i(TAG, "repair engine: ${choice.id} built alongside resident ${resident.modelId} " +
                    "(time-bounded: caller releases it after the heal)")
            }
            RepairEngineHandle(buildEngine(choice), owned = true)
        }
    }

    /** The result of [acquireRepairEngine]: the engine, and who owns it. */
    data class RepairEngineHandle(
        /** The repair engine, or null when no coder model is downloaded. */
        val llm: LocalLlm?,
        /**
         * True when the CALLER owns this engine and must [LocalLlm.release]
         * it in a finally block; false when it is the repository's resident
         * engine (reused) and releasing it would break the running task.
         */
        val owned: Boolean,
    )

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
