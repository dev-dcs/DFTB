package ca.devdcs.backup.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import ca.devdcs.backup.core.boot.JobResumeWorker
import ca.devdcs.backup.core.logging.DftbLog

/**
 * Lets the companion module's late-boot `service.sh` wake DFTB's job-resume reconciliation.
 *
 * It exists because the systemless module runs as root during boot, sees the root-owned journal
 * under `/data/adb/dftb`, and can start the app's reconciliation earlier and more reliably than
 * waiting on the framework's own `BOOT_COMPLETED` delivery. The module issues
 * `am broadcast -a [ACTION_RESUME_JOBS] -n <app>/[COMPONENT_CLASS]`; this receiver enqueues the same
 * [JobResumeWorker] the OS boot receiver uses, so the two paths converge on one unique work item.
 *
 * Like the boot receiver it does no work inline — it only enqueues — and it ignores the broadcast
 * while the user is still locked, because WorkManager's store is not yet available then.
 */
class BootResumeTrigger : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RESUME_JOBS) {
            DftbLog.d(TAG, "Ignoring unexpected action: ${intent?.action}")
            return
        }

        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager?.isUserUnlocked == false) {
            DftbLog.i(TAG, "Resume broadcast received while locked; the boot receiver will retry after unlock")
            return
        }

        DftbLog.i(TAG, "Companion module requested job resume; enqueuing reconciliation")
        runCatching { JobResumeWorker.enqueue(context, JobResumeWorker.SOURCE_MODULE) }
            .onFailure { DftbLog.e(TAG, "Failed to enqueue job-resume worker from module trigger", it) }
    }

    companion object {
        private const val TAG = "ResumeTrigger"

        /** Custom action the companion module broadcasts from `service.sh`. */
        const val ACTION_RESUME_JOBS = "ca.devdcs.backup.action.RESUME_JOBS"

        /** Fully-qualified class name used by the module's explicit `am broadcast -n` target. */
        const val COMPONENT_CLASS = "ca.devdcs.backup.companion.BootResumeTrigger"
    }
}
