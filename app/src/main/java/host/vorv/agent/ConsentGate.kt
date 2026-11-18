package host.vorv.agent

import host.vorv.AgentState
import host.vorv.PendingConfirmation
import host.vorv.tools.SessionGrants
import host.vorv.tools.ShellTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * The one consent dialog. A tool asks; the screen shows the question; the
 * answer comes back to the same call. Two rules it enforces for every tool:
 *
 * - one dialog at a time, so an answer can never land on the wrong question;
 * - silence is "no" — after [timeoutMs] the call returns false and the dialog
 *   is taken down (fail closed: a consent nobody answered must not act).
 */
class ConsentGate(
    private val state: MutableStateFlow<AgentState>,
    private val timeoutMs: Long = 60_000L,
) {
    private val mutex = Mutex()

    suspend fun request(message: String): Boolean = mutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        val pending = PendingConfirmation(
            id = UUID.randomUUID().toString(),
            message = message,
            response = deferred,
            // Only the shell consent offers the session grant, and only when
            // no grant is active yet (an active grant means ShellTool never
            // reaches this dialog). CONSENT_PREFIX is the single source of
            // truth shared with the tool — pinned by test.
            offerSessionGrant = !SessionGrants.shellAllCommands &&
                message.startsWith(ShellTool.CONSENT_PREFIX),
        )
        state.update { it.copy(pendingConfirmation = pending) }
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            state.update {
                if (it.pendingConfirmation?.id == pending.id) it.copy(pendingConfirmation = null) else it
            }
        }
    }

    /** The screen's answer. Ignores an id that is no longer on screen. */
    fun respond(id: String, allowed: Boolean) {
        state.update { s ->
            s.pendingConfirmation?.let { if (it.id == id) it.response.complete(allowed) }
            if (s.pendingConfirmation?.id == id) s.copy(pendingConfirmation = null) else s
        }
    }
}
