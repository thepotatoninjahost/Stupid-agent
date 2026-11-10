package host.vorv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin

/** Groups data readers/writers so deletion can drain them before wiping storage. */
internal class AgentDataSession(private val parent: CoroutineScope) {
    @Volatile private var job = SupervisorJob(parent.coroutineContext[Job])
    val scope: CoroutineScope get() = CoroutineScope(parent.coroutineContext + job)
    var clearing = false
        private set

    // Called on Main, as are the UI entry points that check clearing.
    suspend fun clear(delete: suspend () -> Unit) {
        if (clearing) return
        clearing = true
        try {
            // Cancelling the parent also rejects writes spawned by cancellation handlers.
            job.cancelAndJoin()
            delete()
        } finally {
            job = SupervisorJob(parent.coroutineContext[Job])
            clearing = false
        }
    }
}
