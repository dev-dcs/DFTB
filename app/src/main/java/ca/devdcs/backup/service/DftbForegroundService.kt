package ca.devdcs.backup.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ca.devdcs.backup.MainActivity
import ca.devdcs.backup.R
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.notification.NotificationChannels
import ca.devdcs.backup.core.progress.JobPhase
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.ui.shell.ShellIntents
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Keeps the process alive while jobs run and renders their progress as notifications.
 *
 * The service holds no job logic. Engines publish to [JobProgressBus]; this service mirrors the
 * bus into one ongoing notification per job plus a group summary, posts a result notification on
 * the alerts channel when a job ends, and stops itself a few seconds after the last job finishes.
 * Cancel buttons route back through [JobProgressBus.requestCancel].
 */
@AndroidEntryPoint
class DftbForegroundService : LifecycleService() {

    @javax.inject.Inject lateinit var bus: JobProgressBus
    @javax.inject.Inject lateinit var channels: NotificationChannels
    @javax.inject.Inject lateinit var localeManager: LocaleManager

    private lateinit var notifications: NotificationManagerCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var idleStopJob: Job? = null
    private val shownJobIds = linkedSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        notifications = NotificationManagerCompat.from(this)
        channels.ensureCreated()
        acquireWakeLock()
        startForegroundCompat(buildSummaryNotification(emptyList()))
        observeBus()
        DftbLog.i(TAG, "Foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CANCEL_JOB -> intent.getStringExtra(EXTRA_JOB_ID)?.let(bus::requestCancel)
            ACTION_STOP -> stopSelfGracefully()
            ACTION_START, null -> Unit
        }
        // Not sticky: if the system kills us, the engine (or the boot/resume worker) restarts us
        // with a fresh job instead of the system resurrecting an empty service.
        return START_NOT_STICKY
    }

    /**
     * Android 15+ caps dataSync foreground services at 6 h per 24 h; when the budget runs out the
     * system calls this and kills the process (ForegroundServiceDidNotStopInTimeException) unless
     * the service leaves the foreground within seconds. Stopping here keeps the process (and the
     * running engine) alive as ordinary background work; jobs run under WorkManager are stopped by
     * WorkManager's own foreground service, recorded INTERRUPTED and resumed from their checkpoint,
     * and a job that later dies with the process is repaired to INTERRUPTED and resumed as well.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DftbLog.w(TAG, "Foreground-service time limit reached (type $fgsType); leaving the foreground")
        idleStopJob?.cancel()
        stopSelfGracefully()
    }

    override fun onDestroy() {
        idleStopJob?.cancel()
        shownJobIds.forEach { notifications.cancel(it.notificationId()) }
        shownJobIds.clear()
        releaseWakeLock()
        DftbLog.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    // --- Bus mirroring -------------------------------------------------------------------------

    private fun observeBus() {
        lifecycleScope.launch {
            combine(bus.activeJobs, localeManager.language) { jobs, _ -> jobs.values.toList() }
                .conflate()
                .collect { jobs ->
                    render(jobs)
                    // Rate-limit notification updates; conflate() drops intermediate states.
                    delay(MIN_UPDATE_INTERVAL_MS)
                }
        }
        lifecycleScope.launch {
            bus.terminalEvents.collect { finished -> postResultNotification(finished) }
        }
    }

    private fun render(jobs: List<JobProgress>) {
        val context = localeManager.localizedContext(this)
        val liveIds = jobs.map { it.jobId }.toSet()

        (shownJobIds - liveIds).forEach { gone ->
            notifications.cancel(gone.notificationId())
            shownJobIds.remove(gone)
        }
        jobs.forEach { job ->
            shownJobIds += job.jobId
            notifications.notifySafely(job.jobId.notificationId(), buildJobNotification(context, job))
        }
        notifications.notifySafely(SUMMARY_NOTIFICATION_ID, buildSummaryNotification(jobs, context))

        if (jobs.isEmpty()) scheduleIdleStop() else idleStopJob?.cancel()
    }

    private fun scheduleIdleStop() {
        if (idleStopJob?.isActive == true) return
        idleStopJob = lifecycleScope.launch {
            delay(IDLE_STOP_DELAY_MS)
            if (bus.activeJobs.value.isEmpty()) stopSelfGracefully()
        }
    }

    private fun stopSelfGracefully() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Notifications -------------------------------------------------------------------------

    private fun buildJobNotification(context: Context, job: JobProgress): Notification {
        val kind = context.getString(job.kind.labelRes)
        val phase = context.getString(job.phase.labelRes)
        val progressLine = if (job.totalBytes > 0) {
            context.getString(
                R.string.notification_progress_bytes,
                Formatter.formatShortFileSize(context, job.processedBytes),
                Formatter.formatShortFileSize(context, job.totalBytes),
            )
        } else if (job.totalItems > 0) {
            context.getString(R.string.notification_progress_items, job.processedItems, job.totalItems)
        } else {
            phase
        }
        val detail = listOfNotNull(job.currentItem, job.message).firstOrNull()

        return NotificationCompat.Builder(context, NotificationChannels.JOBS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$kind · ${job.title}")
            .setContentText(progressLine)
            .setSubText(if (job.bytesPerSecond > 0) Formatter.formatShortFileSize(context, job.bytesPerSecond) + "/s" else phase)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(progressLine, detail).joinToString("\n")))
            .setProgress(100, job.percent, job.indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setGroup(GROUP_KEY)
            .setContentIntent(openAppIntent(context, job.jobId, job.jobId.notificationId()))
            .addAction(0, context.getString(R.string.notification_action_cancel), cancelJobIntent(context, job.jobId))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun buildSummaryNotification(jobs: List<JobProgress>, context: Context = localeManager.localizedContext(this)): Notification {
        val text = if (jobs.isEmpty()) {
            context.getString(R.string.notification_idle_text)
        } else {
            context.resources.getQuantityString(R.plurals.notification_active_jobs, jobs.size, jobs.size)
        }
        return NotificationCompat.Builder(context, NotificationChannels.JOBS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_jobs_summary_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(context, jobId = null, requestCode = SUMMARY_NOTIFICATION_ID))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun postResultNotification(job: JobProgress) {
        val context = localeManager.localizedContext(this)
        val kind = context.getString(job.kind.labelRes)
        val phase = context.getString(job.phase.labelRes)
        val body = job.message ?: if (job.totalBytes > 0) Formatter.formatShortFileSize(context, job.processedBytes) else phase
        val notification = NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$kind · ${job.title} — $phase")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(if (job.phase == JobPhase.FAILED) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(context, job.jobId, job.jobId.resultNotificationId()))
            .build()
        notifications.notifySafely(job.jobId.resultNotificationId(), notification)
    }

    /**
     * Opens the job monitor: for [jobId] (a job's progress or result notification) or, with null,
     * whatever is running (the summary). [requestCode] is per notification so each job keeps its
     * own PendingIntent extras instead of FLAG_UPDATE_CURRENT overwriting a shared one.
     */
    private fun openAppIntent(context: Context, jobId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ShellIntents.ACTION_OPEN_JOB)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        jobId?.let { intent.putExtra(ShellIntents.EXTRA_JOB_ID, it) }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelJobIntent(context: Context, jobId: String): PendingIntent {
        val intent = Intent(context, DftbForegroundService::class.java)
            .setAction(ACTION_CANCEL_JOB)
            .putExtra(EXTRA_JOB_ID, jobId)
        return PendingIntent.getService(
            context,
            jobId.notificationId(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** POST_NOTIFICATIONS may be denied on API 33+; the foreground notification still shows. */
    private fun NotificationManagerCompat.notifySafely(id: Int, notification: Notification) {
        try {
            notify(id, notification)
        } catch (error: SecurityException) {
            DftbLog.w(TAG, "Notification permission missing", error)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            SUMMARY_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    // --- Wake lock -----------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:jobs").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun String.notificationId(): Int = JOB_NOTIFICATION_BASE + (abs(hashCode()) % 100_000)
    private fun String.resultNotificationId(): Int = RESULT_NOTIFICATION_BASE + (abs(hashCode()) % 100_000)

    companion object {
        private const val TAG = "FgService"
        const val ACTION_START = "ca.devdcs.backup.action.START_SERVICE"
        const val ACTION_STOP = "ca.devdcs.backup.action.STOP_SERVICE"
        const val ACTION_CANCEL_JOB = "ca.devdcs.backup.action.CANCEL_JOB"
        const val EXTRA_JOB_ID = "job_id"

        private const val GROUP_KEY = "ca.devdcs.backup.JOBS"
        private const val SUMMARY_NOTIFICATION_ID = 1_000
        private const val JOB_NOTIFICATION_BASE = 10_000
        private const val RESULT_NOTIFICATION_BASE = 200_000
        private const val MIN_UPDATE_INTERVAL_MS = 350L
        private const val IDLE_STOP_DELAY_MS = 4_000L
        /** Upper bound as a safety net; released explicitly in onDestroy. */
        private const val MAX_WAKE_LOCK_MS = 12L * 60 * 60 * 1000

        /** Start (or poke) the service. Call before the first [JobProgressBus.publish] of a job. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DftbForegroundService::class.java).setAction(ACTION_START),
            )
        }

        fun cancelJob(context: Context, jobId: String) {
            context.startService(
                Intent(context, DftbForegroundService::class.java)
                    .setAction(ACTION_CANCEL_JOB)
                    .putExtra(EXTRA_JOB_ID, jobId),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DftbForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
