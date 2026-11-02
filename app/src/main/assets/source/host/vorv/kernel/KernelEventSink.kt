package host.vorv.kernel

import host.vorv.data.VorvDatabase
import host.vorv.data.entity.Episode
import host.vorv.data.entity.ToolCall

/**
 * Persistence seam for the cognitive kernel (audit finding H-6 /
 * architecture review 10.2): the kernel no longer touches Room directly.
 * Production wires [RoomKernelEventSink]; unit tests wire a fake and assert
 * on what was logged.
 */
interface KernelEventSink {
    suspend fun logEpisode(kind: String, summary: String, detailsJson: String = "{}")
    suspend fun insertToolCall(call: ToolCall)
    suspend fun recordSkillResult(toolName: String, ok: Boolean)
    suspend fun setTaskIterations(taskId: String, iterations: Int)
}

class RoomKernelEventSink(private val db: VorvDatabase) : KernelEventSink {
    override suspend fun logEpisode(kind: String, summary: String, detailsJson: String) {
        db.episodeDao().insert(Episode(kind = kind, summary = summary, detailsJson = detailsJson))
    }

    override suspend fun insertToolCall(call: ToolCall) {
        db.toolCallDao().insert(call)
    }

    override suspend fun recordSkillResult(toolName: String, ok: Boolean) {
        if (ok) db.skillDao().recordSuccess(toolName) else db.skillDao().recordFailure(toolName)
    }

    override suspend fun setTaskIterations(taskId: String, iterations: Int) {
        db.taskDao().setIterations(taskId, iterations)
    }
}
