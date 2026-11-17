package ca.devdcs.backup.core.restore.files

import ca.devdcs.backup.core.pipeline.ChecksumEngine
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryInfo
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryType
import ca.devdcs.backup.core.pipeline.archive.ArchiveIoException
import ca.devdcs.backup.core.pipeline.archive.ArchivePaths
import ca.devdcs.backup.core.pipeline.checksum.ChecksumSidecar
import ca.devdcs.backup.core.pipeline.layout.FilePathAllocator
import ca.devdcs.backup.core.restore.ConflictPolicy
import ca.devdcs.backup.core.restore.catalog.CatalogException
import ca.devdcs.backup.core.restore.catalog.RestoreStreamCancelledException
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** One archive entry to restore; [open] is called at most once and only for FILE entries. */
class ExtractEntry(
    val info: ArchiveEntryInfo,
    /** Content of a FILE entry (shared archive stream or a decoded tree item); closed by the extractor. */
    val open: (suspend () -> InputStream)?,
    /** Source digests from the manifest, checked before the file is moved into place. */
    val expectedDigests: Map<ChecksumAlgorithm, String> = emptyMap(),
)

/** Where an archive path lands: which target and the path relative to its root. */
class RoutedPath(val target: TargetFileSystem, val path: String)

/** Machine-readable per-entry problems; the engine localizes them. */
enum class ExtractIssueCode {
    /** Absolute, `..`, NUL or otherwise unsafe path: refused. */
    UNSAFE_PATH,
    /** A parent component is a symlink (extracted or pre-existing): refused, never followed. */
    THROUGH_SYMLINK,
    /** A parent component exists and is not a directory. */
    PARENT_NOT_DIRECTORY,
    /** The target exists as a directory where a file/link must go (never deleted). */
    DIRECTORY_IN_THE_WAY,
    /** Existing file kept (ConflictPolicy.SKIP). */
    SKIPPED_EXISTING,
    SYMLINK_UNSUPPORTED,
    HARDLINK_UNSUPPORTED,
    HARDLINK_TARGET_MISSING,
    /** FIFO / device node: never recreated. */
    SPECIAL_SKIPPED,
    /** Restored bytes differ from the manifest digest: the file was not put in place. */
    DIGEST_MISMATCH,
    WRITE_FAILED,
    METADATA_FAILED,
}

data class ExtractIssue(val code: ExtractIssueCode, val path: String, val detail: String? = null)

/** Totals of one extraction. [issues] is capped; [issueCount] is the real number. */
data class ExtractSummary(
    val files: Int,
    val directories: Int,
    val links: Int,
    val skipped: Int,
    val failed: Int,
    val bytes: Long,
    val issues: List<ExtractIssue>,
    val issueCount: Int,
)

/** Callbacks the extractor reports through. */
interface ExtractProgress {
    fun onBytes(delta: Long)
    fun onItem(path: String)
    fun isCancelled(): Boolean
}

/**
 * Writes archive entries into [TargetFileSystem]s safely. The rules that make it safe live here,
 * once, for every target:
 * - every mapped path is re-normalized; absolute / `..` / NUL paths are refused;
 * - no write ever goes through a symlink: parents are lstat'ed and must be real directories,
 * symlinks created by this extraction are remembered, and a final symlink is replaced (rename
 * over it), never written through;
 * - file content is written to a temp file and moved into place only after its manifest digest
 * matched, so a corrupt or partial file never replaces a good one;
 * - directories are merged, never deleted; FIFOs/devices are never created;
 * - metadata (mtime; mode/uid/gid/SELinux only when the target can, i.e. root) is applied at the
 * end, deepest paths first, so directory mtimes are not disturbed by their children.
 *
 * One instance per restore unit; not thread-safe.
 */
