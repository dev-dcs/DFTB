package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.domain.model.ArchiveFormat
import java.io.File
import java.io.InputStream

/** Kind of an archive entry, as restore needs to recreate it. */
enum class ArchiveEntryType { FILE, DIRECTORY, SYMLINK, HARD_LINK, OTHER }

/**
 * What a restore needs to know about one entry before deciding to extract it. [path] is already
 * normalized and traversal-safe ([ArchivePaths.normalizeForRead]); [linkTarget] is the raw
 * symlink target (may be absolute or contain '..' as symlinks legitimately do; the extractor must
 * never write *through* an extracted link) or the normalized path of a hard link's target.
 * [sizeBytes] is -1 when the format does not record it.
 */
data class ArchiveEntryInfo(
    val path: String,
    val type: ArchiveEntryType,
    val sizeBytes: Long,
    val linkTarget: String?,
    val metadata: EntryMetadata,
)

/**
 * One entry plus its content. [stream] is only valid until the next entry is requested (it reads
 * the shared archive stream); closing it does not close the archive. Empty for non-files.
 */
class ArchiveReadEntry(val info: ArchiveEntryInfo, val stream: InputStream)

/** Where an archive to read comes from. */
sealed interface ArchiveReadSource {
    /** A local file; zip and 7z read it in place. */
    data class LocalFile(val file: File) : ArchiveReadSource

    /**
     * A one-shot stream (network download, decrypted pipe). Tar formats read it directly; zip and
     * 7z need random access and spool it to the staging directory first (free space = archive size).
     */
    class Streamed(val open: () -> InputStream) : ArchiveReadSource
}

/**
 * Sequential reader over tar(.gz/.xz/.zst/.bz2), zip and 7z archives for restore and verify.
 * Exists so can walk any archive DFTB writes (and common foreign ones) with full POSIX
 * and SELinux metadata, while path safety is enforced in one place.
 *
 * Blocking: iterate on the IO dispatcher. Single pass: [entries] may be called once. An unsafe
 * entry path aborts iteration with [ArchiveIoException] ([ArchiveErrorKind.UNSAFE_PATH]).
 */
interface ArchiveReader : AutoCloseable {
    val format: ArchiveFormat
    fun entries(): Sequence<ArchiveReadEntry>
    /** Releases the archive and deletes any spool file. Idempotent; never throws. */
    override fun close()
}

/**
 * Opens archives for restore/verify. Separate from the shared ArchiveFactory contract so the
 * pipeline contract stays unchanged; implemented by [DefaultArchiveFactory].
 */
interface ArchiveReaderFactory {
    /**
     * Opens [source] as [format]. Tar formats decompress per format; zip/7z from a streamed
     * source spool to app cache first. RAW_COPY/DD_IMAGE throw [ArchiveArgumentException]
     * ([ArchiveErrorKind.UNSUPPORTED_FORMAT]): they are plain byte streams.
     */
    suspend fun reader(format: ArchiveFormat, source: ArchiveReadSource): ArchiveReader
}
