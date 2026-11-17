package ca.devdcs.backup.core.retention

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.engine.BackupJob
import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.restore.BackupCatalog
import ca.devdcs.backup.core.restore.BackupSummary
import ca.devdcs.backup.core.storage.StorageRegistry
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.HashRegistryRepository
import ca.devdcs.backup.domain.repository.HistoryRepository
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Result of deleting one planned backup. */
data class RetentionDeleted(val deletion: RetentionDeletion, val removedEntries: Int)

data class RetentionFailed(val deletion: RetentionDeletion, val error: String)

/** What [RetentionExecutor.apply] did (or, for a dry run, would do). */
data class RetentionReport(
    val profileId: String,
    val dryRun: Boolean,
    val plan: RetentionPlan,
    val deleted: List<RetentionDeleted> = emptyList(),
    val failed: List<RetentionFailed> = emptyList(),
    /** Destinations whose catalog could not be listed (untracked backups there were not considered). */
    val unlistedDestinations: List<String> = emptyList(),
)

/**
 * Applies a profile's [ca.devdcs.backup.domain.model.RetentionPolicy] to its stored backups.
 *
 * Exists to turn the History repository's two-step retention contract into actual deletions:
 * expired jobs come from [HistoryRepository.expiredRecords] and from the destination's
 * [BackupCatalog] (backups whose History rows are gone but whose manifest names this profile);
 * [RetentionPlanner] decides; this class then deletes each job folder bottom-up through a
 * [ca.devdcs.backup.core.storage.StorageSession], plus the TWRP folder a verbatim Nandroid
 * artifact wrote beside it, and only then drops the History rows and hash records. A dry run
 * returns the plan without touching anything.
 *
 * Never throws for environment problems: an unreachable destination or a failed delete is
 * reported in [RetentionReport.failed] and the rows stay, so the next run retries.
 */