class FileExtractor(
    private val route: (archivePath: String) -> RoutedPath?,
    private val policy: ConflictPolicy,
    private val restoreMetadata: Boolean,
    private val checksumEngine: ChecksumEngine,
    private val io: CoroutineDispatcher,
    private val progress: ExtractProgress,
) {
    private class TargetState {
        val knownDirs = HashSet<String>()
        val createdLinks = HashSet<String>()
        /** One op per path: a later entry at the same path replaces (or clears) the earlier op. */
        val metadata = LinkedHashMap<String, MetadataOp>()
    }

    private val states = LinkedHashMap<TargetFileSystem, TargetState>()
    /** archive path → where the regular file landed (hard-link sources). */
    private val placedFiles = HashMap<String, RoutedPath>()
    private val issues = ArrayList<ExtractIssue>()
    private var issueCount = 0
    private var files = 0
    private var directories = 0
    private var links = 0
    private var skipped = 0
    private var failed = 0
    private var bytes = 0L

    private fun state(target: TargetFileSystem) = states.getOrPut(target) { TargetState() }

    private fun issue(code: ExtractIssueCode, path: String, detail: String? = null) {
        issueCount++
        if (issues.size < MAX_ISSUES) issues += ExtractIssue(code, path, detail)
    }

    /** Restore one entry. Throws only for cancellation and target-wide failures (no space). */
    suspend fun extract(entry: ExtractEntry) {
        currentCoroutineContext().ensureActive()
        if (progress.isCancelled()) throw RestoreStreamCancelledException()
        val archivePath = entry.info.path
        val routed = route(archivePath) ?: return
        val path = safePath(routed.path)
        if (path == null) {
            failed++
            issue(ExtractIssueCode.UNSAFE_PATH, archivePath)
            return
        }
        progress.onItem(archivePath)
        val target = routed.target
        try {
            if (!prepareParents(target, path, archivePath)) {
                failed++
                return
            }
            when (entry.info.type) {
                ArchiveEntryType.DIRECTORY -> restoreDirectory(target, path, entry)
                ArchiveEntryType.FILE -> restoreFile(target, path, entry)
                ArchiveEntryType.SYMLINK -> restoreSymlink(target, path, entry)
                ArchiveEntryType.HARD_LINK -> restoreHardLink(target, path, entry)
                ArchiveEntryType.OTHER -> {
                    skipped++
                    issue(ExtractIssueCode.SPECIAL_SKIPPED, archivePath)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (isTargetWide(e)) throw e
            failed++
            val corrupt = e is CatalogException && (e.reason == CatalogException.Reason.DIGEST_MISMATCH || e.reason == CatalogException.Reason.SIZE_MISMATCH)
            issue(if (corrupt) ExtractIssueCode.DIGEST_MISMATCH else ExtractIssueCode.WRITE_FAILED, archivePath, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Apply deferred metadata and return the totals. */
    suspend fun finish(): ExtractSummary {
        if (restoreMetadata) {
            for ((target, state) in states) {
                if (state.metadata.isEmpty()) continue
                if (!target.canSetMtime && !target.canSetOwnership) continue
                // Deepest first: setting a child's metadata cannot change its parent's mtime then.
                val ordered = state.metadata.values.sortedByDescending { it.path.count { c -> c == '/' } }
                try {
                    target.applyMetadata(ordered, ownership = target.canSetOwnership).forEach { issue(ExtractIssueCode.METADATA_FAILED, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    issue(ExtractIssueCode.METADATA_FAILED, target.label, e.message)
                }
            }
        }
        return ExtractSummary(files, directories, links, skipped, failed, bytes, issues.toList(), issueCount)
    }

    // --- path safety ---------------------------------------------------------------------------

    private fun safePath(raw: String): String? {
        if (raw.isEmpty()) return null
        val normalized = try {
            ArchivePaths.normalizeForRead(raw)
        } catch (e: ArchiveIoException) {
            return null
        } ?: return null
        // Backslashes are legal file-name characters on POSIX but a traversal vector elsewhere.
        if (normalized.contains('\\')) return null
        return normalized
    }

    /** Every parent must be a real directory (created when missing); false = entry refused. */
    private suspend fun prepareParents(target: TargetFileSystem, path: String, archivePath: String): Boolean {
        val state = state(target)
        val segments = path.split('/')
        var prefix = ""
        for (i in 0 until segments.size - 1) {
            prefix = if (prefix.isEmpty()) segments[i] else prefix + "/" + segments[i]
            if (prefix in state.createdLinks) {
                issue(ExtractIssueCode.THROUGH_SYMLINK, archivePath, prefix)
                return false
            }
            if (prefix in state.knownDirs) continue
            when (target.lstat(prefix)?.type) {
                null -> {
                    target.mkdir(prefix)
                    directories++
                }
                TargetNodeType.DIRECTORY -> Unit
                TargetNodeType.SYMLINK -> {
                    issue(ExtractIssueCode.THROUGH_SYMLINK, archivePath, prefix)
                    return false
                }
                else -> {
                    issue(ExtractIssueCode.PARENT_NOT_DIRECTORY, archivePath, prefix)
                    return false
                }
            }
            state.knownDirs += prefix
        }
        return true
    }

    // --- entry kinds ---------------------------------------------------------------------------

    private suspend fun restoreDirectory(target: TargetFileSystem, path: String, entry: ExtractEntry) {
        val state = state(target)
        if (path !in state.knownDirs) {
            when (target.lstat(path)?.type) {
                null -> {
                    target.mkdir(path)
                    directories++
                }
                TargetNodeType.DIRECTORY -> Unit
                else -> {
                    if (policy != ConflictPolicy.OVERWRITE) {
                        skipped++
                        issue(ExtractIssueCode.SKIPPED_EXISTING, entry.info.path)
                        return
                    }
                    target.deleteFile(path)
                    state.createdLinks -= path
                    target.mkdir(path)
                    directories++
                }
            }
            state.knownDirs += path
        }
        record(target, path, TargetNodeType.DIRECTORY, entry.info)
    }

    /** The path to write to, or null when the entry must be skipped. Second = replace existing. */
    private suspend fun destinationFor(target: TargetFileSystem, path: String, archivePath: String): Pair<String, Boolean>? {
        val existing = target.lstat(path) ?: return path to false
        if (existing.type == TargetNodeType.DIRECTORY) {
            if (policy == ConflictPolicy.KEEP_BOTH) return keepBothName(target, path)?.let { it to false }
            skipped++
            issue(ExtractIssueCode.DIRECTORY_IN_THE_WAY, archivePath)
            return null
        }
        return when (policy) {
            ConflictPolicy.SKIP -> {
                skipped++
                issue(ExtractIssueCode.SKIPPED_EXISTING, archivePath)
                null
            }
            ConflictPolicy.OVERWRITE -> path to true
            ConflictPolicy.KEEP_BOTH -> keepBothName(target, path)?.let { it to false }
        }
    }

    /** `photo.jpg` → `photo (2).jpg`, the first free one. */
    private suspend fun keepBothName(target: TargetFileSystem, path: String): String? {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        for (n in 2..MAX_KEEP_BOTH) {
            val candidate = FilePathAllocator.numbered(name, n)
            val full = if (dir.isEmpty()) candidate else "$dir/$candidate"
            if (target.lstat(full) == null) return full
        }
        skipped++
        issue(ExtractIssueCode.WRITE_FAILED, path, "no free name")
        return null
    }

    private suspend fun restoreFile(target: TargetFileSystem, path: String, entry: ExtractEntry) {
        val (finalPath, replace) = destinationFor(target, path, entry.info.path) ?: return
        val open = entry.open ?: throw IOException("No content for ${entry.info.path}")
        val dir = finalPath.substringBeforeLast('/', "")
        val temp = target.openTemp(dir, finalPath.substringAfterLast('/'))
        var committed = false
        try {
            val algorithms = entry.expectedDigests.keys
            val actual = withContext(io) {
                val tee = if (algorithms.isEmpty()) null else checksumEngine.tee(temp.stream, algorithms)
                val sink = tee ?: temp.stream
                open().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (progress.isCancelled()) throw RestoreStreamCancelledException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        sink.write(buffer, 0, n)
                        bytes += n
                        progress.onBytes(n.toLong())
                    }
                }
                sink.close()
                tee?.result()?.hex.orEmpty()
            }
            for ((algorithm, hex) in entry.expectedDigests) {
                val got = actual[algorithm]
                if (got == null || !ChecksumSidecar.matches(hex, got)) {
                    failed++
                    issue(ExtractIssueCode.DIGEST_MISMATCH, entry.info.path, algorithm.storageKey)
                    return
                }
            }
            target.commit(temp, finalPath, replace)
            committed = true
        } finally {
            if (!committed) target.discard(temp)
        }
        val state = state(target)
        state.createdLinks -= finalPath
        files++
        placedFiles[entry.info.path] = RoutedPath(target, finalPath)
        record(target, finalPath, TargetNodeType.FILE, entry.info)
    }

    private suspend fun restoreSymlink(target: TargetFileSystem, path: String, entry: ExtractEntry) {
        if (!target.supportsSymlinks) {
            skipped++
            issue(ExtractIssueCode.SYMLINK_UNSUPPORTED, entry.info.path)
            return
        }
        val linkTarget = entry.info.linkTarget ?: throw IOException("Symlink ${entry.info.path} has no target")
        if (linkTarget.indexOf('\u0000') >= 0) {
            failed++
            issue(ExtractIssueCode.UNSAFE_PATH, entry.info.path, "NUL in link target")
            return
        }
        val (finalPath, replace) = destinationFor(target, path, entry.info.path) ?: return
        if (replace) target.deleteFile(finalPath)
        target.symlink(finalPath, linkTarget)
        state(target).createdLinks += finalPath
        links++
        record(target, finalPath, TargetNodeType.SYMLINK, entry.info)
    }

    private suspend fun restoreHardLink(target: TargetFileSystem, path: String, entry: ExtractEntry) {
        val source = entry.info.linkTarget?.let { placedFiles[it] }
        if (source == null || source.target !== target) {
            failed++
            issue(ExtractIssueCode.HARDLINK_TARGET_MISSING, entry.info.path, entry.info.linkTarget)
            return
        }
        if (!target.supportsHardLinks) {
            skipped++
            issue(ExtractIssueCode.HARDLINK_UNSUPPORTED, entry.info.path)
            return
        }
        val (finalPath, replace) = destinationFor(target, path, entry.info.path) ?: return
        if (replace) target.deleteFile(finalPath)
        target.hardLink(finalPath, source.path)
        state(target).createdLinks -= finalPath
        state(target).metadata.remove(finalPath)
        links++
        placedFiles[entry.info.path] = RoutedPath(target, finalPath)
    }

    private fun record(target: TargetFileSystem, path: String, type: TargetNodeType, info: ArchiveEntryInfo) {
        val ops = state(target).metadata
        // Whatever was recorded for an earlier entry at this path no longer describes what is there.
        ops.remove(path)
        if (!restoreMetadata) return
        val m = info.metadata
        if (m.modifiedAtMillis == null && m.mode == null && m.uid == null && m.gid == null && m.seLinuxContext == null) return
        ops[path] = MetadataOp(path, type, m.modifiedAtMillis, m.mode, m.uid, m.gid, m.seLinuxContext)
    }

    /** Failures every later entry would hit too: abort the unit instead of failing N files. */
    private fun isTargetWide(e: Exception): Boolean {
        if (e is StorageException) return e.reason == StorageException.Reason.NO_SPACE
        if (e is CatalogException) return e.reason == CatalogException.Reason.KEY_REQUIRED
        if (e is ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException) {
            return e.reason == ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException.Reason.WRONG_KEY ||
                e.reason == ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException.Reason.KEY_TYPE_MISMATCH
        }
        val text = (e.message ?: "").lowercase()
        return "no space left" in text || "enospc" in text || "read-only file system" in text
    }

    companion object {
        const val MAX_ISSUES = 200
        const val MAX_KEEP_BOTH = 10_000
        private const val BUFFER = 64 * 1024
    }
}
