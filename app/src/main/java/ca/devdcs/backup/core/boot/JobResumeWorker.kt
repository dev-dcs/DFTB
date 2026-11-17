package ca.devdcs.backup.core.boot

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.devdcs.backup.companion.ModuleManifest
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.schedule.BackupScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Brings unattended work back after a reboot or process restart.
 *
 * It exists so a backup that was running when the device rebooted (or the process was killed) is
 * continued rather than silently lost, and so schedules are re-armed: on boot the OS fires
 * [BootCompletedReceiver], and the companion module's late-boot service fires
 * [ca.devdcs.backup.companion.BootResumeTrigger]; both enqueue this worker. It
 * 1. re-arms every profile's periodic schedule (idempotent UPDATE; owed slots of
 * `runOnBootIfMissed` profiles become due now),
 * 2. hands the journal to [ResumeCoordinator], which repairs stale RUNNING rows (only rows not
 * updated since this process started), re-runs INTERRUPTED backups flagged resume-after-reboot
 * from their checkpoint, and notifies the user about passphrase profiles and restores,
 * 3. cross-checks the companion module's journal directory when readable.
 *
 * Each step is isolated so one failure does not prevent the others.
 */
@HiltWorker
class JobResumeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: BackupScheduler,
    private val resumeCoordinator: ResumeCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val source = inputData.getString(KEY_SOURCE) ?: SOURCE_UNKNOWN
        DftbLog.i(TAG, "Job-resume pass started (source=$source)")
        var ok = true

        ok = step("re-arm schedules") { scheduler.syncAll("resume:$source", catchUp = true) } && ok

        ok = step("resume interrupted jobs") {
            val now = System.currentTimeMillis()
            // A RUNNING row not updated since this process started cannot be a live job (jobs run
            // in-process and write their row on start); rows written after process start are left
            // alone because this worker can be triggered while a job runs.
            val processStartedAt = now - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
            resumeCoordinator.resumeAfterBoot(processStartedAt)
        } && ok

        reconcileCompanionJournal()

        DftbLog.i(TAG, "Job-resume pass finished (ok=$ok)")
        // Failures are logged; retrying a whole boot pass would repeat the parts that succeeded.
        return if (ok) Result.success() else Result.failure()
    }

    private suspend fun step(label: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DftbLog.e(TAG, "Job-resume step failed: $label", e)
        false
    }

    /**
     * Inspect the companion module's journal directory if this process can read it. The directory
     * lives under `/data/adb` (uid 0, mode 0700), so an unprivileged app normally cannot read it;
     * that is expected and logged, not an error. The Room journal is the authoritative source either
     * way — this is only a cross-check for root/module installs.
     */
    private fun reconcileCompanionJournal() {
        val journalDir = File(ModuleManifest.JOURNAL_DIR)
        if (!journalDir.exists()) {
            DftbLog.d(TAG, "Companion journal dir ${ModuleManifest.JOURNAL_DIR} absent (module not installed)")
            return
        }
        if (!journalDir.canRead()) {
            DftbLog.d(TAG, "Companion journal dir present but root-owned; skipping (root-managed)")
            return
        }
        val stateFiles = journalDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList().orEmpty()
        DftbLog.i(TAG, "Companion journal readable: ${stateFiles.size} state file(s) present")
    }

    companion object {
        private const val TAG = "JobResume"

        /** Unique-work name so multiple boot broadcasts collapse into one pass. */
        const val UNIQUE_WORK_NAME = "ca.devdcs.backup.job_resume"
        const val KEY_SOURCE = "source"
        const val SOURCE_BOOT = "boot_completed"
        const val SOURCE_MODULE = "companion_module"
        const val SOURCE_UNKNOWN = "unknown"

        /**
         * Enqueue a single pass, keeping any already-queued run (KEEP) so a device that fires
         * `BOOT_COMPLETED`, `QUICKBOOT_POWERON` and the module broadcast does the work once.
         */
        fun enqueue(context: Context, source: String) {
            val request = OneTimeWorkRequestBuilder<JobResumeWorker>()
                .addTag(UNIQUE_WORK_NAME)
                .setInputData(Data.Builder().putString(KEY_SOURCE, source).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            DftbLog.i(TAG, "Enqueued job-resume pass (source=$source)")
        }
    }
}
