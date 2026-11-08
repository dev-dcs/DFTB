package ca.devdcs.backup.core.schedule

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import ca.devdcs.backup.core.boot.StaleJobRepair
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.ProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the WorkManager side of every profile's schedule: one unique periodic work per profile id,
 * re-enqueued (UPDATE) when the profile's schedule, destination or enabled flag changes, cancelled
 * when the profile is deleted, disabled or loses its schedule. Also the entry point for
 * "Back up now" ([runNow]) and checkpoint resume ([enqueueResume]).
 *
 * Exists so scheduling decisions live in one place: the UI saves profiles, this class observes
 * the repository and keeps WorkManager in step, and boot / app-update / time-zone changes call
 * [syncAll]. Every enqueue is idempotent (UPDATE for periodic, KEEP for one-offs).
 *
 * WorkManager is resolved lazily: this class may be injected into the Application, whose
 * WorkManager configuration (the Hilt worker factory) is not ready until injection finished.
 */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val destinationRepository: DestinationRepository,
    private val staleJobRepair: StaleJobRepair,
    private val clock: EngineClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private val started = AtomicBoolean(false)
    private val lock = Mutex()

    /** profileId → fingerprint of the spec last handed to WorkManager in this process. */
    private val applied = HashMap<String, String>()

    /**
     * Starts observing [ProfileRepository]; idempotent. Call once from Application.onCreate. The
     * first emission is a full sync that keeps already-enqueued work (KEEP) and cancels orphans;
     * later emissions only re-enqueue (UPDATE) or cancel profiles whose inputs changed.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // A process that died without a reboot leaves RUNNING rows; make them resumable now.
        scope.launch {
            try {
                staleJobRepair.repair()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "Stale job repair failed", e)
            }
        }
        scope.launch {
            var first = true
            profileRepository.observeAll().collect { profiles ->
                try {
                    reconcile(profiles, full = first, catchUp = false, keepExisting = first)
                    first = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DftbLog.e(TAG, "Schedule reconciliation failed", e)
                }
            }
        }
        DftbLog.i(TAG, "Schedule observer started")
    }

    /**
     * Re-arms every profile from scratch (boot, app update, time-zone / clock change). Owed slots
     * of `runOnBootIfMissed` profiles become due immediately when [catchUp] is true.
     */
    suspend fun syncAll(reason: String, catchUp: Boolean = true) {
        DftbLog.i(TAG, "Full schedule sync ($reason)")
        reconcile(profileRepository.getAll(), full = true, catchUp = catchUp, force = true)
    }

    /**
     * (Re)arms [profile]'s periodic work at its next slot, or cancels it when it must not run.
     * Called by the worker after each run so the next run lands exactly on the next slot.
     */
    suspend fun schedule(profile: BackupProfile, catchUp: Boolean = false) {
        lock.withLock { applyProfile(profile, destinationTypeOf(profile), catchUp, force = true) }
    }

    suspend fun cancel(profileId: String) {
        lock.withLock { cancelLocked(profileId) }
    }

    /**
     * Expedited one-off backup of [profileId] now (any network for network destinations). A run
     * already queued or running for the profile is kept, so double taps do one backup.
     */
    suspend fun runNow(profileId: String) {
        enqueueOneTime(profileId, BackupWorker.TRIGGER_MANUAL, resumeJobId = null)
    }

    /** Continues the interrupted backup [jobId] of [profileId] (boot resume). */
    suspend fun enqueueResume(profileId: String, jobId: String) {
        enqueueOneTime(profileId, BackupWorker.TRIGGER_RESUME, resumeJobId = jobId)
    }

    private suspend fun enqueueOneTime(profileId: String, trigger: String, resumeJobId: String?) {
        val profile = profileRepository.get(profileId)
        val type = profile?.let { destinationTypeOf(it) }
        val request = WorkRequestFactory.oneTime(profileId, trigger, resumeJobId, ScheduleSpecs.immediateConstraints(type))
        awaitQuietly("enqueue $trigger for $profileId") {
            workManager.enqueueUniqueWork(WorkRequestFactory.oneTimeName(profileId, trigger), ExistingWorkPolicy.KEEP, request).await()
        }
        DftbLog.i(TAG, "Enqueued $trigger run for profile $profileId${resumeJobId?.let { " (job $it)" }.orEmpty()}")
    }

    /**
     * [keepExisting] (process start) leaves already-enqueued work untouched (KEEP) so a pending
     * backoff retry or owed run survives an app launch; otherwise specs are replaced (UPDATE).
     */
    private suspend fun reconcile(profiles: List<BackupProfile>, full: Boolean, catchUp: Boolean, force: Boolean = false, keepExisting: Boolean = false) {
        lock.withLock {
            val byId = profiles.associateBy { it.id }
            profiles.forEach { profile -> applyProfile(profile, destinationTypeOf(profile), catchUp, force || full, keepExisting) }
            // Profiles that disappeared since the last emission.
            (applied.keys - byId.keys).toList().forEach { cancelLocked(it) }
            if (full) cancelOrphans(byId)
        }
    }

    private suspend fun applyProfile(profile: BackupProfile, type: DestinationType?, catchUp: Boolean, force: Boolean, keepExisting: Boolean = false) {
        val now = clock.nowMillis()
        val spec = ScheduleSpecs.build(profile, type, now, clock.zone(), catchUp)
        if (spec == null) {
            val problem = profile.schedule?.problem
            if (profile.enabled && problem != null) {
                DftbLog.w(TAG, "Profile ${profile.id} has an invalid schedule ($problem); not scheduling")
            }
            // Manual-only profiles are cancelled once per full sync, not on every emission.
            if (force || applied.containsKey(profile.id)) cancelLocked(profile.id)
            return
        }
        if (!force && applied[profile.id] == spec.fingerprint) return
        if (!keepExisting && isBackedOffWithSameSpec(profile.id, spec.fingerprint)) {
            // UPDATE would put the next-slot override ahead of the pending backoff retry and skip
            // the failed run; an unchanged spec has nothing to update anyway.
            applied[profile.id] = spec.fingerprint
            DftbLog.i(TAG, "Profile ${profile.id} is waiting for a retry; keeping its pending work")
            return
        }
        val ok = awaitQuietly("schedule ${profile.id}") {
            workManager.enqueueUniquePeriodicWork(
                WorkRequestFactory.periodicName(profile.id),
                if (keepExisting) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE,
                WorkRequestFactory.periodic(spec),
            ).await()
        }
        if (ok) {
            applied[profile.id] = spec.fingerprint
            DftbLog.i(TAG, "Scheduled profile ${profile.id}: next run at ${spec.nextRunAtMillis}, period ${spec.periodMillis} ms, network ${spec.constraints.network}")
        }
    }

    private suspend fun isBackedOffWithSameSpec(profileId: String, fingerprint: String): Boolean = try {
        val tag = WorkRequestFactory.fingerprintTag(fingerprint)
        workManager.getWorkInfosForUniqueWorkFlow(WorkRequestFactory.periodicName(profileId)).first()
            .any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 && tag in it.tags }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DftbLog.w(TAG, "Could not read pending work of $profileId", e)
        false
    }

    private suspend fun cancelLocked(profileId: String) {
        applied.remove(profileId)
        awaitQuietly("cancel $profileId") { workManager.cancelUniqueWork(WorkRequestFactory.periodicName(profileId)).await() }
    }

    /** Periodic work whose profile no longer exists (deleted while the process was dead). */
    private suspend fun cancelOrphans(profiles: Map<String, BackupProfile>) {
        val infos: List<WorkInfo> = try {
            workManager.getWorkInfosByTagFlow(WorkRequestFactory.TAG_PERIODIC).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Could not list scheduled work", e)
            return
        }
        infos.filter { !it.state.isFinished }
            .mapNotNull { WorkRequestFactory.profileIdFromTags(it.tags) }
            .toSet()
            .filter { id -> profiles[id]?.isScheduled != true }
            .forEach { id ->
                DftbLog.i(TAG, "Cancelling orphaned schedule of profile $id")
                cancelLocked(id)
            }
    }

    private suspend fun destinationTypeOf(profile: BackupProfile): DestinationType? {
        val id = profile.destinationId ?: return null
        return try {
            destinationRepository.get(id)?.type
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Could not read destination $id", e)
            null
        }
    }

    private suspend fun awaitQuietly(label: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DftbLog.e(TAG, "WorkManager operation failed: $label", e)
        false
    }

    private companion object {
        const val TAG = "Scheduler"
    }
}
