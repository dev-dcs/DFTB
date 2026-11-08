package ca.devdcs.backup.core.restore.partition

import ca.devdcs.backup.R
import ca.devdcs.backup.core.discovery.partition.PartitionSlot
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.pipeline.engine.ArtifactLayout
import ca.devdcs.backup.core.pipeline.engine.DeviceInfoProvider
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.ManifestItem
import ca.devdcs.backup.core.pipeline.engine.SourceKind
import ca.devdcs.backup.core.restore.BackupCatalog
import ca.devdcs.backup.core.restore.OpenedBackup
import ca.devdcs.backup.core.restore.RestoreUnitResult
import ca.devdcs.backup.core.restore.engine.PartitionRestorer
import ca.devdcs.backup.core.restore.engine.RestoreOptions
import ca.devdcs.backup.core.restore.engine.RestoreProgress
import ca.devdcs.backup.core.restore.engine.UnitPreflight
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import kotlinx.coroutines.CancellationException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One DFTB partition image found in a manifest artifact. */
data class PartitionImageRef(
    val artifact: ManifestArtifact,
    val item: ManifestItem,
    val node: ImagedNode,
) {
    /** Expected plaintext digests of the image, keyed by algorithm (unknown keys dropped). */
    val expectedDigests: Map<ChecksumAlgorithm, String>
        get() = item.digests.orEmpty().mapNotNull { (key, hex) ->
            ChecksumAlgorithm.entries.firstOrNull { it.storageKey == key }?.let { it to hex }
        }.toMap()

    /** Declared plaintext size: the item's (blockdev-probed) size, else the artifact's source bytes. */
    val declaredSizeBytes: Long? get() = item.sizeBytes ?: artifact.sourceBytes.takeIf { it > 0 }
}

/**
 * Root-only [PartitionRestorer]: writes DFTB `dd` images back to the matching block device of
 * THIS device. Slot mapping ([SlotMapping]), family locking ([ca.devdcs.backup.domain.model.PartitionTarget.logical])
 * and every safety gate ([PartitionSafetyGates]) run before the image is read; the write itself is
 * [PartitionImageWriter]'s verify → `dd conv=fsync` → read-back path.
 *
 * Dynamic partitions: logical (mapper) images are written only to the same-named mapper node of the
 * chosen slot, only when no filesystem or dm device uses it, after `blockdev --setrw`. Nothing
 * resizes or remaps logical partitions (no lptools / liblp): an inactive-slot partition that is not
 * mapped, or whose size differs, is refused and must be restored from recovery or fastbootd.
 */
