package ca.devdcs.backup.core.schedule

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import ca.devdcs.backup.MainActivity
import ca.devdcs.backup.R
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.notification.NotificationChannels
import ca.devdcs.backup.core.progress.JobKind
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.service.DftbForegroundService
import ca.devdcs.backup.ui.shell.ShellIntents
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Notifications of unattended work: the backup worker's foreground notification and the alerts
 * that ask the user to step in (a passphrase-encrypted profile that cannot run alone, an
 * interrupted restore, a scheduled run that failed for a reason only the user can fix).
 *
 * Exists so the worker and the resume coordinator speak with one localized voice and one id
 * scheme; the live progress itself stays with [DftbForegroundService].
 */
@Singleton
class ScheduleNotifier @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val localeManager: LocaleManager,
    private val channels: NotificationChannels,
) {

    /** Foreground info for the backup worker (dataSync type; minSdk 29 so always typed). */
    fun foregroundInfo(profileId: String, profileName: String, jobId: String, trigger: String): ForegroundInfo {
        channels.ensureCreated()
        val context = localeManager.localizedContext(appContext)
        val text = when (trigger) {
            BackupWorker.TRIGGER_RESUME -> R.string.schedule_fg_text_resume
            BackupWorker.TRIGGER_MANUAL -> R.string.schedule_fg_text_manual
            else -> R.string.schedule_fg_text_scheduled
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.JOBS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.schedule_fg_title, profileName))
            .setContentText(context.getString(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openJobIntent(context, profileId, jobId))
            .addAction(0, context.getString(R.string.notification_action_cancel), cancelJobIntent(context, jobId))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return ForegroundInfo(foregroundId(profileId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    fun passphraseRequired(profileId: String, profileName: String) {
        val context = localeManager.localizedContext(appContext)
        post(
            id = alertId("pass", profileId),
            notification = alert(context, profileId)
                .setContentTitle(context.getString(R.string.schedule_passphrase_title, profileName))
                .setContentText(context.getString(R.string.schedule_passphrase_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.schedule_passphrase_text)))
                .build(),
        )
    }

    fun backupFailed(profileId: String, profileName: String, message: String?) {
        val context = localeManager.localizedContext(appContext)
        val body = message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.schedule_failed_text_generic)
        post(
            id = alertId("fail", profileId),
            notification = alert(context, profileId)
                .setContentTitle(context.getString(R.string.schedule_failed_title, profileName))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build(),
        )
    }

    /** A non-backup job (restore, verify, sync) was interrupted; these are never auto-resumed. */
    fun jobInterrupted(jobId: String, type: JobType) {
        val context = localeManager.localizedContext(appContext)
        val builder = alert(context, profileId = null)
        if (type == JobType.RESTORE) {
            builder.setContentTitle(context.getString(R.string.schedule_restore_interrupted_title))
                .setContentText(context.getString(R.string.schedule_restore_interrupted_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.schedule_restore_interrupted_text)))
        } else {
            val kind = context.getString(kindOf(type).labelRes)
            builder.setContentTitle(context.getString(R.string.schedule_job_interrupted_title, kind))
                .setContentText(context.getString(R.string.schedule_job_interrupted_text))
        }
        post(alertId("job", jobId), builder.build())
    }

    private fun alert(context: Context, profileId: String?): NotificationCompat.Builder {
        channels.ensureCreated()
        return NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(context, profileId))
    }

    private fun post(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(appContext).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied on API 33+; the state is still visible in the app.
            DftbLog.w(TAG, "Notification permission missing", e)
        }
    }

    private fun openAppIntent(context: Context, profileId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_PROFILE)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        profileId?.let { intent.putExtra(EXTRA_PROFILE_ID, it) }
        return PendingIntent.getActivity(
            context,
            profileId?.let { abs(it.hashCode()) } ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The running backup's notification opens its job monitor (profile id kept for context). */
    private fun openJobIntent(context: Context, profileId: String, jobId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ShellIntents.ACTION_OPEN_JOB)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ShellIntents.EXTRA_JOB_ID, jobId)
            .putExtra(EXTRA_PROFILE_ID, profileId)
        return PendingIntent.getActivity(
            context,
            OPEN_JOB_REQUEST_BASE + abs(jobId.hashCode()) % ID_SPAN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Routes through the bus (via the service) so the engine records a user CANCEL, not an interruption. */
    private fun cancelJobIntent(context: Context, jobId: String): PendingIntent {
        val intent = Intent(context, DftbForegroundService::class.java)
            .setAction(DftbForegroundService.ACTION_CANCEL_JOB)
            .putExtra(DftbForegroundService.EXTRA_JOB_ID, jobId)
        return PendingIntent.getService(
            context,
            CANCEL_REQUEST_BASE + abs(jobId.hashCode()) % ID_SPAN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun kindOf(type: JobType): JobKind = when (type) {
        JobType.BACKUP -> JobKind.BACKUP
        JobType.RESTORE -> JobKind.RESTORE
        JobType.VERIFY -> JobKind.VERIFY
        JobType.SYNC -> JobKind.SYNC
    }

    private fun foregroundId(profileId: String): Int = FOREGROUND_BASE + abs(profileId.hashCode() % ID_SPAN)

    private fun alertId(kind: String, key: String): Int = ALERT_BASE + abs(("$kind:$key").hashCode() % ID_SPAN)

    companion object {
        private const val TAG = "ScheduleNotify"

        /** Intent action/extra UI reads to open the profile a notification is about. */
        const val ACTION_OPEN_PROFILE = "ca.devdcs.backup.action.OPEN_PROFILE"
        const val EXTRA_PROFILE_ID = "ca.devdcs.backup.extra.PROFILE_ID"

        // Disjoint from DftbForegroundService ids (1_000, 10_000+, 200_000+).
        private const val FOREGROUND_BASE = 400_000
        private const val ALERT_BASE = 500_000
        private const val CANCEL_REQUEST_BASE = 600_000
        private const val OPEN_JOB_REQUEST_BASE = 700_000
        private const val ID_SPAN = 90_000
    }
}
