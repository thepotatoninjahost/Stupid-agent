package host.vorv.tools

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.JsonObject
import java.util.concurrent.TimeUnit

/**
 * schedule — enqueue a delayed task via WorkManager.
 * Fires even if the app is killed. WorkManager persists across reboots.
 *
 * The scheduled work posts a notification with the user's reminder text.
 */
class ScheduleTool(private val appContext: Context) : Tool {
    override val name = "schedule"
    override val description = "Schedule a reminder that fires N minutes from now, even if the app is closed. Uses WorkManager — survives reboots."

    override fun schema() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("title", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Reminder title.") })
            add("text", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Reminder body text.") })
            add("minutes", JsonObject().apply { addProperty("type", "number"); addProperty("description", "Minutes from now to fire. Minimum 1.") })
        })
        add("required", com.google.gson.JsonArray().apply { add("title"); add("text"); add("minutes") })
    }

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolResult {
        val t0 = System.currentTimeMillis()
        val title = input.get("title")?.asString ?: return ToolResult(ok = false, output = "", error = "missing title", durationMs = System.currentTimeMillis() - t0)
        val text = input.get("text")?.asString ?: return ToolResult(ok = false, output = "", error = "missing text", durationMs = System.currentTimeMillis() - t0)
        val minutes = input.get("minutes")?.takeIf { !it.isJsonNull }?.asDouble ?: return ToolResult(ok = false, output = "", error = "missing minutes", durationMs = System.currentTimeMillis() - t0)

        if (minutes < 1) {
            return ToolResult(ok = false, output = "", error = "minutes must be >= 1", durationMs = System.currentTimeMillis() - t0)
        }

        try {
            val delay = minutes.toLong().coerceAtLeast(1L)
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .setInputData(workDataOf(
                    "title" to title,
                    "text" to text,
                ))
                .build()
            WorkManager.getInstance(appContext).enqueue(request)

            return ToolResult(
                ok = true,
                output = "Scheduled: '$title' in $delay minutes. Will fire even if app is killed.",
                durationMs = System.currentTimeMillis() - t0,
                citation = Citation("exec", ctx.taskId, "schedule iter ${ctx.iteration}"),
            )
        } catch (e: Exception) {
            return ToolResult(ok = false, output = "", error = "schedule failed: ${e.message}", durationMs = System.currentTimeMillis() - t0)
        }
    }
}
