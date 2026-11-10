package host.vorv.tools

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class NotifyConsentTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val title = "Task complete\nSecond title line"
    private val text = (1..40).joinToString("\n") { "Notification line $it" }
    private val input get() = JsonObject().apply {
        addProperty("title", title)
        addProperty("text", text)
    }

    private suspend fun run(confirm: (suspend (String) -> Boolean)?): ToolResult {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        return NotifyTool(context).run(input, ToolContext("test", 1, null, confirm))
    }

    @Test
    fun `missing bridge refuses without posting or creating a channel`() = runBlocking {
        assertFalse(run(null).ok)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertNull(manager.getNotificationChannel("vorv_notify"))
    }

    @Test
    fun `denial shows full content and prevents posting`() = runBlocking {
        var calls = 0
        val result = run { message ->
            calls++
            assertTrue(message.contains("Title:\n$title\n\nText:\n$text"))
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            false
        }
        assertEquals(1, calls)
        assertFalse(result.ok)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertNull(manager.getNotificationChannel("vorv_notify"))
    }

    @Test
    fun `approval posts the content that was shown`() = runBlocking {
        var calls = 0
        val result = run { message ->
            calls++
            assertTrue(message.contains(title))
            assertTrue(message.contains(text))
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            true
        }
        assertTrue(result.ok)
        assertEquals(1, calls)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(text, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
