package host.vorv.service

import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import host.vorv.tools.NotifyTool
import host.vorv.tools.ToolContext
import host.vorv.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M-6 regression tests (code review, 2026-11): the foreground service and
 * the notify tool must own DIFFERENT notification channels with the right
 * importance for each, and the legacy collided id must be gone.
 *
 * THE BUG: AgentService created "vorv_agent" (IMPORTANCE_LOW) and NotifyTool
 * created the SAME id (IMPORTANCE_DEFAULT). Channels are immutable once
 * created — the service always runs first, so it won the race and every
 * user-facing notify-tool ping was silently demoted to LOW forever.
 *
 * THE CONTRACT:
 *  - FGS channel:  "vorv_agent_fgs", IMPORTANCE_LOW  (quiet status card)
 *  - notify tool:  "vorv_notify",    IMPORTANCE_DEFAULT (user-facing pings)
 *  - legacy id:    deleted by the service on upgrade, never recreated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // plain Application: VorvApplication eagerly touches the Keystore
class NotificationChannelsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun nm(): NotificationManager =
        context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `foreground service creates its own quiet channel`() {
        Robolectric.setupService(AgentService::class.java) // runs onCreate

        val chan = nm().getNotificationChannel(AgentService.CHANNEL_ID)
        assertNotNull("FGS channel must exist", chan)
        assertEquals("vorv_agent_fgs", AgentService.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, chan!!.importance)
    }

    @Test
    fun `legacy collided channel id is deleted on upgrade, not left behind`() {
        // Simulate the pre-fix install: the service had created "vorv_agent".
        nm().createNotificationChannel(
            android.app.NotificationChannel(
                "vorv_agent", "Vorv Agent", NotificationManager.IMPORTANCE_LOW,
            )
        )
        assertNotNull(nm().getNotificationChannel("vorv_agent"))

        Robolectric.setupService(AgentService::class.java)

        assertNull(
            "the collided legacy channel must be deleted by the migration",
            nm().getNotificationChannel("vorv_agent"),
        )
    }

    @Test
    fun `notify tool posts on its own channel at DEFAULT importance`() = runBlocking {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS),
        )

        val tool = NotifyTool(context)
        val result: ToolResult = tool.run(
            JsonObject().apply {
                addProperty("title", "Task done")
                addProperty("text", "report.csv saved")
            },
            ToolContext(taskId = "test", iteration = 1, workspaceUri = null),
        )

        assertTrue("notify must succeed: ${result.error}", result.ok)
        val chan = nm().getNotificationChannel("vorv_notify")
        assertNotNull("notify tool channel must exist", chan)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, chan!!.importance)
        assertTrue(
            "the notification must actually be posted",
            shadowOf(nm()).activeNotifications
                .any {
                    it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) == "Task done"
                },
        )
    }

    @Test
    fun `the two channel ids can never collide again`() {
        // Direct pin on the M-6 root cause: distinct ids BY CONSTANT, not by
        // luck of initialization order.
        assertFalse(AgentService.CHANNEL_ID == "vorv_agent")
        assertFalse(AgentService.CHANNEL_ID == "vorv_notify")
    }
}
