package ca.devdcs.backup.core.retention

import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.layout.TwrpConventions
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StoragePaths
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.domain.model.RetentionPolicy

/** Why a backup is pruned. */
enum class RetentionReason { BEYOND_KEEP_LAST, OLDER_THAN_MAX_AGE }

/** Why an expired backup is nevertheless kept. */
enum class RetentionHold {
    /** The newest successful backup of the profile is never deleted, whatever the policy. */
    NEWEST_SUCCESSFUL,
    /**
     * The newest fully COMPLETED backup: COMPLETED_WITH_WARNINGS may lack failed artifacts, so the
     * last clean backup is kept even when newer degraded ones fill keep-last.
     */
    NEWEST_COMPLETE,
    /** A running or resumable job still writes into this folder. */
    ACTIVE_JOB,
    /** The stored path is empty, escapes the destination or points at a TWRP folder. */
    UNSAFE_PATH,
    /** The backup may own a TWRP folder outside its job folder, but its manifest could not be read. */
    TWRP_FOLDER_UNKNOWN,
}

/**
 * A backup on a destination that has no History row (database reset, reinstall) but whose
 * manifest says it belongs to the profile. [twrpFolders] were read from that manifest.
 */
data class CatalogBackup(
    val destinationId: String,
    val jobDir: String,
    val createdAtMillis: Long,
    val twrpFolders: List<String> = emptyList(),
)

/** One backup to prune: its job folder, any TWRP folder outside it, and its History rows. */
data class RetentionDeletion(
    /** Null when the destination was deleted from DFTB: only the History rows can be dropped. */
    val destinationId: String?,
    /** Normalized job folder, or null when there is nothing to delete on the destination. */
    val jobDir: String?,
    val historyIds: List<String>,
    val createdAtMillis: Long,
    val reason: RetentionReason,
    /** TWRP/BACKUPS/<device>/<name> folders written verbatim beside the job folder. */
    val extraFolders: List<String> = emptyList(),
)

data class RetentionKept(
    val destinationId: String?,
    val jobDir: String?,
    val historyIds: List<String>,
    val hold: RetentionHold,
)

/** What retention would do (dry run) or did not do yet, for one profile. */
data class RetentionPlan(
    val profileId: String,
    val deletions: List<RetentionDeletion>,
    val kept: List<RetentionKept>,
) {
    val isEmpty: Boolean get() = deletions.isEmpty()

    companion object {
        fun empty(profileId: String) = RetentionPlan(profileId, emptyList(), emptyList())
    }
}

/**
 * Pure retention planning. Exists so the destructive part of retention is decided by tested
 * rules before anything is deleted:
 * - History-tracked backups expire exactly as [ca.devdcs.backup.domain.repository.HistoryRepository.expiredRecords]
 * says (the two-step contract: the caller deletes on the destination, then drops the rows);
 * - untracked backups found on the destination are ranked together with tracked ones and expire by
 * the same keep-last / max-age rule;
 * - the newest successful backup, folders of active jobs and unsafe paths are always held.
 */
object RetentionPlanner {

