package ca.devdcs.backup.ui.profiles

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.SourceEstimate
import ca.devdcs.backup.core.pipeline.engine.BackupPlan
import ca.devdcs.backup.core.pipeline.engine.BackupPlanner
import ca.devdcs.backup.core.pipeline.engine.BackupSourceFactory
import ca.devdcs.backup.core.pipeline.engine.SourceDescriptor
import ca.devdcs.backup.core.pipeline.engine.SourceIssue
import ca.devdcs.backup.core.pipeline.engine.SourceKind
import ca.devdcs.backup.core.pipeline.engine.StagingArea
import ca.devdcs.backup.core.pipeline.engine.TransferPlanner
import ca.devdcs.backup.core.pipeline.engine.TransferResolution
import ca.devdcs.backup.core.storage.FreeSpaceVerifier
import ca.devdcs.backup.core.storage.PreflightResult
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One source's estimate as the review page lists it. */
data class SourceEstimateLine(
    val displayName: String,
    val kind: SourceKind,
    val estimate: SourceEstimate,
    /** The source could not be measured; its size counts as zero. */
    val failed: Boolean,
)

/**
 * What a backup of a draft would take, computed without writing anything: per-source estimates,
 * the engine's own [BackupPlan] (names, formats, destination and staging space) and a local staging
 * space check. [plan] is null when the transfer mode cannot run against the destination type.
 */
data class PreflightEstimate(
    val lines: List<SourceEstimateLine>,
    val issues: List<SourceIssue>,
    val totalBytes: Long,
    val totalItems: Long,
    val exact: Boolean,
    val plan: BackupPlan?,
    val transferUnsupported: Boolean,
    /** Local spool the run needs at once (0 when nothing is staged). */
    val stagingRequiredBytes: Long,
    val stagingCheck: PreflightResult?,
) {
    /** Bytes the destination must hold (the engine's upper bound: uncompressed source size). */
    val destinationRequiredBytes: Long get() = plan?.destinationRequiredBytes ?: totalBytes
}

/**
 * Dry pre-flight for the profile wizard. Exists so the review and destination pages show the same
 * numbers the engine will use: sources come from the engine's [BackupSourceFactory], the plan from
 * [BackupPlanner], the transfer decision from [TransferPlanner] and free space from
 * [FreeSpaceVerifier]. Only estimates are read; nothing is written anywhere.
 */
@Singleton
class ProfilePreflight @Inject constructor(
    private val sourceFactory: BackupSourceFactory,
    private val freeSpaceVerifier: FreeSpaceVerifier,
    private val stagingArea: StagingArea,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Estimates [profile] for a destination of [destinationType] (null when none is chosen: the plan
     * is then built as if staged). Environment limits come back as [PreflightEstimate.issues].
     */
    suspend fun estimate(profile: BackupProfile, destinationType: DestinationType?): PreflightEstimate = withContext(io) {
        val resolution = sourceFactory.create(profile, profile.targets)
        val descriptors = resolution.sources.map { resolved ->
            var failed = false
            val estimate = try {
                resolved.source.estimate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Estimate failed for ${resolved.displayName}", e)
                failed = true
                SourceEstimate(itemCount = 0L, totalBytes = 0L, exact = false)
            }
            SourceDescriptor(
                sourceId = resolved.source.sourceId,
                kind = resolved.kind,
                displayName = resolved.displayName,
                estimate = estimate,
                estimateFailed = failed,
                verbatim = resolved.storeVerbatim,
            )
        }
        val transfer = if (destinationType != null) {
            TransferPlanner.resolve(profile.pipeline.transferMode, destinationType)
        } else {
            TransferResolution.Staged
        }
        val plan = if (transfer is TransferResolution.Unsupported) {
            null
        } else {
            BackupPlanner.build(
                options = profile.pipeline,
                jobDirName = PREVIEW_JOB_DIR,
                sources = descriptors,
                transfer = transfer,
                mask = null,
            )
        }
        val stagingRequired = plan?.stagingRequiredBytes ?: 0L
        val stagingCheck = if (stagingRequired > 0L) {
            val free = try {
                stagingArea.freeBytes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Staging free space unknown", e)
                null
            }
            FreeSpaceVerifier.evaluate(free?.let { SpaceInfo(freeBytes = it, totalBytes = null) }, stagingRequired)
        } else {
            null
        }
        PreflightEstimate(
            lines = descriptors.map { SourceEstimateLine(it.displayName, it.kind, it.estimate, it.estimateFailed) },
            issues = resolution.issues,
            totalBytes = BackupPlanner.saturatingSum(descriptors.map { it.estimate.totalBytes }),
            totalItems = BackupPlanner.saturatingSum(descriptors.map { it.estimate.itemCount }),
            exact = descriptors.all { it.estimate.exact && !it.estimateFailed },
            plan = plan,
            transferUnsupported = transfer is TransferResolution.Unsupported,
            stagingRequiredBytes = stagingRequired,
            stagingCheck = stagingCheck,
        )
    }

    /** Asks [destination] for its free space (connects to it) and compares with [requiredBytes]. */
    suspend fun checkDestination(destination: Destination, requiredBytes: Long): PreflightResult =
        freeSpaceVerifier.verify(destination, requiredBytes.coerceAtLeast(0L))

    private companion object {
        const val TAG = "ProfilePreflight"

        /** Placeholder job folder name; only artifact names inside it are shown. */
        const val PREVIEW_JOB_DIR = "preview"
    }
}
