package ca.devdcs.backup.core.schedule

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.devdcs.backup.core.logging.DftbLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Re-arms every profile's schedule off the main thread. Exists because the broadcasts that
 * invalidate wall-clock alignment (app update, time-zone or clock change) arrive in a
 * [android.content.BroadcastReceiver], which must not do database or WorkManager I/O inline.
 */
@HiltWorker
class ScheduleSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: BackupScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON) ?: "unknown"
        val catchUp = inputData.getBoolean(KEY_CATCH_UP, false)
        return try {
            scheduler.syncAll(reason, catchUp)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.e(TAG, "Schedule sync failed ($reason)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "ScheduleSync"
        private const val UNIQUE_NAME = "ca.devdcs.backup.schedule_sync"
        private const val KEY_REASON = "reason"
        private const val KEY_CATCH_UP = "catchUp"
        private const val MAX_ATTEMPTS = 3

        /** REPLACE: a newer clock/zone change supersedes a queued sync computed from the old one. */
        fun enqueue(context: Context, reason: String, catchUp: Boolean) {
            val request = OneTimeWorkRequest.Builder(ScheduleSyncWorker::class.java)
                .setInputData(Data.Builder().putString(KEY_REASON, reason).putBoolean(KEY_CATCH_UP, catchUp).build())
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
