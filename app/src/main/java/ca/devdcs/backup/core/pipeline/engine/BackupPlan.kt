package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.SourceEstimate
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.PipelineOptions

/** A resolved source plus its estimate; the planner's only input about what is backed up. */
data class SourceDescriptor(
    val sourceId: String,
    val kind: SourceKind,
    val displayName: String,
    val estimate: SourceEstimate,
    val estimateFailed: Boolean = false,
    /** Store items byte-for-byte (TWRP): no codec, encryption or masking. */
    val verbatim: Boolean = false,
)

/** One artifact the job will produce. Paths are destination-relative. */
data class PlannedArtifact(
    val index: Int,
    val sourceId: String,
    val kind: SourceKind,
    /** Unmasked final name (file, or folder for FILE_TREE). Shown in UI, kept in the manifest. */
    val logicalName: String,
    /** Name actually used at the destination ([logicalName] masked when masking is on). */
    val storedName: String,
    /** `<jobDir>/<storedName>`. */
    val path: String,
    val spec: ArtifactSpec,
    val encrypted: Boolean,
    val masked: Boolean,
    val estimate: SourceEstimate,
    val verbatim: Boolean = false,
    /**
     * FILE_TREE only: destination-relative folder the item paths are appended to. Equals [path]
     * normally; empty (the destination root) for verbatim TWRP trees, whose items carry their
     * full `TWRP/BACKUPS/<device>/<name>/...` path, the only place TWRP/OrangeFox look.
     */
    val treeRoot: String = path,
)

/**
 * Everything decided before the first byte moves: names, shapes, totals and the space each
 * storage needs. Exists as a pure value so the plan is unit-tested and the pre-flight is honest.
 */
data class BackupPlan(
    val jobDir: String,
    val manifestPath: String,
    val encryptedManifest: Boolean,
    val artifacts: List<PlannedArtifact>,
    val totalBytes: Long,
    val totalItems: Long,
    val estimatesExact: Boolean,
    /** Upper bound written to the destination (uncompressed source size; compression only helps). */
    val destinationRequiredBytes: Long,
    /** Largest local spool needed at once (artifacts are staged and released one at a time). */
    val stagingRequiredBytes: Long,
    val transfer: TransferResolution,
)

/** Pure plan construction. */
object BackupPlanner {

    fun build(
        options: PipelineOptions,
        jobDirName: String,
        sources: List<SourceDescriptor>,
        transfer: TransferResolution,
        mask: ((String) -> String)?,
    ): BackupPlan {
        val encrypted = options.isEncrypted
        val masking = options.maskFilenames && mask != null
        val specs = sources.map { if (it.verbatim) ArtifactSpecResolver.VERBATIM else ArtifactSpecResolver.resolve(it.kind, options) }
        val bases = ArtifactNaming.uniqueNames(sources.map { ArtifactNaming.artifactBaseName(it.kind, it.displayName) })
        val artifacts = sources.mapIndexed { index, source ->
            val spec = specs[index]
            val artifactEncrypted = encrypted && !source.verbatim
            val artifactMasked = masking && !source.verbatim
            val logical = ArtifactNaming.artifactName(bases[index], spec, artifactEncrypted)
            val stored = if (artifactMasked) mask(logical) else logical
            PlannedArtifact(
                index = index,
                sourceId = source.sourceId,
                kind = source.kind,
                logicalName = logical,
                storedName = stored,
                path = ArtifactNaming.join(jobDirName, stored),
                spec = spec,
                encrypted = artifactEncrypted,
                masked = artifactMasked,
                estimate = source.estimate,
                verbatim = source.verbatim,
                treeRoot = if (source.verbatim) "" else ArtifactNaming.join(jobDirName, stored),
            )
        }
        // A masked layout leaks nothing through names; a plaintext manifest would undo that.
        val encryptedManifest = encrypted && masking
        return BackupPlan(
            jobDir = jobDirName,
            manifestPath = ArtifactNaming.join(jobDirName, ArtifactNaming.manifestName(encryptedManifest)),
            encryptedManifest = encryptedManifest,
            artifacts = artifacts,
            totalBytes = saturatingSum(sources.map { it.estimate.totalBytes }),
            totalItems = saturatingSum(sources.map { it.estimate.itemCount }),
            estimatesExact = sources.all { it.estimate.exact && !it.estimateFailed },
            destinationRequiredBytes = saturatingSum(sources.map { it.estimate.totalBytes }),
            stagingRequiredBytes = artifacts.maxOfOrNull { stagingBytesFor(it, transfer) } ?: 0L,
            transfer = transfer,
        )
    }

    /** Local bytes [artifact] needs while it is produced under [transfer]. */
    fun stagingBytesFor(artifact: PlannedArtifact, transfer: TransferResolution): Long {
        val size = artifact.estimate.totalBytes.coerceAtLeast(0L)
        var multiplier = 0L
        if (transfer == TransferResolution.Staged) multiplier++
        if (artifact.spec.needsLocalSpool) multiplier++
        // tar headers need exact sizes; the writer spools streams whose size is not known.
        if (artifact.spec.writerFormat == ArchiveFormat.TAR && !artifact.estimate.exact) multiplier++
        return if (multiplier == 0L) 0L else saturatingMultiply(size, multiplier)
    }

    fun saturatingSum(values: List<Long>): Long {
        var total = 0L
        for (value in values) {
            val v = value.coerceAtLeast(0L)
            total = if (total > Long.MAX_VALUE - v) Long.MAX_VALUE else total + v
        }
        return total
    }

    private fun saturatingMultiply(value: Long, factor: Long): Long =
        if (value != 0L && factor > Long.MAX_VALUE / value) Long.MAX_VALUE else value * factor
}
