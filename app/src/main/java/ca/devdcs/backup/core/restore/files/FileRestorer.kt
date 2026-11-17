package ca.devdcs.backup.core.restore.files

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.ChecksumEngine
import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryInfo
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryType
import ca.devdcs.backup.core.pipeline.archive.ArchiveReadSource
import ca.devdcs.backup.core.pipeline.archive.ArchiveReaderFactory
import ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout
import ca.devdcs.backup.core.pipeline.engine.ManifestItem
import ca.devdcs.backup.core.pipeline.engine.SourceKind
import ca.devdcs.backup.core.restore.BackupCatalog
import ca.devdcs.backup.core.restore.ConflictPolicy
import ca.devdcs.backup.core.restore.OpenedBackup
import ca.devdcs.backup.core.restore.catalog.BackupItemReader
import ca.devdcs.backup.core.restore.catalog.CatalogException
import ca.devdcs.backup.core.restore.catalog.DecodedArtifactStream
import ca.devdcs.backup.core.restore.catalog.DefaultBackupCatalog
import ca.devdcs.backup.core.restore.catalog.digestsOf
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.StructureMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** How one file unit is restored. */
data class FileRestoreSpec(
    val target: FileRestoreTarget = FileRestoreTarget.OriginalLocation,
    val twrpMode: TwrpRestoreMode = TwrpRestoreMode.COPY_FOLDER,
    /** Archive-path prefixes to restore (segment-wise); null = everything. */
    val includePaths: Set<String>? = null,
)

/** Why a whole file unit could not be restored (per-entry problems are [ExtractIssue]s). */
enum class FileRestoreError {
    /** The backup does not say where these files came from (categorized layout, category roots). */
    ORIGINAL_LOCATION_UNKNOWN,
    /** No target folder could be opened (see [FileUnitOutcome.detail]). */
    TARGET_UNAVAILABLE,
    NO_PRIVILEGE,
    SAF_PERMISSION_MISSING,
    /** Extracting TWRP volumes needs a chosen folder; never the live system. */
    TWRP_EXTRACT_NEEDS_FOLDER,
    KEY_REQUIRED,
    WRONG_KEY,
    /** Stored bytes failed their digest (detected at the end of the artifact). */
    CORRUPT_ARTIFACT,
    NO_SPACE,
    /** The archive could not be read (truncated, unsafe member, unsupported). */
    ARCHIVE_FAILED,
    STORAGE,
}

/** Result of one file unit; [summary] is present whenever extraction started. */
data class FileUnitOutcome(
    val unitId: String,
    val summary: ExtractSummary?,
    val error: FileRestoreError?,
    val detail: String? = null,
    /** Entries whose original root could not be mapped (OriginalLocation only). */
    val unmapped: Int = 0,
) {
    val ok: Boolean get() = error == null && (summary?.failed ?: 0) == 0 && unmapped == 0
}

/** Preflight facts for one file unit. */
data class FilePreflight(
    val available: Boolean,
    val tier: ElevationTier,
    val requiredBytes: Long,
    /** Smallest free space among the unit's targets, or null when unknown. */
    val freeBytes: Long?,
    val error: FileRestoreError?,
    val detail: String? = null,
)

/**
 * Restores FILES and NANDROID (TWRP) artifacts: picks the target folder(s), streams the decoded
 * archive or the tree's items through one [FileExtractor], and reports a [FileUnitOutcome]. Exists
 * so the engine only orchestrates while the routing rules (original roots, chosen folder, TWRP
 * copy vs extract) live in one place.
 */
