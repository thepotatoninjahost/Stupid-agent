package host.vorv.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

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

        try {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    "vorv_reminder", "Vorv Reminders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Scheduled reminders from Vorv" }
                nm.createNotificationChannel(chan)
            }
            val notif = NotificationCompat.Builder(applicationContext, "vorv_reminder")
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notif)
            return Result.success()
        } catch (e: Exception) {
            return Result.failure()
        }
    }
}
