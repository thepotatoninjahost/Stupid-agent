package host.vorv.tools

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import host.vorv.data.VorvDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M-7 regression tests (code review, 2026-11).
 *
 * THE BUG: ReminderWorker never checked POST_NOTIFICATIONS. On Android 13+
 * without the grant, nm.notify() is a SILENT no-op — the reminder "fired"
 * invisibly and the Worker returned success. NotifyTool already checked and
 * reported honestly; the worker lied instead.
 *
 * THE CONTRACT THESE TESTS PIN (Android 13 / SDK 33+):
 *  - without the grant: Result.failure() AND an honest episode row in the
 *    app's episode log (kind "error", "Reminder NOT shown"), so the miss is
 *    visible inside the app, not only inside WorkManager internals;
 *  - with the grant: Result.success() and a real notification posted on
 *    the "vorv_reminder" channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // plain Application: VorvApplication eagerly touches the Keystore
class ReminderWorkerPermissionTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun buildWorker(title: String, text: String): ReminderWorker =
        TestListenableWorkerBuilder<ReminderWorker>(context)
            .setInputData(workDataOf("title" to title, "text" to text))
            .build()

    @Test
    fun `revoked notification permission fails the work honestly instead of silent success`() {
        // No permission granted (Robolectric default) — exactly the Android 13+
        // state where notify() silently no-ops.
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        )
        assertEquals(PackageManager.PERMISSION_DENIED, granted)

        val result = buildWorker("check the build", "CI finished").doWork()

        assertTrue(
            "a reminder that cannot be shown must NOT report success",
            result is ListenableWorker.Result.Failure,
        )
    }

    @Test
    fun `missed reminder is recorded in the episode log the app can show`() = runBlocking {
        val result = buildWorker("check the build", "CI finished").doWork()
        assertTrue(result is ListenableWorker.Result.Failure)

        val db = androidx.room.Room.databaseBuilder(
            context, VorvDatabase::class.java, "vorv.db"
        ).addMigrations(*VorvDatabase.ALL_MIGRATIONS).build()
        try {
            val episode = db.episodeDao().observeRecent(50).first()
                .firstOrNull { it.summary.contains("Reminder NOT shown") }
            assertNotNull(
                "the missed reminder must be visible in the episode log", episode,
            )
            assertEquals("error", episode!!.kind)
            assertTrue(episode.summary.contains("check the build"))
            assertTrue(episode.detailsJson.contains("reminder_not_shown"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `granted permission posts the reminder and reports success`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val result = buildWorker("check the build", "CI finished").doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertNotNull(nm.getNotificationChannel("vorv_reminder"))
        }
        val shown = shadowOf(nm).activeNotifications
        assertTrue(
            "the reminder notification must actually be posted",
            shown.any {
                it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) == "check the build"
            },
        )
    }
}
