package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.core.pipeline.ArchiveWriter
import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.SourceItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Lifecycle and threading shared by every [ArchiveWriter]: path validation, the
 * open -> finished/closed state machine, and moving all blocking work onto the IO dispatcher.
 *
 * Ownership: writers never close the caller's [OutputStream]; [finish] flushes it, the caller
 * closes it. [close] without [finish] writes neither the archive trailer nor a codec trailer, so
 * an abandoned artifact is detectably truncated.
 */
internal abstract class BaseArchiveWriter(
    protected val out: OutputStream,
    private val io: CoroutineDispatcher,
) : ArchiveWriter {

    private var finished = false
    private var closed = false

    final override suspend fun write(item: SourceItem) {
        checkOpen()
        val path = ArchivePaths.requireSafeForWrite(item.archivePath)
        withContext(io) { writeItem(item, path) }
    }

    final override suspend fun finish() {
        checkOpen()
        finished = true
        withContext(io) {
            finishArchive()
            out.flush()
        }
    }

    final override fun close() {
        if (closed) return
        closed = true
        try {
            release(completed = finished)
        } catch (_: Throwable) {
            // close() must never throw; the archive is already finished or abandoned.
        }
    }

    private fun checkOpen() {
        if (closed) throw ArchiveStateException("${format.name} writer is closed")
        if (finished) throw ArchiveStateException("${format.name} writer is already finished")
    }

    /** Write one validated item; [path] has no trailing '/'. Runs on the IO dispatcher. */
    protected abstract suspend fun writeItem(item: SourceItem, path: String)

    /** Write trailer/central directory into [out]. Runs on the IO dispatcher. */
    protected abstract suspend fun finishArchive()

    /** Free resources; [completed] is true when [finish] already succeeded or was attempted. */
    protected abstract fun release(completed: Boolean)

    protected companion object {
        const val S_IFMT = 0xF000
        const val S_IFDIR = 0x4000
        const val S_IFREG = 0x8000
        const val S_IFLNK = 0xA000
        const val PERM_MASK = 0xFFF // 07777
        const val DEFAULT_FILE_PERM = 0x1A4 // 0644
        const val DEFAULT_DIR_PERM = 0x1ED // 0755
        const val DEFAULT_LINK_PERM = 0x1FF // 0777

        /** Full st_mode with the type bits we decide and the permission bits from [md] (or a default). */
        fun fullMode(md: EntryMetadata, typeBits: Int, defaultPerm: Int): Int =
            typeBits or ((md.mode ?: defaultPerm) and PERM_MASK)
    }
}
