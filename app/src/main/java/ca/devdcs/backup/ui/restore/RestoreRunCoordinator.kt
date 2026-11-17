package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.core.restore.engine.RestoreEngine
import ca.devdcs.backup.core.restore.engine.RestoreOptions
import ca.devdcs.backup.core.restore.engine.RestoreRequest
import ca.devdcs.backup.core.restore.files.FileRestoreSpec
import ca.devdcs.backup.core.restore.files.FileRestoreTarget
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.ui.destinations.DestinationHousekeeping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What to restore, as decided by the wizard. Not a data class: it carries key material, which the
 * coordinator copies; the caller keeps (and wipes) its own [key].
 */
class RestoreRunSpec(
    val destinationId: String,
    val jobDir: String,
    val key: CipherKeySpec?,
    val units: List<RestoreUnitInfo>,
    val fileSpec: FileRestoreSpec,
    val options: RestoreOptions,
    val profileId: String?,
    val title: String?,
    /** A SAF grant taken only for this restore; released afterwards unless a destination uses it. */
    val releaseTreeUri: String?,
)

/**
 * Runs restores on the application scope and keeps their live state, so a restore keeps going when
 * its screen is left or recreated and any screen can show it again. Each run's key is a private
 * copy wiped as soon as the engine finishes. Cancellation goes through [JobProgressBus] so the
 * engine stops at a safe point and records CANCELLED (a partition write in progress is completed
 * first by the engine, never abandoned half-way).
 */
@Singleton
class RestoreRunCoordinator @Inject constructor(
    private val engine: RestoreEngine,
    private val progressBus: JobProgressBus,
    private val housekeeping: DestinationHousekeeping,
    private val clock: EngineClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _runs = MutableStateFlow<Map<String, RestoreRunState>>(emptyMap())

    /** Every run started in this process and not dismissed, keyed by job id. */
    val runs: StateFlow<Map<String, RestoreRunState>> = _runs.asStateFlow()

    /** Starts [spec] and returns the job id. */
    fun start(spec: RestoreRunSpec): String {
        val key = spec.key?.let(BackupUnlocker::copyKey)
        val request = RestoreRequest(
            destinationId = spec.destinationId,
            jobDir = spec.jobDir,
            key = key,
            unitIds = spec.units.map { it.id }.toSet(),
            fileSpec = spec.fileSpec,
            options = spec.options,
            jobId = UUID.randomUUID().toString(),
            profileId = spec.profileId,
            title = spec.title,
        )
        val jobId = request.jobId
        val initial = RestoreRunState(
            jobId = jobId,
            destinationId = spec.destinationId,
            jobDir = spec.jobDir,
            title = spec.title,
            units = spec.units,
            startedAtMillis = clock.nowMillis(),
            treeUri = (spec.fileSpec.target as? FileRestoreTarget.SafTree)?.treeUri,
        )
        _runs.update { it + (jobId to initial) }
        scope.launch {
            try {
                engine.restore(request).collect { event -> mutate(jobId) { RestoreRunReducer.reduce(it, event) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "Restore $jobId event flow failed", e)
                mutate(jobId) { RestoreRunReducer.crashed(it, e.message ?: e.javaClass.simpleName, BackupUnlocker.failureOf(e)) }
            } finally {
                BackupUnlocker.wipeKey(key)
                spec.releaseTreeUri?.let { uri ->
                    withContext(NonCancellable) {
                        try {
                            housekeeping.releaseTreeIfUnused(uri)
                        } catch (e: Exception) {
                            DftbLog.w(TAG, "Could not release the restore folder grant", e)
                        }
                    }
                }
            }
        }
        return jobId
    }

    /** Asks the engine to stop at its next safe point. */
    fun cancel(jobId: String) {
        val run = _runs.value[jobId] ?: return
        if (run.finished) return
        mutate(jobId) { it.copy(cancelRequested = true) }
        progressBus.requestCancel(jobId)
    }

    /** Forgets a finished run (its History record stays). */
    fun dismiss(jobId: String) {
        _runs.update { map -> if (map[jobId]?.finished == true) map - jobId else map }
    }

    /** The newest run of one backup, finished or not. */
    fun latestFor(destinationId: String, jobDir: String): RestoreRunState? =
        _runs.value.values.filter { it.destinationId == destinationId && it.jobDir == jobDir }.maxByOrNull { it.startedAtMillis }

    /** True while any unfinished run uses [treeUri] as its target (so a screen must not release it). */
    fun usesTree(treeUri: String): Boolean = _runs.value.values.any { run ->
        !run.finished && run.treeUri == treeUri
    }

    private fun mutate(jobId: String, transform: (RestoreRunState) -> RestoreRunState) {
        _runs.update { map -> map[jobId]?.let { map + (jobId to transform(it)) } ?: map }
    }

    private companion object {
        const val TAG = "RestoreRuns"
    }
}
