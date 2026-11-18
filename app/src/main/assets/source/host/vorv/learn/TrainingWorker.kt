package host.vorv.learn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import host.vorv.MainActivity
import host.vorv.data.VorvDatabase

/**
 * Step 9: training on the phone, where the phone is charging and cool.
 *
 * The worker is deliberately boring about success: every run writes a
 * `training_runs` row that starts as "running" and ends as promoted, refused,
 * stopped or failed with a sentence the owner can read. Nothing here can end
 * with a blank screen and no explanation.
 */
class TrainingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val startedAt = System.currentTimeMillis()
        val db = androidx.room.Room.databaseBuilder(
            applicationContext,
            VorvDatabase::class.java,
            "vorv.db",
        ).addMigrations(*VorvDatabase.ALL_MIGRATIONS).build()
        val runs = db.trainingRunDao()
        val ledger = LearningStore(db.learningDao())

        val stepsRequested = inputData.getInt(KEY_STEPS, DEFAULT_STEPS)
        val runId = runs.insert(
            host.vorv.data.entity.TrainingRunEntity(
                startedAt = startedAt,
                state = "running",
                rung = "",
                corpusRows = 0,
                message = "Training started.",
            ),
        )
        // A row left "running" by a killed process is a lie about the present;
        // this run clears older ones before it starts.
        runs.failStale(startedAt, "The process was killed before this run finished, so it is recorded as failed.")

        try {
            val rows = ledger.rows()
            val budget = BrainPaths.budgetBytes(applicationContext)
            val settings = Trainer.Settings(
                budgetBytes = budget,
                maxSteps = stepsRequested,
            )
            setForegroundQuietly("Preparing generation from ${rows.size} ledger rows")
            val outcome = Trainer(BrainPaths.dir(applicationContext)).run(rows, settings) { line ->
                notify("Training the on-device brain", line)
            }
            val state = when {
                !outcome.ran -> "stopped"
                outcome.promoted -> "promoted"
                else -> "refused"
            }
            runs.finish(
                id = runId,
                state = state,
                finishedAt = System.currentTimeMillis(),
                generation = outcome.generation,
                steps = outcome.steps,
                trainLoss = outcome.trainLoss.takeIf { it.isFinite() },
                valLoss = outcome.valLoss.takeIf { it.isFinite() },
                seconds = outcome.seconds.takeIf { it > 0 },
                message = outcome.message,
                decision = outcome.decision?.let { PromotionGate.explain(it) } ?: "",
            )
            notify(
                if (outcome.promoted) "A new generation is serving" else "Training finished without a promotion",
                outcome.message,
            )
            val data = workDataOf(
                KEY_STATE to state,
                KEY_GENERATION to outcome.generation,
                KEY_MESSAGE to outcome.message,
            )
            return Result.success(data)
        } catch (t: Throwable) {
            val why = "Training failed: ${t.javaClass.simpleName}: ${t.message ?: "no message"}"
            try {
                runs.finish(runId, "failed", System.currentTimeMillis(), 0, 0, null, null, null, why, "")
            } catch (inner: Throwable) {
                android.util.Log.e(TAG, "could not record the failed run: ${inner.message}", inner)
            }
            notify("Training failed", why)
            return Result.failure(Data.Builder().putString(KEY_MESSAGE, why).build())
        } finally {
            try {
                db.close()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "closing the database failed: ${t.message}")
            }
        }
    }

    private var foregroundTried = false

    private suspend fun setForegroundQuietly(text: String) {
        if (foregroundTried) return
        foregroundTried = true
        try {
            setForeground(foregroundInfo(text))
        } catch (t: Throwable) {
            // WorkManager refuses foreground without the permission or type;
            // the run still proceeds and the row still records the outcome.
            android.util.Log.w(TAG, "training could not go foreground: ${t.message}")
        }
        // A missing notification permission is not a reason to stop learning;
        // the run row and the final notification-free log still say what happened.
    }

    private fun foregroundInfo(text: String): ForegroundInfo =
        ForegroundInfo(CHANNEL_ID.hashCode(), buildNotification(text), FOREGROUND_TYPE)

    private fun notify(title: String, text: String) {
        if (!canNotify()) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        nm.notify(NOTIFICATION_ID, buildNotification("$title\n$text"))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setContentTitle("Vorv is training")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Vorv training", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress while the on-device brain trains" },
            )
        }
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Vorv/Train"
        const val CHANNEL_ID = "vorv_training"
        private const val NOTIFICATION_ID = 4711
        private const val FOREGROUND_TYPE = 0x40000000 // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        const val DEFAULT_STEPS = 240
        const val KEY_STEPS = "steps"
        const val KEY_STATE = "state"
        const val KEY_GENERATION = "generation"
        const val KEY_MESSAGE = "message"
        private const val WORK_NAME = "vorv-training"

        /**
         * Queues a run. [chargingOnly] is true for the automatic nightly run and
         * false for the run the owner starts by tapping in the Brain screen.
         */
        fun enqueue(context: Context, steps: Int = DEFAULT_STEPS, chargingOnly: Boolean = true) {
            val request = OneTimeWorkRequestBuilder<TrainingWorker>()
                .setInputData(workDataOf(KEY_STEPS to steps))
                .apply {
                    if (chargingOnly) {
                        setConstraints(
                            androidx.work.Constraints.Builder()
                                .setRequiresCharging(true)
                                .setRequiresBatteryNotLow(true)
                                .setRequiresDeviceIdle(true)
                                .build(),
                        )
                    }
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
