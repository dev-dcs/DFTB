package ca.devdcs.backup.core.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import ca.devdcs.backup.core.logging.DftbLog

/**
 * Wakes DFTB's job-resume reconciliation after the device boots.
 *
 * It exists because a backup/restore interrupted by a reboot must be noticed as soon as the device
 * comes back, not only when the user next opens the app. The receiver does no work itself (that
 * would block the broadcast and risk an ANR); it only enqueues [JobResumeWorker] through
 * WorkManager, which runs the actual reconciliation off the main thread.
 *
 * It is direct-boot aware so it also receives `LOCKED_BOOT_COMPLETED`, but WorkManager's database
 * lives in credential-encrypted storage, so while the user is still locked the receiver defers and
 * lets the later `BOOT_COMPLETED` (fired after unlock) do the enqueue.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in HANDLED_ACTIONS) {
            DftbLog.d(TAG, "Ignoring unexpected action: $action")
            return
        }

        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val unlocked = userManager?.isUserUnlocked ?: true
        if (!unlocked) {
            // Credential-encrypted storage (and thus the WorkManager/Room DB) is not available yet.
            DftbLog.i(TAG, "Received $action while locked; deferring resume until BOOT_COMPLETED")
            return
        }

        DftbLog.i(TAG, "Received $action; enqueuing job-resume reconciliation")
        runCatching { JobResumeWorker.enqueue(context, JobResumeWorker.SOURCE_BOOT) }
            .onFailure { DftbLog.e(TAG, "Failed to enqueue job-resume worker on boot", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"

        /** OEM-specific fast-boot actions are included alongside the standard ones. */
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
