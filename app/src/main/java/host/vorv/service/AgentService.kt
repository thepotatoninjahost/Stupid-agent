package host.vorv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import host.vorv.R

/**
 * Foreground service that keeps Vorv alive while a long task is running.
 * Notification shows current task + iteration count.
 */
class AgentService : Service() {

    companion object {
        const val CHANNEL_ID = "vorv_agent"
        const val NOTIF_ID = 1

        const val ACTION_START = "host.vorv.action.START"
        const val ACTION_STOP = "host.vorv.action.STOP"
        const val EXTRA_TITLE = "title"

        fun start(ctx: Context, title: String) {
            val intent = Intent(ctx, AgentService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AgentService::class.java).apply { action = ACTION_STOP })
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Vorv Agent",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active agent tasks"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Vorv is thinking…"
                startForeground(NOTIF_ID, buildNotification(title))
            }
        }
        return START_STICKY
    }

    private fun buildNotification(title: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Vorv")
        .setContentText(title)
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
