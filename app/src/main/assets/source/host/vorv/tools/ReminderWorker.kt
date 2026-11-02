package host.vorv.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import host.vorv.MainActivity

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
            nm.notify(NotificationIds.next(), notif)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
