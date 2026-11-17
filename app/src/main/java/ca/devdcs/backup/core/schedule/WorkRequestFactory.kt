package ca.devdcs.backup.core.schedule

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import java.util.concurrent.TimeUnit

/**
 * Copies a [ScheduleSpec] / [RunConstraints] into WorkManager builders. Exists so the only code
 * touching WorkManager request types is this thin, decision-free layer; every number it uses is
 * computed (and tested) in [ScheduleSpecs].
 */
object WorkRequestFactory {

    /** Unique-work name of a profile's periodic schedule. */
    fun periodicName(profileId: String): String = "$NAME_PREFIX.periodic.$profileId"

    /**
     * Unique-work name of a profile's one-off run. "Back up now" and resume have separate names so
     * a resume waiting in backoff never swallows a manual run; KEEP collapses duplicate taps and
     * [ProfileRunLock] keeps the two from running at once.
     */
    fun oneTimeName(profileId: String, trigger: String): String = "$NAME_PREFIX.once.$trigger.$profileId"

    /** Tag identifying the spec a periodic work was enqueued with (see [BackupScheduler]). */
    fun fingerprintTag(fingerprint: String): String = FINGERPRINT_TAG_PREFIX + Integer.toHexString(fingerprint.hashCode())

    /** Tag carried by every work item of [profileId], used to find orphans after a profile is deleted. */
    fun profileTag(profileId: String): String = "$PROFILE_TAG_PREFIX$profileId"

    fun profileIdFromTags(tags: Set<String>): String? =
        tags.firstOrNull { it.startsWith(PROFILE_TAG_PREFIX) }?.removePrefix(PROFILE_TAG_PREFIX)?.takeIf { it.isNotEmpty() }

    fun networkType(requirement: NetworkRequirement): NetworkType = when (requirement) {
        NetworkRequirement.NONE -> NetworkType.NOT_REQUIRED
        NetworkRequirement.CONNECTED -> NetworkType.CONNECTED
        NetworkRequirement.UNMETERED -> NetworkType.UNMETERED
    }

    fun constraints(spec: RunConstraints): Constraints = Constraints.Builder()
        .setRequiredNetworkType(networkType(spec.network))
        .setRequiresCharging(spec.requiresCharging)
        .setRequiresBatteryNotLow(spec.requiresBatteryNotLow)
        .setRequiresStorageNotLow(spec.requiresStorageNotLow)
        .setRequiresDeviceIdle(spec.requiresDeviceIdle)
        .build()

    fun periodic(spec: ScheduleSpec): PeriodicWorkRequest =
        PeriodicWorkRequest.Builder(BackupWorker::class.java, spec.periodMillis, TimeUnit.MILLISECONDS, spec.flexMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(spec.constraints))
            .setInitialDelay(spec.initialDelayMillis, TimeUnit.MILLISECONDS)
            .setNextScheduleTimeOverride(spec.nextRunAtMillis)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setBackoffForSystemInterruptions()
            .setInputData(BackupWorker.inputData(spec.profileId, BackupWorker.TRIGGER_SCHEDULE, resumeJobId = null))
            .addTag(TAG_ALL)
            .addTag(TAG_PERIODIC)
            .addTag(profileTag(spec.profileId))
            .addTag(fingerprintTag(spec.fingerprint))
            .build()

    /** Expedited one-off run; falls back to regular work when the expedited quota is used up. */
    fun oneTime(profileId: String, trigger: String, resumeJobId: String?, constraints: RunConstraints): OneTimeWorkRequest {
        val input: Data = BackupWorker.inputData(profileId, trigger, resumeJobId)
        return OneTimeWorkRequest.Builder(BackupWorker::class.java)
            .setConstraints(constraints(constraints))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setBackoffForSystemInterruptions()
            .setInputData(input)
            .addTag(TAG_ALL)
            .addTag(TAG_ONE_TIME)
            .addTag(profileTag(profileId))
            .build()
    }

    private const val NAME_PREFIX = "ca.devdcs.backup.backup"
    private const val PROFILE_TAG_PREFIX = "ca.devdcs.backup.profile:"
    private const val FINGERPRINT_TAG_PREFIX = "ca.devdcs.backup.spec:"
    const val TAG_ALL = "ca.devdcs.backup.backup_work"
    const val TAG_PERIODIC = "ca.devdcs.backup.backup_periodic"
    const val TAG_ONE_TIME = "ca.devdcs.backup.backup_once"

    /** First retry after a transient failure; WorkManager doubles it per attempt (capped at 5 h). */
    const val RETRY_BACKOFF_MILLIS = 5 * ScheduleMath.MINUTE_MILLIS
}
