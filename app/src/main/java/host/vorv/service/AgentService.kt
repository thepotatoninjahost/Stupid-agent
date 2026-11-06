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
        // M-6 (code review, 2026-11): this used to be "vorv_agent" — the SAME
        // channel id NotifyTool creates with IMPORTANCE_DEFAULT. Channels are
        // immutable once created, so whichever component ran first won and
        // the other's importance was silently dropped: the FGS's quiet
        // status notification and the notify tool's user-facing pings fought
        // over one channel and one of them was always wrong. The foreground
        // service now owns its own channel; NotifyTool owns "vorv_notify".
        const val CHANNEL_ID = "vorv_agent_fgs"
        /** Id both components used to fight over — deleted on upgrade, below. */
        private const val LEGACY_COLLIDED_CHANNEL_ID = "vorv_agent"
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
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Vorv Agent Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active agent tasks"
            }
            nm.createNotificationChannel(chan)
            // M-6 upgrade migration: on installs before the channel split,
            // this service created "vorv_agent" (IMPORTANCE_LOW) and won the
            // immutability race, silently demoting every NotifyTool
            // notification to LOW forever after. Nothing posts to that id
            // anymore — delete the stale channel so old installs get the
            // correct DEFAULT importance on the new "vorv_notify" channel
            // and no phantom "Vorv Agent" entry lingers in Settings.
            nm.deleteNotificationChannel(LEGACY_COLLIDED_CHANNEL_ID)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopForeground(Service.STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Vorv is thinking…"
                // Audit L-9: the service type is specialUse (compute), so the
                // startForeground call must say so on API 34+.
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, buildNotification(title),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIF_ID, buildNotification(title))
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(title: String): Notification {
        // Tapping the notification opens the app (audit finding M-12).
        val contentIntent = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, host.vorv.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vorv")
            .setContentText(title)
            .setContentIntent(contentIntent)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