@Singleton
class RootPartitionRestorer @Inject constructor(
    private val catalog: BackupCatalog,
    private val writer: PartitionImageWriter,
    private val deviceInfo: DeviceInfoProvider,
    private val strings: PartitionRestoreStrings,
) : PartitionRestorer {

    override suspend fun preflight(backup: OpenedBackup, artifacts: List<ManifestArtifact>, options: RestoreOptions): UnitPreflight =
        preflight(backup, artifacts, PartitionRestoreOptions.from(options))

    override suspend fun restore(backup: OpenedBackup, artifacts: List<ManifestArtifact>, options: RestoreOptions, progress: RestoreProgress): RestoreUnitResult =
        restore(backup, artifacts, PartitionRestoreOptions.from(options), progress)

    /** [preflight] with explicit partition overrides. Read-only. */
    suspend fun preflight(backup: OpenedBackup, artifacts: List<ManifestArtifact>, options: PartitionRestoreOptions): UnitPreflight {
        val unitId = unitId(artifacts)
        val env = when (val e = writer.environment()) {
            is WriterEnvironment.Unavailable -> return UnitPreflight(unitId, available = false, requiredTier = ElevationTier.ROOT, reason = e.message)
            is WriterEnvironment.Ready -> e
        }
        val reasons = ArrayList<String>()
        val warnings = ArrayList<String>()
        var bytes = 0L
        for (artifact in artifacts) {
            val ref = imageOf(artifact)
            if (ref == null) {
                reasons += strings.get(R.string.partition_restore_not_partition_artifact, artifactLabel(artifact))
                continue
            }
            ref.declaredSizeBytes?.let { bytes += it }
            when (val planned = plan(backup, ref, env, options)) {
                is Planned.Failed -> reasons += planned.message
                is Planned.Ok -> {
                    val report = writer.gates(planned.target, origin(backup), planned.plan.crossesSlot, ref.declaredSizeBytes, options)
                    val (refusal, overrides) = writer.describe(report, planned.target.resolved.partition.name)
                    if (refusal != null) reasons += refusal
                    warnings += overrides
                }
            }
        }
        return UnitPreflight(
            unitId = unitId,
            available = reasons.isEmpty(),
            requiredTier = ElevationTier.ROOT,
            requiredBytes = bytes.takeIf { it > 0 },
            reason = reasons.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            warnings = warnings,
        )
    }

    /**
     * [restore] with explicit partition overrides. Artifacts are written one after another; a
     * refused or failed partition does not stop the others, and the result lists every error.
     */
    suspend fun restore(backup: OpenedBackup, artifacts: List<ManifestArtifact>, options: PartitionRestoreOptions, progress: RestoreProgress): RestoreUnitResult {
        val unitId = unitId(artifacts)
        val env = when (val e = writer.environment()) {
            is WriterEnvironment.Unavailable -> return RestoreUnitResult(unitId, ok = false, error = e.message)
            is WriterEnvironment.Ready -> e
        }
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()
        for (artifact in artifacts) {
            if (progress.isCancelled()) throw CancellationException("restore cancelled")
            val ref = imageOf(artifact)
            if (ref == null) {
                errors += strings.get(R.string.partition_restore_not_partition_artifact, artifactLabel(artifact))
                continue
            }
            val planned = plan(backup, ref, env, options)
            if (planned is Planned.Failed) {
                errors += planned.message
                continue
            }
            planned as Planned.Ok
            val payload = ImagePayload(
                label = ref.node.nodeName,
                declaredSizeBytes = ref.declaredSizeBytes,
                expectedDigests = ref.expectedDigests,
                open = { catalog.openDecoded(backup, artifact) },
            )
            val result = writer.write(artifact.sourceId, env, planned.target, payload, origin(backup), planned.plan.crossesSlot, options, progress)
            warnings += result.warnings
            result.error?.let { errors += it }
        }
        return RestoreUnitResult(unitId, ok = errors.isEmpty(), warnings = warnings, error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    private sealed interface Planned {
        data class Ok(val plan: SlotPlan.Planned, val target: TargetInspection.Ready) : Planned
        data class Failed(val message: String) : Planned
    }

    private suspend fun plan(backup: OpenedBackup, ref: PartitionImageRef, env: WriterEnvironment.Ready, options: PartitionRestoreOptions): Planned {
        val backupActive = PartitionSlot.fromSuffix(backup.manifest.device.slotSuffix)
        val plan = when (val p = SlotMapping.plan(ref.node, backupActive, env.scan, options.slotChoice)) {
            is SlotPlan.Failed -> return Planned.Failed(writer.slotFailureMessage(p.failure, ref.node.nodeName))
            is SlotPlan.Planned -> p
        }
        return when (val inspection = writer.inspect(env, plan)) {
            is TargetInspection.Failed -> Planned.Failed(inspection.message)
            is TargetInspection.Ready -> Planned.Ok(plan, inspection)
        }
    }

    private suspend fun origin(backup: OpenedBackup): ImageOrigin = ImageOrigin.Manifest(backup.manifest.device, deviceInfo.device())

    private fun unitId(artifacts: List<ManifestArtifact>): String = artifacts.firstOrNull()?.sourceId ?: SourceKind.PARTITION.idPrefix

    private fun artifactLabel(artifact: ManifestArtifact): String = artifact.name ?: artifact.storedName

    companion object {
        /** `kind` value the engine records for partition artifacts. */
        val PARTITION_KIND: String = SourceKind.PARTITION.name.lowercase(Locale.ROOT)

        private val RAW_LAYOUT = ArtifactLayout.RAW_STREAM.name.lowercase(Locale.ROOT)

        /**
         * True for artifacts this restorer handles. Partition sources' ids start with `partitions:`
         * (not [SourceKind.PARTITION]'s `partition:` prefix), so routing must use [ManifestArtifact.kind].
         */
        fun isPartitionArtifact(artifact: ManifestArtifact): Boolean =
            artifact.kind.equals(PARTITION_KIND, ignoreCase = true) && imageOf(artifact) != null

        /** The single image of a RAW_STREAM partition artifact, or null when it is not one. */
        fun imageOf(artifact: ManifestArtifact): PartitionImageRef? {
            if (!artifact.layout.equals(RAW_LAYOUT, ignoreCase = true)) return null
            val images = artifact.items.filter { it.type == ManifestItem.ItemType.STREAM || it.type == ManifestItem.ItemType.FILE }
            val item = images.singleOrNull() ?: return null
            val node = SlotMapping.parseArchivePath(item.archivePath) ?: return null
            return PartitionImageRef(artifact, item, node)
        }
    }
}