@Singleton
class FileRestorer @Inject constructor(
    private val catalog: BackupCatalog,
    private val items: BackupItemReader,
    private val readers: ArchiveReaderFactory,
    private val checksumEngine: ChecksumEngine,
    private val targets: RestoreTargetOpener,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Opened targets for one unit plus the routing function over them. */
    private inner class Routing(
        val opened: Map<String, OpenedTarget>,
        val route: (String) -> RoutedPath?,
        val unmapped: () -> Int,
    ) : AutoCloseable {
        override fun close() {
            opened.values.forEach { (it as? OpenedTarget.Ready)?.target?.close() }
        }
    }

    suspend fun preflight(backup: OpenedBackup, artifact: ManifestArtifact, spec: FileRestoreSpec): FilePreflight {
        val required = requiredBytes(artifact, spec)
        val routing = try {
            routing(backup, artifact, spec)
        } catch (e: RoutingException) {
            return FilePreflight(false, ElevationTier.NONE, required, null, e.error, e.detail)
        }
        routing.use { r ->
            val ready = r.opened.values.filterIsInstance<OpenedTarget.Ready>()
            val unavailable = r.opened.values.filterIsInstance<OpenedTarget.Unavailable>()
            if (ready.isEmpty()) {
                val first = unavailable.firstOrNull()
                return FilePreflight(false, ElevationTier.NONE, required, null, first?.let { errorOf(it.reason) } ?: FileRestoreError.TARGET_UNAVAILABLE, first?.detail)
            }
            val tier = ready.map { it.tier }.maxByOrNull { it.ordinal } ?: ElevationTier.NONE
            val free = ready.mapNotNull { runCatching { it.target.freeBytes() }.getOrNull() }.minOrNull()
            val partial = unavailable.firstOrNull()
            return FilePreflight(true, tier, required, free, partial?.let { errorOf(it.reason) }, partial?.detail)
        }
    }

    suspend fun restore(
        backup: OpenedBackup,
        artifact: ManifestArtifact,
        spec: FileRestoreSpec,
        policy: ConflictPolicy,
        restoreMetadata: Boolean,
        progress: ExtractProgress,
    ): FileUnitOutcome {
        val routing = try {
            routing(backup, artifact, spec)
        } catch (e: RoutingException) {
            return FileUnitOutcome(artifact.sourceId, null, e.error, e.detail)
        }
        routing.use { r ->
            if (r.opened.values.none { it is OpenedTarget.Ready }) {
                val first = r.opened.values.filterIsInstance<OpenedTarget.Unavailable>().firstOrNull()
                return FileUnitOutcome(artifact.sourceId, null, first?.let { errorOf(it.reason) } ?: FileRestoreError.TARGET_UNAVAILABLE, first?.detail)
            }
            val extractor = FileExtractor(r.route, policy, restoreMetadata, checksumEngine, io, progress)
            return try {
                if (DefaultBackupCatalog.isTree(artifact)) {
                    restoreTree(backup, artifact, spec, extractor)
                } else {
                    restoreArchive(backup, artifact, extractor)
                }
                FileUnitOutcome(artifact.sourceId, extractor.finish(), null, unmapped = r.unmapped())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                DftbLog.w(TAG, "File unit ${artifact.sourceId} failed", e)
                val summary = runCatching { extractor.finish() }.getOrNull()
                FileUnitOutcome(artifact.sourceId, summary, errorOf(e), e.message ?: e.javaClass.simpleName, r.unmapped())
            }
        }
    }

    // --- extraction ----------------------------------------------------------------------------

    private suspend fun restoreArchive(backup: OpenedBackup, artifact: ManifestArtifact, extractor: FileExtractor) {
        val format = DefaultBackupCatalog.readerFormatOf(artifact.format)
            ?: throw CatalogException(CatalogException.Reason.NOT_A_SINGLE_STREAM, "${artifact.storedName} is not an archive")
        val byPath = artifact.items.associateBy { it.archivePath }
        val decoded = catalog.openDecoded(backup, artifact)
        try {
            val reader = readers.reader(format, ArchiveReadSource.Streamed { decoded })
            try {
                withContext(io) {
                    for (entry in reader.entries()) {
                        val stream = entry.stream
                        extractor.extract(ExtractEntry(entry.info, { stream }, digestsOf(byPath[entry.info.path]?.digests)))
                    }
                    (decoded as? DecodedArtifactStream)?.finishAndVerify()
                }
            } finally {
                reader.close()
            }
        } finally {
            withContext(io) { decoded.close() }
        }
    }

    private suspend fun restoreTree(backup: OpenedBackup, artifact: ManifestArtifact, spec: FileRestoreSpec, extractor: FileExtractor) {
        val twrpExtract = isNandroid(artifact) && spec.twrpMode == TwrpRestoreMode.EXTRACT_VOLUMES
        for (item in artifact.items) {
            currentCoroutineContext().ensureActive()
            if (twrpExtract) {
                val name = item.archivePath.substringAfterLast('/')
                if (item.storedPath != null && TwrpTarEntries.isTarVolumeName(name) && RestorePaths.selected(item.archivePath, spec.includePaths)) {
                    extractVolume(backup, artifact, item, name, extractor)
                }
                continue
            }
            val info = infoOf(item) ?: continue
            val open: (suspend () -> InputStream)? = if (info.type == ArchiveEntryType.FILE) {
                { items.openDecodedItem(backup, artifact, item) }
            } else {
                null
            }
            extractor.extract(ExtractEntry(info, open, digestsOf(item.digests)))
        }
    }

    /** One TWRP volume's members go below `<partition>/` in the chosen folder. */
    private suspend fun extractVolume(backup: OpenedBackup, artifact: ManifestArtifact, item: ManifestItem, name: String, extractor: FileExtractor) {
        val folder = RestorePaths.twrpVolumeFolder(name)
        val decoded = items.openDecodedItem(backup, artifact, item)
        try {
            withContext(io) {
                for (entry in TwrpTarEntries.entries(decoded)) {
                    val stream = entry.stream
                    val info = entry.info.copy(
                        path = "$folder/${entry.info.path}",
                        linkTarget = if (entry.info.type == ArchiveEntryType.HARD_LINK) entry.info.linkTarget?.let { "$folder/$it" } else entry.info.linkTarget,
                    )
                    extractor.extract(ExtractEntry(info, { stream }))
                }
                decoded.finishAndVerify()
            }
        } finally {
            withContext(io) { decoded.close() }
        }
    }

    private fun infoOf(item: ManifestItem): ArchiveEntryInfo? {
        val type = when (item.type) {
            ManifestItem.ItemType.DIRECTORY -> ArchiveEntryType.DIRECTORY
            ManifestItem.ItemType.SYMLINK -> ArchiveEntryType.SYMLINK
            ManifestItem.ItemType.FILE, ManifestItem.ItemType.STREAM -> if (item.storedPath == null) return null else ArchiveEntryType.FILE
        }
        val m = item.metadata
        return ArchiveEntryInfo(
            path = item.archivePath,
            type = type,
            sizeBytes = item.sizeBytes ?: -1L,
            linkTarget = item.linkTarget,
            metadata = EntryMetadata(m?.modifiedAtMillis, m?.mode, m?.uid, m?.gid, m?.seLinuxContext),
        )
    }

    // --- routing -------------------------------------------------------------------------------

    private class RoutingException(val error: FileRestoreError, val detail: String?) : Exception(detail ?: error.name)

    private suspend fun routing(backup: OpenedBackup, artifact: ManifestArtifact, spec: FileRestoreSpec): Routing {
        val nandroid = isNandroid(artifact)
        val include = spec.includePaths
        if (nandroid && spec.twrpMode == TwrpRestoreMode.EXTRACT_VOLUMES && spec.target == FileRestoreTarget.OriginalLocation) {
            throw RoutingException(FileRestoreError.TWRP_EXTRACT_NEEDS_FOLDER, null)
        }
        when (val target = spec.target) {
            is FileRestoreTarget.Folder, is FileRestoreTarget.SafTree -> {
                val location = if (target is FileRestoreTarget.Folder) TargetLocation.AbsolutePath(target.path) else TargetLocation.Tree((target as FileRestoreTarget.SafTree).treeUri)
                val opened = targets.open(location)
                val fs = (opened as? OpenedTarget.Ready)?.target
                val extractVolumes = nandroid && spec.twrpMode == TwrpRestoreMode.EXTRACT_VOLUMES
                val route: (String) -> RoutedPath? = { archivePath ->
                    when {
                        fs == null -> null
                        // Volume members were already prefixed with their partition folder.
                        extractVolumes -> RoutedPath(fs, archivePath)
                        !RestorePaths.selected(archivePath, include) -> null
                        nandroid -> RoutedPath(fs, archivePath)
                        archivePath == ArchiveLayout.FILES -> null
                        else -> RoutedPath(fs, RestorePaths.inChosenFolder(archivePath))
                    }
                }
                return Routing(mapOf("" to opened), route) { 0 }
            }
            FileRestoreTarget.OriginalLocation -> {
                if (nandroid) {
                    val opened = targets.open(TargetLocation.AbsolutePath(targets.primaryStorageRoot()))
                    val fs = (opened as? OpenedTarget.Ready)?.target
                    return Routing(mapOf("" to opened), { path -> if (fs != null && RestorePaths.selected(path, include)) RoutedPath(fs, path) else null }) { 0 }
                }
                val profile = backup.manifest.profile
                if (!RestoreUnitKind.isKind(artifact, SourceKind.FILES) || profile.pipeline.structureMode != StructureMode.PRESERVE_HIERARCHY) {
                    throw RoutingException(FileRestoreError.ORIGINAL_LOCATION_UNKNOWN, artifact.name ?: artifact.storedName)
                }
                val labels = RestorePaths.labels(profile.targets.paths.map { it.location })
                val needed = artifact.items.mapNotNull { RestorePaths.labelOf(it.archivePath) }.toSet()
                val opened = LinkedHashMap<String, OpenedTarget>()
                for (label in needed) {
                    val location = labels[label] ?: continue
                    opened[label] = targets.open(if (location.startsWith("content://")) TargetLocation.Tree(location) else TargetLocation.AbsolutePath(location))
                }
                if (opened.isEmpty()) throw RoutingException(FileRestoreError.ORIGINAL_LOCATION_UNKNOWN, needed.joinToString())
                var unmapped = 0
                val route: (String) -> RoutedPath? = route@{ archivePath ->
                    if (!RestorePaths.selected(archivePath, include)) return@route null
                    val label = RestorePaths.labelOf(archivePath) ?: return@route null
                    val origin = RestorePaths.origin(archivePath, labels)
                    val fs = (opened[label] as? OpenedTarget.Ready)?.target
                    if (origin == null || fs == null) {
                        if (archivePath.count { it == '/' } >= 2) unmapped++
                        return@route null
                    }
                    origin.relativePath.takeIf { it.isNotEmpty() }?.let { RoutedPath(fs, it) }
                }
                return Routing(opened, route) { unmapped }
            }
        }
    }

    private fun requiredBytes(artifact: ManifestArtifact, spec: FileRestoreSpec): Long {
        val include = spec.includePaths
        if (include == null) {
            return artifact.sourceBytes.takeIf { it > 0L } ?: artifact.items.sumOf { it.sizeBytes ?: 0L }
        }
        return artifact.items.filter { RestorePaths.selected(it.archivePath, include) }.sumOf { it.sizeBytes ?: 0L }
    }

    private fun isNandroid(artifact: ManifestArtifact): Boolean = RestoreUnitKind.isKind(artifact, SourceKind.NANDROID)

    private fun errorOf(reason: TargetUnavailableReason): FileRestoreError = when (reason) {
        TargetUnavailableReason.NO_PRIVILEGE -> FileRestoreError.NO_PRIVILEGE
        TargetUnavailableReason.SAF_PERMISSION_MISSING -> FileRestoreError.SAF_PERMISSION_MISSING
        TargetUnavailableReason.INVALID_LOCATION -> FileRestoreError.TARGET_UNAVAILABLE
    }

    private fun errorOf(e: Exception): FileRestoreError = when {
        e is CatalogException && e.reason == CatalogException.Reason.KEY_REQUIRED -> FileRestoreError.KEY_REQUIRED
        e is CatalogException && (e.reason == CatalogException.Reason.DIGEST_MISMATCH || e.reason == CatalogException.Reason.SIZE_MISMATCH) ->
            FileRestoreError.CORRUPT_ARTIFACT
        e is CipherIntegrityException && (e.reason == CipherIntegrityException.Reason.WRONG_KEY || e.reason == CipherIntegrityException.Reason.KEY_TYPE_MISMATCH) ->
            FileRestoreError.WRONG_KEY
        e is CipherIntegrityException -> FileRestoreError.CORRUPT_ARTIFACT
        e is StorageException && e.reason == StorageException.Reason.NO_SPACE -> FileRestoreError.NO_SPACE
        e is StorageException -> FileRestoreError.STORAGE
        (e.message ?: "").contains("No space left", ignoreCase = true) -> FileRestoreError.NO_SPACE
        else -> FileRestoreError.ARCHIVE_FAILED
    }

    private companion object {
        const val TAG = "FileRestorer"
    }
}