@Singleton
class RetentionExecutor @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val checkpointRepository: CheckpointRepository,
    private val destinationRepository: DestinationRepository,
    private val hashRegistryRepository: HashRegistryRepository,
    private val catalog: BackupCatalog,
    private val storageRegistry: StorageRegistry,
    private val credentialVault: CredentialVault,
    private val clock: EngineClock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * @param includeCatalog also list the destination(s) for backups History no longer knows.
     */
    suspend fun apply(profile: BackupProfile, dryRun: Boolean = false, includeCatalog: Boolean = true): RetentionReport = withContext(io) {
        val policy = profile.retention
        if (policy.isUnlimited) return@withContext RetentionReport(profile.id, dryRun, RetentionPlan.empty(profile.id))
        val now = clock.nowMillis()

        val successful = historyRepository.getByStatus(SUCCESS_STATUSES)
            .filter { it.profileId == profile.id && it.type == JobType.BACKUP && it.artifactPath != null }
        val expired = historyRepository.expiredRecords(profile.id, policy, now)
        val activeJobs = checkpointRepository.getForProfile(profile.id)
            .filter { !it.status.isTerminal }
            .mapNotNull { cp ->
                cp.state.extras[BackupJob.EXTRA_JOB_DIR]?.let { dir -> (cp.state.extras[BackupJob.EXTRA_DESTINATION_ID] ?: profile.destinationId) to dir }
            }
            .toSet()

        val destinations = destinationsOf(profile, successful)
        val unlisted = ArrayList<String>()
        val untracked = if (includeCatalog) {
            findUntracked(profile, destinations, successful, now, expired, activeJobs, unlisted)
        } else {
            emptyList()
        }

        var plan = RetentionPlanner.plan(profile.id, policy, now, successful, expired, untracked, activeJobs)
        plan = resolveTwrpFolders(profile, plan, destinations)

        if (dryRun || plan.isEmpty) {
            if (!plan.isEmpty) DftbLog.i(TAG, "Retention dry run for ${profile.id}: ${plan.deletions.size} backup(s) would be pruned")
            return@withContext RetentionReport(profile.id, dryRun, plan, unlistedDestinations = unlisted)
        }
        execute(profile.id, plan, destinations, unlisted)
    }

    // --- Planning inputs ------------------------------------------------------------------------

    private suspend fun destinationsOf(profile: BackupProfile, successful: List<HistoryRecord>): Map<String, Destination> {
        val ids = (successful.mapNotNull { it.destinationId } + listOfNotNull(profile.destinationId)).toSet()
        return ids.mapNotNull { id -> guard("read destination $id") { destinationRepository.get(id) } }.associateBy { it.id }
    }

    /**
     * Untracked backups owned by [profile]. Cheap prefilter by profile name, then a first plan over
     * all candidates; only those it would delete are opened to confirm the manifest's profile id
     * (removing unconfirmed ones can only make the remaining ones rank newer, never older).
     */
    private suspend fun findUntracked(
        profile: BackupProfile,
        destinations: Map<String, Destination>,
        successful: List<HistoryRecord>,
        now: Long,
        expired: List<HistoryRecord>,
        activeJobs: Set<Pair<String?, String>>,
        unlisted: MutableList<String>,
    ): List<CatalogBackup> {
        // Any History row (even failed or interrupted) means the folder is not "untracked".
        val others = guard("read other history") { historyRepository.getByStatus(OTHER_STATUSES) }.orEmpty()
            .filter { it.profileId == profile.id && it.artifactPath != null }
        val tracked = (successful + others).mapNotNull { rec -> RetentionPlanner.safeDir(rec.artifactPath)?.let { rec.destinationId to it } }.toSet()
        val summaries = ArrayList<BackupSummary>()
        for (dest in destinations.values) {
            val listed = guard("list backups on ${dest.id}") { catalog.list(dest) }
            if (listed == null) {
                unlisted += dest.id
                continue
            }
            summaries += listed.filter { s ->
                val dir = RetentionPlanner.safeDir(s.jobDir)
                dir != null && s.createdAtMillis != null && (dest.id to dir) !in tracked &&
                    (s.profileName == null || s.profileName == profile.name)
            }
        }
        if (summaries.isEmpty()) return emptyList()

        val candidates = summaries.map { CatalogBackup(it.destinationId, it.jobDir, it.createdAtMillis ?: 0L) }
        val firstPass = RetentionPlanner.plan(profile.id, profile.retention, now, successful, expired, candidates, activeJobs)
        val toConfirm = firstPass.deletions.filter { it.historyIds.isEmpty() && it.jobDir != null && it.destinationId != null }
        val confirmed = ArrayList<CatalogBackup>()
        for (deletion in toConfirm) {
            val dest = destinations[deletion.destinationId] ?: continue
            val jobDir = deletion.jobDir ?: continue
            val owned = withOpened(profile, dest, jobDir) { manifest ->
                if (manifest.profile.id == profile.id) {
                    CatalogBackup(dest.id, jobDir, manifest.createdAtMillis, RetentionPlanner.twrpFolders(manifest))
                } else {
                    null
                }
            }
            if (owned != null) confirmed += owned
        }
        // Unconfirmed candidates are dropped entirely, which only makes the confirmed ones rank newer.
        return confirmed
    }

    /** Fills [RetentionDeletion.extraFolders] for tracked deletions from each job's manifest. */
    private suspend fun resolveTwrpFolders(profile: BackupProfile, plan: RetentionPlan, destinations: Map<String, Destination>): RetentionPlan {
        val deletions = ArrayList<RetentionDeletion>()
        val kept = ArrayList(plan.kept)
        for (deletion in plan.deletions) {
            val jobDir = deletion.jobDir
            val dest = deletion.destinationId?.let { destinations[it] }
            if (jobDir == null || dest == null || deletion.historyIds.isEmpty()) {
                // History-only rows, untracked backups (folders read while confirming them), or an
                // unreadable destination row (execute() reports it and keeps the History rows).
                deletions += deletion
                continue
            }
            val folders = withOpened(profile, dest, jobDir) { manifest -> RetentionPlanner.twrpFolders(manifest) }
            when {
                folders != null -> deletions += deletion.copy(extraFolders = folders.mapNotNull(RetentionPlanner::safeTwrpFolder).distinct())
                // A TWRP profile whose manifest cannot be read would orphan its TWRP folder forever.
                profile.pipeline.twrpCompatible ->
                    kept += RetentionKept(deletion.destinationId, jobDir, deletion.historyIds, RetentionHold.TWRP_FOLDER_UNKNOWN)
                else -> deletions += deletion
            }
        }
        return plan.copy(deletions = deletions, kept = kept)
    }

    // --- Execution ------------------------------------------------------------------------------

    private suspend fun execute(
        profileId: String,
        plan: RetentionPlan,
        destinations: Map<String, Destination>,
        unlisted: List<String>,
    ): RetentionReport {
        val deleted = ArrayList<RetentionDeleted>()
        val failed = ArrayList<RetentionFailed>()

        // Rows whose destination is gone: nothing to delete remotely.
        plan.deletions.filter { it.jobDir == null }.forEach { deletion ->
            if (dropRows(deletion)) deleted += RetentionDeleted(deletion, 0) else failed += RetentionFailed(deletion, "history")
        }

        plan.deletions.filter { it.jobDir != null }.groupBy { it.destinationId }.forEach { (destId, group) ->
            val dest = destId?.let { destinations[it] }
            if (dest == null) {
                group.forEach { failed += RetentionFailed(it, "Destination $destId not found") }
                return@forEach
            }
            try {
                storageRegistry.withSession(dest) { session ->
                    for (deletion in group) {
                        try {
                            var removed = 0
                            // TWRP folder first: if the job folder delete then fails, a retry still finds it.
                            deletion.extraFolders.forEach { removed += RemoteTreeDeleter.deleteTree(session, it) }
                            removed += RemoteTreeDeleter.deleteTree(session, requireNotNull(deletion.jobDir))
                            if (dropRows(deletion)) {
                                deleted += RetentionDeleted(deletion, removed)
                            } else {
                                failed += RetentionFailed(deletion, "Deleted on destination; History rows not removed")
                            }
                            DftbLog.i(TAG, "Pruned ${deletion.jobDir} on ${dest.id} (${deletion.reason}, $removed entries)")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            DftbLog.w(TAG, "Could not prune ${deletion.jobDir} on ${dest.id}", e)
                            failed += RetentionFailed(deletion, e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Could not connect to ${dest.id} for retention", e)
                val handled = (deleted.map { it.deletion } + failed.map { it.deletion }).toSet()
                group.filter { it !in handled }.forEach { failed += RetentionFailed(it, e.message ?: e.javaClass.simpleName) }
            }
        }
        DftbLog.i(TAG, "Retention for $profileId: ${deleted.size} pruned, ${failed.size} failed, ${plan.kept.size} held")
        return RetentionReport(profileId, dryRun = false, plan = plan, deleted = deleted, failed = failed, unlistedDestinations = unlisted)
    }

    /** Second step of the History contract: rows go only after the destination delete succeeded. */
    private suspend fun dropRows(deletion: RetentionDeletion): Boolean {
        if (deletion.historyIds.isEmpty()) return true
        return guard("drop history rows") {
            historyRepository.deleteByIds(deletion.historyIds)
            deletion.historyIds.forEach { id ->
                hashRegistryRepository.deleteForBackup(id)
                checkpointRepository.get(id)?.takeIf { it.status.isTerminal }?.let { checkpointRepository.delete(id) }
            }
            true
        } ?: false
    }

    // --- Manifest access ------------------------------------------------------------------------

    private suspend fun <T> withOpened(
        profile: BackupProfile,
        destination: Destination,
        jobDir: String,
        block: (BackupManifest) -> T?,
    ): T? {
        val key = keyFor(profile)
        return try {
            catalog.open(destination, jobDir, key).use { opened -> block(opened.manifest) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Could not read the manifest of $jobDir on ${destination.id}: ${e.message}")
            null
        } finally {
            (key as? CipherKeySpec.Passphrase)?.chars?.fill('\u0000')
        }
    }

    /** Key for reading an encrypted manifest: the profile's vault key or vault-held passphrase. */
    private suspend fun keyFor(profile: BackupProfile): CipherKeySpec? {
        if (!profile.pipeline.isEncrypted) return null
        val ref = profile.pipeline.encryptionKeyRef ?: return null
        return when (val secret = guard("load key") { credentialVault.load(ref) }) {
            is Secret.EncryptionKey -> CipherKeySpec.VaultKey(ref)
            is Secret.Password -> CipherKeySpec.Passphrase(secret.password.toCharArray())
            else -> null
        }
    }

    private suspend fun <T> guard(label: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DftbLog.w(TAG, "Retention: could not $label", e)
        null
    }

    private companion object {
        const val TAG = "Retention"
        val SUCCESS_STATUSES = setOf(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS)
        val OTHER_STATUSES = JobStatus.entries.toSet() - SUCCESS_STATUSES
    }
}
