package ca.devdcs.backup.core.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ca.devdcs.backup.core.logging.DftbLog

/**
 * Re-aligns schedules when their wall-clock basis changes: after an app update (new code may
 * compute slots differently; owed runs are caught up) and after a time-zone or manual clock change
 * (a 02:00 slot must stay 02:00 local time; no catch-up so a clock jump does not trigger backups).
 * Does no I/O itself; it only enqueues [ScheduleSyncWorker].
 */
class ScheduleRearmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val catchUp = when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> false
            else -> {
                DftbLog.d(TAG, "Ignoring unexpected action: $action")
                return
            }
        }
        DftbLog.i(TAG, "Received $action; re-arming schedules")
        runCatching { ScheduleSyncWorker.enqueue(context, action, catchUp) }
            .onFailure { DftbLog.e(TAG, "Failed to enqueue schedule sync", it) }
    }

    private companion object {
        const val TAG = "ScheduleRearm"
    }
}
