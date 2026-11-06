package host.vorv.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import host.vorv.MainActivity
import host.vorv.data.VorvDatabase
import host.vorv.data.entity.Episode
import kotlinx.coroutines.runBlocking

/**
 * WorkManager worker that fires the scheduled reminder.
 * Persists across app death and device reboot.
 */
class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val title = inputData.getString("title") ?: "Vorv reminder"
        val text = inputData.getString("text") ?: ""

        // M-7 (code review, 2026-11): on Android 13+, nm.notify() without the
        // POST_NOTIFICATIONS grant is a SILENT no-op — the reminder would
        // "fire" invisibly while this Worker reported success. NotifyTool
        // already checks and reports honestly; the worker must too. A
        // reminder that cannot be shown is a FAILED reminder: record it in
        // the episode log (visible in the app's episode history) and fail
        // the work so the WorkManager state tells the truth as well.
        // (Not retried on purpose: retrying in a few minutes cannot succeed
        // until the USER grants the permission, and a stale reminder firing
        // hours late would be a different, quieter lie.)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "reminder '$title' not shown: POST_NOTIFICATIONS not granted")
                recordUnshownReminder(title, text)
                return Result.failure()
            }
        }

        return try {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    "vorv_reminder", "Vorv Reminders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Scheduled reminders from Vorv" }
                nm.createNotificationChannel(chan)
            }

            // Tapping the notification opens the app (audit finding M-12).
            val contentIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val notif = NotificationCompat.Builder(applicationContext, "vorv_reminder")
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NotificationIds.nextId(), notif)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    /**
     * Writes the missed reminder into the episode log so the failure is
     * visible inside the app, not only in WorkManager internals. Own Room
     * instance built exactly the way the process-wide one is (same name,
     * same migrations); opened and closed around the one insert — a Worker
     * may run in a fresh process where nothing else has built the DB.
     */
    private fun recordUnshownReminder(title: String, text: String) {
        try {
            val db = androidx.room.Room.databaseBuilder(
                applicationContext,
                VorvDatabase::class.java,
                "vorv.db",
            )
                .addMigrations(*VorvDatabase.ALL_MIGRATIONS)
                .build()
            try {
                // Worker.doWork() runs on a WorkManager background thread —
                // blocking here is safe and keeps the failure atomic with the
                // Result.failure() that follows.
                runBlocking {
                    db.episodeDao().insert(
                        Episode(
                            kind = "error",
                            summary = "Reminder NOT shown — notification permission was " +
                                "revoked: \"$title\"",
                            detailsJson = "{\"kind\":\"reminder_not_shown\"," +
                                "\"title\":${titleJsonValue(title)}," +
                                "\"text\":${titleJsonValue(text)}}",
                        )
                    )
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            // The episode row is the best-effort surface; never let a logging
            // failure turn into a crash loop inside WorkManager.
            Log.e(TAG, "could not record unshown reminder: ${e.message}", e)
        }
    }

    /** Minimal JSON string escaping for the details payload. */
    private fun titleJsonValue(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    companion object {
        private const val TAG = "Vorv/ReminderWorker"
    }
}