    /**
     * @param successful every COMPLETED / COMPLETED_WITH_WARNINGS BACKUP record of the profile with
     * an artifact path (used for ranking and to find the newest successful backup).
     * @param expired what the History repository reports as expired for [policy].
     * @param untracked backups on the destination without History rows (ownership already verified).
     * @param activeJobs (destinationId, jobDir) of the profile's non-terminal checkpoints.
     */
    fun plan(
        profileId: String,
        policy: RetentionPolicy,
        nowMillis: Long,
        successful: List<HistoryRecord>,
        expired: List<HistoryRecord>,
        untracked: List<CatalogBackup>,
        activeJobs: Set<Pair<String?, String>>,
    ): RetentionPlan {
        if (policy.isUnlimited) return RetentionPlan.empty(profileId)
        val cutoff = policy.cutoffMillis(nowMillis)
        val owned = successful.filter { it.profileId == profileId && it.type == JobType.BACKUP && it.isSuccessful && it.artifactPath != null }
        val trackedKeys = owned.mapNotNull { rec -> safeDir(rec.artifactPath)?.let { key(rec.destinationId, it) } }.toSet()
        val untrackedOwn = untracked
            .mapNotNull { b -> safeDir(b.jobDir)?.let { b.copy(jobDir = it) } }
            .filter { key(it.destinationId, it.jobDir) !in trackedKeys }
            .distinctBy { key(it.destinationId, it.jobDir) }

        // Rank everything the profile owns, newest first, to protect the newest and rank untracked ones.
        data class Ranked(val key: String, val createdAt: Long, val untracked: CatalogBackup?)
        val ranked = (owned.map { Ranked(key(it.destinationId, safeDir(it.artifactPath) ?: it.id), it.startedAt, null) } +
            untrackedOwn.map { Ranked(key(it.destinationId, it.jobDir), it.createdAtMillis, it) })
            .sortedWith(compareByDescending<Ranked> { it.createdAt }.thenBy { it.key })
        val newestKey = ranked.firstOrNull()?.key
        val newestTracked = owned.maxByOrNull { it.startedAt }
        val protectedKeys = setOfNotNull(newestKey, newestTracked?.let { key(it.destinationId, safeDir(it.artifactPath) ?: it.id) })
        val newestComplete = owned.filter { it.status == JobStatus.COMPLETED }.maxByOrNull { it.startedAt }
        val completeKey = newestComplete?.let { key(it.destinationId, safeDir(it.artifactPath) ?: it.id) }
        val activeKeys = activeJobs.mapNotNull { (dest, dir) -> safeDir(dir)?.let { key(dest, it) } }.toSet()

        val deletions = ArrayList<RetentionDeletion>()
        val kept = ArrayList<RetentionKept>()

        // Tracked: grouped by folder so two rows of one job become one deletion.
        expired.filter { it.profileId == profileId && it.type == JobType.BACKUP && it.isSuccessful && it.artifactPath != null }
            .groupBy { rec -> safeDir(rec.artifactPath)?.let { key(rec.destinationId, it) } ?: "unsafe:${rec.id}" }
            .forEach { (k, records) ->
                val first = records.maxBy { it.startedAt }
                val ids = records.map { it.id }
                val dir = safeDir(first.artifactPath)
                when {
                    dir == null -> kept += RetentionKept(first.destinationId, first.artifactPath, ids, RetentionHold.UNSAFE_PATH)
                    k in protectedKeys || first.id == newestTracked?.id -> kept += RetentionKept(first.destinationId, dir, ids, RetentionHold.NEWEST_SUCCESSFUL)
                    k == completeKey -> kept += RetentionKept(first.destinationId, dir, ids, RetentionHold.NEWEST_COMPLETE)
                    k in activeKeys -> kept += RetentionKept(first.destinationId, dir, ids, RetentionHold.ACTIVE_JOB)
                    else -> deletions += RetentionDeletion(
                        destinationId = first.destinationId,
                        jobDir = if (first.destinationId == null) null else dir,
                        historyIds = ids,
                        createdAtMillis = first.startedAt,
                        reason = reasonFor(first.startedAt, cutoff),
                    )
                }
            }

        // Untracked: same keep-last / max-age rule over the combined ranking.
        ranked.forEachIndexed { index, r ->
            val b = r.untracked ?: return@forEachIndexed
            val beyondKeep = policy.keepLast?.let { index >= it.coerceAtLeast(0) } == true
            val tooOld = cutoff != null && b.createdAtMillis < cutoff
            if (!beyondKeep && !tooOld) return@forEachIndexed
            when {
                r.key in protectedKeys -> kept += RetentionKept(b.destinationId, b.jobDir, emptyList(), RetentionHold.NEWEST_SUCCESSFUL)
                r.key in activeKeys -> kept += RetentionKept(b.destinationId, b.jobDir, emptyList(), RetentionHold.ACTIVE_JOB)
                else -> deletions += RetentionDeletion(
                    destinationId = b.destinationId,
                    jobDir = b.jobDir,
                    historyIds = emptyList(),
                    createdAtMillis = b.createdAtMillis,
                    reason = if (tooOld) RetentionReason.OLDER_THAN_MAX_AGE else RetentionReason.BEYOND_KEEP_LAST,
                    extraFolders = b.twrpFolders.mapNotNull(::safeTwrpFolder).distinct(),
                )
            }
        }
        return RetentionPlan(profileId, deletions.sortedBy { it.createdAtMillis }, kept)
    }

    /**
     * TWRP folders a backup wrote outside its job folder: verbatim FILE_TREE artifacts have
     * treeRoot "" and items stored under `TWRP/BACKUPS/<device>/<name>/…`; the folder to remove is
     * exactly those four segments (never the device folder, which holds other backups).
     */
    fun twrpFolders(manifest: BackupManifest): List<String> =
        manifest.artifacts.asSequence()
            .filter { it.treeRoot == "" }
            .flatMap { artifact -> artifact.items.asSequence().mapNotNull { it.storedPath } }
            .mapNotNull { path -> runCatching { StoragePaths.segments(path) }.getOrNull() }
            .filter { it.size > TWRP_DEPTH && isTwrpPrefix(it) }
            .map { it.take(TWRP_DEPTH).joinToString("/") }
            .distinct()
            .toList()

    /** Normalized TWRP backup folder (exactly TWRP/BACKUPS/<device>/<name>), or null. */
    fun safeTwrpFolder(path: String): String? {
        val segs = try {
            StoragePaths.segments(path)
        } catch (e: StorageException) {
            return null
        }
        return if (segs.size == TWRP_DEPTH && isTwrpPrefix(segs)) segs.joinToString("/") else null
    }

    /** Normalized job folder, or null when it is empty, escapes the base or is a TWRP path. */
    fun safeDir(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val segs = try {
            StoragePaths.segments(path)
        } catch (e: StorageException) {
            return null
        }
        if (segs.isEmpty()) return null
        if (segs.first() == TWRP_ROOT_SEGMENTS[0]) return null
        return segs.joinToString("/")
    }

    private fun isTwrpPrefix(segs: List<String>): Boolean =
        segs.size >= TWRP_ROOT_SEGMENTS.size && segs.subList(0, TWRP_ROOT_SEGMENTS.size) == TWRP_ROOT_SEGMENTS

    private fun reasonFor(createdAt: Long, cutoff: Long?): RetentionReason =
        if (cutoff != null && createdAt < cutoff) RetentionReason.OLDER_THAN_MAX_AGE else RetentionReason.BEYOND_KEEP_LAST

    private fun key(destinationId: String?, dir: String): String = "${destinationId.orEmpty()}|$dir"

    private val TWRP_ROOT_SEGMENTS: List<String> = TwrpConventions.ROOT_FOLDER.split('/')
    private const val TWRP_DEPTH = 4
}
