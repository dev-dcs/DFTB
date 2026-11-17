package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.discovery.partition.PartitionScanResult
import ca.devdcs.backup.core.discovery.partition.PartitionScanUnavailable
import ca.devdcs.backup.core.discovery.partition.PartitionScanner
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.discovery.partition.PartitionUnavailableReason
import ca.devdcs.backup.core.discovery.partition.PartitionViews
import ca.devdcs.backup.core.discovery.partition.ResolvedPartition
import ca.devdcs.backup.core.pipeline.BackupSource
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout
import ca.devdcs.backup.core.pipeline.source.AppBackupSourceFactory
import ca.devdcs.backup.core.pipeline.source.FileTreeSourceFactory
import ca.devdcs.backup.core.pipeline.source.NandroidSourceFactory
import ca.devdcs.backup.core.pipeline.source.PartitionImageSourceFactory
import ca.devdcs.backup.core.pipeline.source.PipelineSourceStrings
import ca.devdcs.backup.core.pipeline.source.SourcePreparation
import ca.devdcs.backup.core.pipeline.source.SourceUnavailableReason
import ca.devdcs.backup.core.pipeline.source.SourceWarningKind
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.TargetSelection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binds the engine's [BackupSourceFactory] seam to the real partition / app / file / TWRP
 * factories, splitting the selection so every partition, app and file root becomes its own
 * artifact (a failed app never takes the others down, and restore can pick one). Environment
 * limits come back as [SourceIssue]s from the factories' [SourcePreparation.Unavailable].
 */
@Singleton
class DefaultBackupSourceFactory @Inject constructor(
    private val partitionScanner: PartitionScanner,
    private val partitionFactory: PartitionImageSourceFactory,
    private val appFactory: AppBackupSourceFactory,
    private val fileFactory: FileTreeSourceFactory,
    private val nandroidFactory: NandroidSourceFactory,
    private val strings: PipelineSourceStrings,
) : BackupSourceFactory {

    override suspend fun create(profile: BackupProfile, selection: TargetSelection): SourceResolution {
        val options = profile.pipeline
        val layout = ArchiveLayout(options.structureMode)
        val sources = ArrayList<ResolvedSource>()
        val issues = ArrayList<SourceIssue>()

        if (selection.partitions.isNotEmpty()) {
            when (val scan = partitionScanner.scan()) {
                is PartitionScanUnavailable -> issues += unavailableIssue(SUBJECT_PARTITIONS, mapScanReason(scan.reason), scan.detail)
                is PartitionScanResult -> {
                    val resolution = PartitionViews.resolve(selection.partitions, scan)
                    resolution.unresolved.forEach { unresolved ->
                        issues += SourceIssue(
                            subject = unresolved.target.name,
                            message = strings.get(SourceWarningKind.PARTITION_UNRESOLVED.messageRes, unresolved.target.name),
                            detail = unresolved.failure.name,
                            blocking = true,
                        )
                    }
                    if (options.twrpCompatible) {
                        addNandroid(resolution.resolved, sources, issues)
                    } else {
                        for (resolved in resolution.resolved) {
                            add(partitionFactory.prepareResolved(listOf(resolved), layout), SourceKind.PARTITION, partitionName(resolved), sources, issues)
                        }
                    }
                }
            }
        }

        for (target in selection.apps) {
            add(appFactory.prepare(listOf(target), options, layout), SourceKind.APP, target.packageName, sources, issues)
        }

        for (path in selection.paths) {
            val single = TargetSelection(paths = listOf(path))
            add(fileFactory.prepare(single, options, layout), SourceKind.FILES, path.location, sources, issues)
        }
        if (selection.categories.isNotEmpty()) {
            val media = TargetSelection(categories = selection.categories)
            val name = selection.categories.sortedBy { it.ordinal }.joinToString("_") { it.name.lowercase() }
            add(fileFactory.prepare(media, options, layout), SourceKind.FILES, name, sources, issues)
        }
        return SourceResolution(sources, issues)
    }

    /** TWRP images the raw partitions; a selected userdata/data partition becomes TWRP's data tar. */
    private suspend fun addNandroid(resolved: List<ResolvedPartition>, sources: MutableList<ResolvedSource>, issues: MutableList<SourceIssue>) {
        val (data, images) = resolved.partition { it.partition.baseName in DATA_PARTITION_NAMES }
        val preparation = nandroidFactory.prepare(images, includeData = data.isNotEmpty())
        val ready = preparation as? SourcePreparation.Ready
        val name = ready?.source?.plan?.backupName ?: SUBJECT_TWRP
        val runtime: () -> List<SourceIssue> = {
            ready?.source?.runtimeWarnings.orEmpty().map { SourceIssue(it.subject, strings.describe(it), it.detail, blocking = false) }
        }
        add(preparation, SourceKind.NANDROID, name, sources, issues, verbatim = true, runtimeIssues = runtime)
    }

    private fun <S : BackupSource> add(
        preparation: SourcePreparation<S>,
        kind: SourceKind,
        displayName: String,
        sources: MutableList<ResolvedSource>,
        issues: MutableList<SourceIssue>,
        verbatim: Boolean = false,
        runtimeIssues: () -> List<SourceIssue> = { emptyList() },
    ) {
        when (preparation) {
            is SourcePreparation.Ready -> {
                sources += ResolvedSource(preparation.source, kind, displayName, storeVerbatim = verbatim, runtimeIssues = runtimeIssues)
                preparation.warnings.forEach { warning ->
                    issues += SourceIssue(warning.subject, strings.describe(warning), warning.detail, blocking = false)
                }
            }
            is SourcePreparation.Unavailable -> issues += SourceIssue(displayName, preparation.message, preparation.detail, blocking = true)
        }
    }

    private fun unavailableIssue(subject: String, reason: SourceUnavailableReason, detail: String?): SourceIssue {
        val unavailable = strings.unavailable(reason, detail)
        return SourceIssue(subject, unavailable.message, unavailable.detail, blocking = true)
    }

    private fun partitionName(resolved: ResolvedPartition): String =
        if (resolved.partition.source == PartitionSource.MAPPER) resolved.partition.name + "_mapper" else resolved.partition.name

    private companion object {
        const val SUBJECT_PARTITIONS = "partitions"
        const val SUBJECT_TWRP = "twrp"
        val DATA_PARTITION_NAMES = setOf("userdata", "data")

        fun mapScanReason(reason: PartitionUnavailableReason): SourceUnavailableReason = when (reason) {
            PartitionUnavailableReason.ROOT_REQUIRED -> SourceUnavailableReason.ROOT_REQUIRED
            PartitionUnavailableReason.ROOT_NOT_GRANTED -> SourceUnavailableReason.ROOT_NOT_GRANTED
            PartitionUnavailableReason.SCAN_FAILED, PartitionUnavailableReason.NO_PARTITIONS_FOUND -> SourceUnavailableReason.SCAN_FAILED
        }
    }
}
