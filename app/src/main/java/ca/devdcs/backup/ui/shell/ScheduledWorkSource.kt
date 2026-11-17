package ca.devdcs.backup.ui.shell

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.schedule.NetworkRequirement
import ca.devdcs.backup.core.schedule.WorkRequestFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only view of the backup work [ca.devdcs.backup.core.schedule.BackupScheduler] enqueued:
 * every unfinished item tagged [WorkRequestFactory.TAG_ALL], converted to [ScheduledWork].
 *
 * Exists because the scheduler owns enqueueing but exposes no query, and the dashboard must show
 * what WorkManager will actually run (exact next time, retries, constraint waits) rather than a
 * time recomputed from the policy. WorkManager is resolved lazily inside the flow so a failure to
 * obtain it degrades to an empty list instead of crashing the screen.
 */
@Singleton
class ScheduledWorkSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun observe(): Flow<List<ScheduledWork>> = flow {
        emitAll(WorkManager.getInstance(context).getWorkInfosByTagFlow(WorkRequestFactory.TAG_ALL))
    }
        .map { infos -> infos.mapNotNull(::toScheduledWork) }
        .catch { error ->
            DftbLog.w(TAG, "Could not read scheduled backups", error)
            emit(emptyList())
        }

    private fun toScheduledWork(info: WorkInfo): ScheduledWork? {
        val profileId = WorkRequestFactory.profileIdFromTags(info.tags) ?: return null
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED -> ScheduledWorkState.ENQUEUED
            WorkInfo.State.BLOCKED -> ScheduledWorkState.BLOCKED
            WorkInfo.State.RUNNING -> ScheduledWorkState.RUNNING
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> return null
        }
        val constraints = info.constraints
        return ScheduledWork(
            profileId = profileId,
            periodic = WorkRequestFactory.TAG_PERIODIC in info.tags,
            state = state,
            // Long.MAX_VALUE is WorkManager's "not scheduled" sentinel.
            nextRunAtMillis = info.nextScheduleTimeMillis.takeIf { it in 1 until Long.MAX_VALUE },
            runAttemptCount = info.runAttemptCount,
            network = when (constraints.requiredNetworkType) {
                NetworkType.NOT_REQUIRED -> NetworkRequirement.NONE
                NetworkType.UNMETERED -> NetworkRequirement.UNMETERED
                else -> NetworkRequirement.CONNECTED
            },
            requiresCharging = constraints.requiresCharging(),
            requiresDeviceIdle = constraints.requiresDeviceIdle(),
        )
    }

    private companion object {
        const val TAG = "ShellWork"
    }
}
