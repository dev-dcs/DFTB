package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.discovery.partition.PartitionClassifier
import ca.devdcs.backup.core.discovery.partition.PartitionRisk
import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.SourceKind
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.core.restore.partition.RootPartitionRestorer

/**
 * One restorable unit of a backup as the restore screens show it: every artifact of one source
 * (an app, a partition image, a file tree). Exists so selection, sizes and risk badges are derived
 * from the manifest once, with the same unit ids ([ManifestArtifact.sourceId]) the engine uses.
 */
data class RestoreUnitInfo(
    val id: String,
    val kind: RestoreUnitKind,
    /** Engine display name (artifact name, else its stored name). */
    val name: String,
    val artifactCount: Int,
    /** Bytes stored at the destination (compressed / encrypted). */
    val storedBytes: Long,
    /** Plaintext bytes the unit writes back (best estimate from the manifest). */
    val restoreBytes: Long,
    val encrypted: Boolean,
    /** APP units: the package recorded in `apps/<pkg>/manifest.json`. */
    val packageName: String? = null,
    /** PARTITION units: the imaged node (`boot_a`) and its slot-less base name (`boot`). */
    val partitionNode: String? = null,
    val partitionBase: String? = null,
    val logicalPartition: Boolean = false,
    /** PARTITION units holding device-unique identity / calibration data (IMEI/NV, persist, FRP). */
    val criticalIdentity: Boolean = false,
    /** FILE_TREE units that are a TWRP (Nandroid) folder, where the TWRP restore mode applies. */
    val nandroid: Boolean = false,
) {
    /** Name to show: package for apps, node for partitions, else the engine name. */
    val displayName: String get() = packageName ?: partitionNode ?: name
}

/** Units split by kind, each list sorted by display name. */
data class RestoreUnitGroups(
    val apps: List<RestoreUnitInfo>,
    val partitions: List<RestoreUnitInfo>,
    val files: List<RestoreUnitInfo>,
) {
    val all: List<RestoreUnitInfo> get() = partitions + apps + files
    val isEmpty: Boolean get() = apps.isEmpty() && partitions.isEmpty() && files.isEmpty()

    fun of(kind: RestoreUnitKind): List<RestoreUnitInfo> = when (kind) {
        RestoreUnitKind.APP -> apps
        RestoreUnitKind.PARTITION -> partitions
        RestoreUnitKind.FILE_TREE -> files
    }
}

/**
 * Pure manifest → unit derivation shared by the restore wizard, the preflight and the run panel.
 * Groups by sourceId exactly like the engine's planner so a unit id selected here is the unit the
 * engine restores.
 */
object RestoreUnitGrouping {

    private val APP_MANIFEST_SUFFIX = "/" + ArchiveLayout.APP_MANIFEST
    private val APP_PREFIX = ArchiveLayout.APPS + "/"

    fun units(manifest: BackupManifest): List<RestoreUnitInfo> = units(manifest.artifacts)

    fun units(artifacts: List<ManifestArtifact>): List<RestoreUnitInfo> =
        artifacts.groupBy { it.sourceId }.map { (id, list) -> describe(id, list) }

    fun group(units: List<RestoreUnitInfo>): RestoreUnitGroups {
        val order = compareBy<RestoreUnitInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }.thenBy { it.id }
        return RestoreUnitGroups(
            apps = units.filter { it.kind == RestoreUnitKind.APP }.sortedWith(order),
            partitions = units.filter { it.kind == RestoreUnitKind.PARTITION }.sortedWith(order),
            files = units.filter { it.kind == RestoreUnitKind.FILE_TREE }.sortedWith(order),
        )
    }

    /** Plaintext bytes of the [selected] units (unknown ids ignored). */
    fun restoreBytes(units: List<RestoreUnitInfo>, selected: Set<String>): Long =
        saturatingSum(units.filter { it.id in selected }.map { it.restoreBytes })

    /** The package of an app unit, from its `apps/<pkg>/manifest.json` member. */
    fun packageName(artifacts: List<ManifestArtifact>): String? =
        artifacts.asSequence()
            .flatMap { it.items.asSequence() }
            .map { it.archivePath.trimStart('/') }
            .firstNotNullOfOrNull { path ->
                if (!path.startsWith(APP_PREFIX) || !path.endsWith(APP_MANIFEST_SUFFIX)) return@firstNotNullOfOrNull null
                path.removePrefix(APP_PREFIX).removeSuffix(APP_MANIFEST_SUFFIX).takeIf { it.isNotEmpty() && '/' !in it }
            }

    private fun describe(id: String, artifacts: List<ManifestArtifact>): RestoreUnitInfo {
        val first = artifacts.first()
        val kind = RestoreUnitKind.of(first)
        val stored = saturatingSum(artifacts.map { it.sizeBytes })
        val encrypted = artifacts.any { a -> a.encryption != null || a.items.any { it.encryption != null } }
        val base = RestoreUnitInfo(
            id = id,
            kind = kind,
            name = first.name ?: first.storedName,
            artifactCount = artifacts.size,
            storedBytes = stored,
            restoreBytes = saturatingSum(artifacts.map(::plaintextBytes)),
            encrypted = encrypted,
        )
        return when (kind) {
            RestoreUnitKind.APP -> base.copy(packageName = packageName(artifacts))
            RestoreUnitKind.PARTITION -> {
                val image = artifacts.firstNotNullOfOrNull { RootPartitionRestorer.imageOf(it) }
                if (image == null) {
                    base
                } else {
                    val node = image.node
                    val category = PartitionClassifier.category(node.baseName, isMapper = node.logical)
                    val risk = PartitionClassifier.risk(node.baseName, category, image.declaredSizeBytes)
                    base.copy(
                        partitionNode = node.nodeName,
                        partitionBase = node.baseName,
                        logicalPartition = node.logical,
                        criticalIdentity = risk == PartitionRisk.CRITICAL_IDENTITY,
                    )
                }
            }
            RestoreUnitKind.FILE_TREE -> base.copy(nandroid = RestoreUnitKind.isKind(first, SourceKind.NANDROID))
        }
    }

    /** Item sizes when the manifest records them, else the artifact's source bytes. */
    private fun plaintextBytes(artifact: ManifestArtifact): Long {
        val items = saturatingSum(artifact.items.mapNotNull { it.sizeBytes })
        return if (items > 0L) items else artifact.sourceBytes.coerceAtLeast(0L)
    }

    internal fun saturatingSum(values: List<Long>): Long {
        var total = 0L
        for (v in values) {
            if (v <= 0L) continue
            total = if (total > Long.MAX_VALUE - v) Long.MAX_VALUE else total + v
        }
        return total
    }
}
