package host.vorv.tools

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import host.vorv.data.VorvPrefs
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Pure validation for schedule minutes (audit findings M-5, M-6) — extracted
 * so it is unit-testable without WorkManager.
 */
object ScheduleValidator {
    const val MIN_MINUTES = 1L
    const val MAX_MINUTES = 7 * 24 * 60L   // 7 days

    /** null = acceptable; non-null = honest refusal reason. */
    fun checkMinutes(minutes: Double): String? = when {
        minutes < MIN_MINUTES -> "minutes must be >= $MIN_MINUTES (got $minutes)"
        minutes > MAX_MINUTES -> "refusing to schedule more than 7 days out ($minutes minutes) — " +
            "use a calendar app for long-term reminders"
        minutes.isNaN() || minutes.isInfinite() -> "minutes must be a finite number"
        else -> null
    }
}

/**
 * Persistence for the pending-reminder registry: WorkManager unique names
 * keyed by title, so schedule_cancel can find them across restarts.
 */
class ReminderRegistry(private val prefs: VorvPrefs) {

    data class Entry(val name: String, val title: String, val etaMs: Long)

    private suspend fun load(): MutableList<Entry> {
        val raw = prefs.scheduledReminders.first()
        return try {
            val arr = JsonParser.parseString(raw).asJsonArray
            arr.map { el ->
                val o = el.asJsonObject
                Entry(
                    name = o.get("name").asString,
                    title = o.get("title").asString,
                    etaMs = o.get("etaMs").asLong,
                )
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private suspend fun save(entries: List<Entry>) {
        val arr = com.google.gson.JsonArray()
        entries.forEach { e ->
            arr.add(JsonObject().apply {
                addProperty("name", e.name)
                addProperty("title", e.title)
                addProperty("etaMs", e.etaMs)
            })
        }
        prefs.setScheduledReminders(arr.toString())
    }

    suspend fun register(entry: Entry) {
        val list = load()
        // Cap pending reminders to prevent WorkManager flooding (audit M-5).
        if (list.size >= MAX_PENDING) {
            throw IllegalStateException("Too many pending reminders (${list.size}, max $MAX_PENDING). Cancel some first.")
        }
        list.add(entry)
        save(list)
    }

    /**
     * Remove [entry] from the registry. Audit M-9: called when the WorkManager
     * enqueue FAILS after a successful register, so a reminder can never
     * exist in the registry without a live work request (or vice versa).
     */
    suspend fun rollback(entry: Entry) {
        val list = load()
        save(list.filter { it.name != entry.name })
    }

    /** Snapshot of every registry entry (used by "clear ALL data", audit M-4). */
    suspend fun allEntries(): List<Entry> = load()

    data class CancelBatch(
        /** Registry entries matching the cancel request. */
        val matching: List<Entry>,
        /** Expired entries whose work is ALSO cancelled so nothing lingers (audit M-9). */
        val expired: List<Entry>,
    )

    /**
     * Returns the entries to cancel for [title] (or all when null).
     * Audit M-9: expired entries (eta in the past) used to be silently
     * dropped from the registry WITHOUT cancelling their WorkManager work —
     * a doze-delayed reminder could still fire, uncancellable. They are now
     * returned alongside the matches so the caller cancels their work too.
     */
    suspend fun takeForCancel(title: String?): CancelBatch {
        val list = load()
        val now = System.currentTimeMillis()
        val (expired, live) = list.partition { it.etaMs <= now }
        if (expired.isNotEmpty()) save(live)
        val current = if (expired.isNotEmpty()) live else list
        val matching = if (title.isNullOrBlank()) current else current.filter { it.title == title }
        val remaining = current - matching.toSet()
        save(remaining)
        return CancelBatch(matching = matching, expired = expired)
    }

    private companion object {
        const val MAX_PENDING = 20
    }
}

/**
 * schedule — enqueue a delayed reminder via WorkManager.
 * Fires even if the app is killed. WorkManager persists across reboots.
 *
 * v0.6.0 (audit M-5, M-6): unique work names prevent flooding, minutes are
 * capped at 7 days, and a companion [ScheduleCancelTool] exists.
 */
class ScheduleTool(
    appContext: Context,
    private val registry: ReminderRegistry,
) : Tool {
    override val name = "schedule"
    override val description =
        "Schedule a reminder that fires N minutes from now (1 to 10080 = 7 days), even if " +
        "the app is closed or the phone reboots. Returns the reminder's unique name."

    override val timeoutMs: Long get() = 15_000L
    override val maxCallsPerTask: Int get() = 10

    private val workManager = WorkManager.getInstance(appContext)

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("title", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Reminder title.") })
            add("text", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Reminder body text.") })
            add("minutes", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Minutes from now to fire. 1 to 10080.") })
        })
        add("required", com.google.gson.JsonArray().apply { add("title"); add("text"); add("minutes") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
        val t0 = System.currentTimeMillis()
        val title = input.get("title")?.asString
            ?: return ToolResult(ok = false, output = "", error = "missing title", durationMs = System.currentTimeMillis() - t0)
        val text = input.get("text")?.asString
            ?: return ToolResult(ok = false, output = "", error = "missing text", durationMs = System.currentTimeMillis() - t0)
        val minutes = input.get("minutes")?.takeIf { !it.isJsonNull }?.asDouble
            ?: return ToolResult(ok = false, output = "", error = "missing minutes", durationMs = System.currentTimeMillis() - t0)

        ScheduleValidator.checkMinutes(minutes)?.let { refusal ->
            return ToolResult(ok = false, output = "", error = refusal, durationMs = System.currentTimeMillis() - t0)
        }

        return try {
            val delay = minutes.toLong()
            val workName = "vorv_reminder_${ctx.taskId}_${ctx.iteration}"
            val entry = ReminderRegistry.Entry(
                name = workName,
                title = title,
                etaMs = System.currentTimeMillis() + delay * 60_000,
            )

            // Audit M-9: register FIRST (this is where the 20-entry cap
            // throws), THEN enqueue — the old order (enqueue, then register)
            // left an enqueued, uncancellable work request behind whenever
            // register threw at the cap. If the enqueue itself fails, roll
            // the registry entry back so the two never disagree.
            registry.register(entry)
            try {
                val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(delay, TimeUnit.MINUTES)
                    .setInputData(workDataOf(
                        "title" to title,
                        "text" to text,
                    ))
                    .build()
                // Unique name + REPLACE: a re-run of the same iteration can't
                // stack duplicates (audit M-5).
                workManager.enqueueUniqueWork(workName, androidx.work.ExistingWorkPolicy.REPLACE, request)
            } catch (e: Exception) {
                registry.rollback(entry)
                throw e
            }

            ToolResult(
                ok = true,
                output = "Scheduled: '$title' in $delay minutes (work name: $workName). " +
                    "Will fire even if the app is killed. Cancel with schedule_cancel.",
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "schedule iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "schedule failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }
}

/**
 * schedule_cancel — cancel pending reminders by title, or all of them
 * (audit M-5: "no cancellation API").
 */
class ScheduleCancelTool(
    appContext: Context,
    private val registry: ReminderRegistry,
) : Tool {
    override val name = "schedule_cancel"
    override val description =
        "Cancel pending scheduled reminders. Pass a title to cancel reminders with that " +
        "exact title, or omit it to cancel ALL pending reminders. Returns how many were cancelled."

    override val timeoutMs: Long get() = 10_000L
    override val maxCallsPerTask: Int get() = 20

    private val workManager = WorkManager.getInstance(appContext)

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("title", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "Exact reminder title to cancel. Omit to cancel all.")
            })
        })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
        val t0 = System.currentTimeMillis()
        val title = input.get("title")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

        return try {
            val batch = registry.takeForCancel(title)
            // Cancel BOTH the matches and any expired entries (audit M-9):
            // an expired-but-still-pending work request (doze delay) must not
            // linger uncancellable just because its eta passed.
            (batch.matching + batch.expired).forEach { workManager.cancelUniqueWork(it.name) }
            val scope = if (title.isNullOrBlank()) "all pending reminders" else "reminders titled '$title'"
            ToolResult(
                ok = true,
                output = if (batch.matching.isEmpty()) {
                    if (batch.expired.isEmpty()) "No $scope were pending."
                    else "No $scope were pending; also cleaned up ${batch.expired.size} expired entr${if (batch.expired.size == 1) "y" else "ies"}."
                } else {
                    "Cancelled ${batch.matching.size} $scope: ${batch.matching.joinToString { it.title }}" +
                        (if (batch.expired.isNotEmpty()) "; also cleaned up ${batch.expired.size} expired." else "")
                },
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "schedule_cancel iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            ToolResult(ok = false, output = "", error = "schedule_cancel failed: ${e.message}",
                durationMs = System.currentTimeMillis() - t0)
        }
    }
}
