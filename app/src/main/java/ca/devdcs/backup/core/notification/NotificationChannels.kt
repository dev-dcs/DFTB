package ca.devdcs.backup.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import ca.devdcs.backup.R
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the three notification channels and keeps their user-visible names in the app language.
 *
 * Channel names are frozen by the system at creation, so [observeLanguageChanges] re-registers the
 * channels (same ids, new names) whenever the language flips; importance and sound settings the
 * user has customised are preserved by the system because only name/description are mutable.
 */
@Singleton
class NotificationChannels @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val localeManager: LocaleManager,
    @ApplicationScope private val scope: CoroutineScope,
) {

    fun ensureCreated() {
        val context = localeManager.localizedContext(appContext)
        val manager = NotificationManagerCompat.from(appContext)

        manager.createNotificationChannel(
            NotificationChannel(JOBS, context.getString(R.string.notification_channel_jobs_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notification_channel_jobs_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERTS, context.getString(R.string.notification_channel_alerts_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notification_channel_alerts_desc)
                setShowBadge(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(SCHEDULER, context.getString(R.string.notification_channel_scheduler_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notification_channel_scheduler_desc)
                setShowBadge(false)
            },
        )
        DftbLog.d(TAG, "Notification channels registered (${localeManager.language.value.tag})")
    }

    fun observeLanguageChanges() {
        scope.launch {
            localeManager.language.drop(1).collect { ensureCreated() }
        }
    }

    fun areNotificationsEnabled(): Boolean = NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    companion object {
        private const val TAG = "Channels"
        /** Ongoing, low-importance progress of running jobs (foreground service). */
        const val JOBS = "dftb.jobs"
        /** Completion, failure, insufficient space, verification mismatch. */
        const val ALERTS = "dftb.alerts"
        /** Scheduled-run reminders and constraint waits (Wi-Fi, charging). */
        const val SCHEDULER = "dftb.scheduler"
    }
}
