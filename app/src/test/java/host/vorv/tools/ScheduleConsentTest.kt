package host.vorv.tools

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.gson.JsonObject
import host.vorv.data.VorvPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScheduleConsentTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = VorvPrefs(context)
    private lateinit var tool: ScheduleTool
    private val input = JsonObject().apply {
        addProperty("title", "Full reminder title")
        addProperty("text", "Full reminder body\nincluding a second line")
        addProperty("minutes", 2.75)
    }
    private val workName = "vorv_reminder_consent_1"

    @Before fun setup() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        prefs.setScheduledReminders("[]")
        tool = ScheduleTool(context, ReminderRegistry(prefs))
    }

    private fun ctx(confirm: (suspend (String) -> Boolean)?) =
        ToolContext("consent", 1, null, confirm)

    private suspend fun assertNoReminder() {
        assertEquals("[]", prefs.scheduledReminders.first())
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get().isEmpty())
    }

    @Test fun `missing bridge writes nothing`() = runBlocking {
        assertFalse(tool.run(input, ctx(null)).ok)
        assertNoReminder()
    }

    @Test fun `denial writes nothing and retry asks again`() = runBlocking {
        repeat(2) {
            var asked = false
            assertFalse(tool.run(input, ctx { asked = true; false }).ok)
            assertTrue(asked)
            assertNoReminder()
        }
    }

    @Test fun `bridge failure writes nothing`() = runBlocking {
        assertFalse(tool.run(input, ctx { throw IllegalStateException("bridge failed") }).ok)
        assertNoReminder()
    }

    @Test fun `cancellation while awaiting approval writes nothing`() = runBlocking {
        try {
            tool.run(input, ctx { throw kotlinx.coroutines.CancellationException("stopped") })
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertNoReminder()
        }
    }

    @Test fun `invalid delay never asks or schedules`() = runBlocking {
        input.addProperty("minutes", 0)
        assertFalse(tool.run(input, ctx { fail("Invalid reminder requested consent"); true }).ok)
        assertNoReminder()
    }

    @Test fun `approval shows complete content before any write then schedules`() = runBlocking {
        val result = tool.run(input, ctx { message ->
            assertNoReminder()
            assertTrue(message.contains(input.get("title").asString))
            assertTrue(message.contains(input.get("text").asString))
            assertTrue(message.contains("in 2 minutes after approval"))
            true
        })
        assertTrue(result.error, result.ok)
        assertTrue(prefs.scheduledReminders.first().contains(workName))
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, work.size)
        assertEquals(120_000L, work.single().initialDelayMillis)
    }
}
