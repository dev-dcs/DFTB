package ca.devdcs.backup.core.restore.files

import java.io.OutputStream

/** What sits at a target path, observed WITHOUT following a final symlink (lstat semantics). */
enum class TargetNodeType { FILE, DIRECTORY, SYMLINK, OTHER }

data class TargetNode(val type: TargetNodeType, val sizeBytes: Long? = null)

/** A file being written under a temporary name next to its final location. */
class TempWrite(val tempPath: String, val stream: OutputStream)

/**
 * Metadata to apply to one restored path after its content is in place. Ownership, mode and the
 * SELinux label are only applied by targets that can (root); mtime by most.
 */
data class MetadataOp(
    val path: String,
    val type: TargetNodeType,
    val modifiedAtMillis: Long?,
    val mode: Int?,
    val uid: Int?,
    val gid: Int?,
    val seLinuxContext: String?,
)

/**
 * A restore destination folder. Paths are '/'-separated and relative to the target root (`""` is
 * the root itself); the [FileExtractor] has already validated them (no `..`, no absolute paths,
 * no traversal through symlinks), so implementations only perform the primitive operation and
 * must never follow a symlink at the final component when writing, replacing or deleting.
 *
 * Exists so one extractor drives in-process NIO folders, SAF trees and root/Shizuku-only paths.
 * Methods throw IOException on failure; they block, so callers run them on the IO dispatcher.
 */
interface TargetFileSystem : AutoCloseable {
    /** User-facing description of the target root (path or tree name). */
    val label: String
    val supportsSymlinks: Boolean
    val supportsHardLinks: Boolean
    /** chown/chmod/chcon are possible (root tier). */
    val canSetOwnership: Boolean
    val canSetMtime: Boolean

    suspend fun lstat(path: String): TargetNode?

    /** Create one directory whose parent exists. */
    suspend fun mkdir(path: String)

    /** Open a fresh temp file in directory [dir] (which exists) for the future [finalName]. */
    suspend fun openTemp(dir: String, finalName: String): TempWrite

    /** Move a closed temp file to [path]; [replace] atomically replaces a file or symlink there. */
    suspend fun commit(temp: TempWrite, path: String, replace: Boolean)

    /** Remove a temp file after a failure. Never throws. */
    suspend fun discard(temp: TempWrite)

    /** Delete a file, symlink or special file (never a directory, never following links). */
    suspend fun deleteFile(path: String)

    suspend fun symlink(path: String, target: String)

    suspend fun hardLink(path: String, existing: String)

    /** Apply [ops]; returns user-facing descriptions of the ones that failed. */
    suspend fun applyMetadata(ops: List<MetadataOp>, ownership: Boolean): List<String>

    /** Free bytes on the target filesystem, or null when unknown. */
    suspend fun freeBytes(): Long?

    override fun close()
}
