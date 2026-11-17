package ca.devdcs.backup.core.pipeline.source

import android.content.Context
import android.net.Uri
import ca.devdcs.backup.core.discovery.files.FileScanner
import ca.devdcs.backup.core.discovery.files.ScanEvent
import ca.devdcs.backup.core.discovery.files.ScanOptions
import ca.devdcs.backup.core.discovery.files.ScanResult
import ca.devdcs.backup.core.discovery.files.ScannedFile
import ca.devdcs.backup.core.discovery.files.WalkerKind
import ca.devdcs.backup.core.pipeline.BackupSource
import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.SourceEstimate
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.StructureMode
import ca.devdcs.backup.domain.model.TargetSelection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a collected [ScanResult] into archive items. Each file is opened the same way it was found:
 * NIO paths in-process (with lstat metadata; a path that became a symlink is stored as a link),
 * SAF and MediaStore URIs through the ContentResolver, privileged-find paths through
 * [RootByteStreams] (`cat` via root FIFO or Shizuku). Every File item is length-checked, so a file
 * that changed since the scan fails its item instead of corrupting the archive.
 */
class FileTreeSource internal constructor(
    private val context: Context,
    private val files: List<ScannedFile>,
    /** Stable selection keys (scan root locations) for [sourceId]. */
    sourceKeys: List<String>,
    private val options: PipelineOptions,
    private val layout: ArchiveLayout,
    private val streams: RootByteStreams,
    private val io: CoroutineDispatcher,
) : BackupSource {

    override val sourceId: String = "files:" + stableHash(sourceKeys.distinct().sorted() + options.structureMode.storageKey)

    override suspend fun estimate(): SourceEstimate = SourceEstimate(files.size.toLong(), files.sumOf { it.sizeBytes }, exact = true)

    override fun items(): Flow<SourceItem> = flow {
        val allocator = layout.newFileAllocator()
        val emittedDirs = HashSet<String>()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val path = allocator.allocate(file.rootLocation, file.relativePath, file.category)
            for (dir in allocator.parentsOf(path)) {
                if (dir == ArchiveLayout.FILES) continue
                if (emittedDirs.add(dir)) emit(SourceItem.Directory(dir))
            }
            emit(itemFor(file, path))
        }
    }.flowOn(io)

    private fun itemFor(file: ScannedFile, archivePath: String): SourceItem {
        val size = file.sizeBytes
        val scanMeta = EntryMetadata(modifiedAtMillis = file.modifiedAtMillis)
        return when (file.walker) {
            WalkerKind.NIO -> {
                val location = file.location
                val stat = LocalEntries.lstat(location)
                when {
                    stat != null && LocalEntries.isSymlink(stat) && options.structureMode == StructureMode.PRESERVE_HIERARCHY -> {
                        val target = LocalEntries.readlink(location)
                        if (target != null) {
                            SourceItem.Symlink(archivePath, target, LocalEntries.metadataOf(location, stat, options.preservePosixMetadata, options.preserveSelinuxContext))
                        } else {
                            fileItem(archivePath, size, scanMeta) { FileInputStream(location) }
                        }
                    }
                    stat != null && LocalEntries.isRegular(stat) -> {
                        val meta = LocalEntries.metadataOf(location, stat, options.preservePosixMetadata, options.preserveSelinuxContext)
                        fileItem(archivePath, stat.st_size, meta) { FileInputStream(location) }
                    }
                    // Vanished or no longer a regular file: opening fails the item honestly.
                    else -> fileItem(archivePath, size, scanMeta) { FileInputStream(location) }
                }
            }
            WalkerKind.SAF, WalkerKind.MEDIA_STORE -> {
                val uri = Uri.parse(file.location)
                fileItem(archivePath, size, scanMeta) {
                    context.contentResolver.openInputStream(uri) ?: throw SourceReadException("${file.location}: provider returned no stream")
                }
            }
            WalkerKind.PRIVILEGED_FIND -> {
                val location = file.location
                fileItem(archivePath, size, scanMeta) { streams.openFile(location) }
            }
        }
    }

    private fun fileItem(archivePath: String, size: Long, meta: EntryMetadata, open: suspend () -> InputStream): SourceItem.File =
        SourceItem.File(archivePath, size, meta) { ExactLengthInputStream(open(), size, archivePath) }
}

/** Runs (or adopts) a collecting file scan and wraps it in a [FileTreeSource]. */
@Singleton
class FileTreeSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: FileScanner,
    private val streams: RootByteStreams,
    private val strings: PipelineSourceStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Scan the paths and categories of [selection] with collectFiles=true. */
    suspend fun prepare(selection: TargetSelection, options: PipelineOptions, layout: ArchiveLayout, scanOptions: ScanOptions = ScanOptions(collectFiles = true)): SourcePreparation<FileTreeSource> =
        withContext(io) {
            if (selection.paths.isEmpty() && selection.categories.isEmpty()) return@withContext strings.unavailable(SourceUnavailableReason.NOTHING_SELECTED)
            when (val event = scanner.run(selection, scanOptions.copy(collectFiles = true))) {
                is ScanEvent.InvalidFilter -> strings.unavailable(SourceUnavailableReason.INVALID_FILTER, event.problems.joinToString { it.toString() })
                is ScanEvent.Completed -> fromResult(event.result, options, layout)
                is ScanEvent.Progress -> strings.unavailable(SourceUnavailableReason.SCAN_FAILED, "scan ended without a result")
            }
        }

    /** Wrap an existing collecting scan. */
    fun fromResult(result: ScanResult, options: PipelineOptions, layout: ArchiveLayout): SourcePreparation<FileTreeSource> {
        val warnings = ArrayList<SourceWarning>()
        if (result.truncated) warnings += SourceWarning(SourceWarningKind.FILES_TRUNCATED, result.files.size.toString())
        for (root in result.blockedRoots) {
            warnings += SourceWarning(SourceWarningKind.FILES_ROOT_BLOCKED, root.location, (root.blocked ?: root.rootProblem)?.toString())
        }
        if (result.summary.skippedUnreadable > 0) {
            warnings += SourceWarning(SourceWarningKind.FILES_UNREADABLE_ENTRIES, result.summary.skippedUnreadable.toString())
        }
        return SourcePreparation.Ready(FileTreeSource(context, result.files, result.roots.map { it.location }, options, layout, streams, io), warnings)
    }
}
