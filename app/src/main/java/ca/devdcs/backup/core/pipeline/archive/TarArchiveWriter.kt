package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.CompressionCodec
import kotlinx.coroutines.CoroutineDispatcher
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.File
import java.io.OutputStream
import java.nio.file.attribute.FileTime

/**
 * POSIX (pax) tar, optionally inside a codec (tar.gz/xz/zst/bz2). Pax is required for what
 * Android backups contain: names over 100 bytes, files over 8 GiB (BIGNUMBER_POSIX writes a
 * "size" pax record), large uids, and the SELinux label as `RHT.security.selinux` (the key GNU
 * tar `--selinux` and TWRP's libtar read and write), so restores relabel app data correctly.
 *
 * Streams without an exact size are spooled to [stagingDir] first (tar headers precede data);
 * that needs free space equal to the largest such stream.
 */
internal class TarArchiveWriter(
    override val format: ArchiveFormat,
    out: OutputStream,
    codec: CompressionCodec,
    level: Int,
    private val stagingDir: File,
    io: CoroutineDispatcher,
) : BaseArchiveWriter(out, io) {

    // Bottom-up: caller stream (never closed) <- codec <- tar.
    private val codecStream: OutputStream = Codecs.compress(codec, level, NonClosingOutputStream(out))
    private val tar: TarArchiveOutputStream = newTarStream(codecStream)

    override suspend fun writeItem(item: SourceItem, path: String) {
        when (item) {
            is SourceItem.Directory -> {
                val entry = TarArchiveEntry("$path/", TarConstants.LF_DIR)
                applyMetadata(entry, item.metadata, fullMode(item.metadata, S_IFDIR, DEFAULT_DIR_PERM))
                tar.putArchiveEntry(entry)
                tar.closeArchiveEntry()
            }
            is SourceItem.Symlink -> {
                val entry = TarArchiveEntry(path, TarConstants.LF_SYMLINK)
                entry.linkName = item.target
                applyMetadata(entry, item.metadata, fullMode(item.metadata, S_IFLNK, DEFAULT_LINK_PERM))
                tar.putArchiveEntry(entry)
                tar.closeArchiveEntry()
            }
            is SourceItem.File, is SourceItem.Stream -> {
                val sized = StreamCopy.sized(item, stagingDir)
                try {
                    val entry = TarArchiveEntry(path)
                    entry.size = sized.size
                    applyMetadata(entry, item.metadata, fullMode(item.metadata, S_IFREG, DEFAULT_FILE_PERM))
                    sized.open().use { input ->
                        tar.putArchiveEntry(entry)
                        StreamCopy.copyExactly(input, sized.size, path) { b, o, l -> tar.write(b, o, l) }
                        tar.closeArchiveEntry()
                    }
                } finally {
                    sized.release()
                }
            }
        }
    }

    override suspend fun finishArchive() {
        tar.finish()
        tar.close() // closes the codec (writing its trailer) and then the non-closing shield
    }

    override fun release(completed: Boolean) {
        // Deliberately neither the tar EOF records nor the codec trailer are written on abandon,
        // so a truncated tar never sits inside a well-formed gzip/xz/zstd stream. Compressor
        // memory (zstd native context, Deflater) is reclaimed by their finalizers/cleaners.
    }

    internal companion object {
        /** GNU tar --selinux / TWRP libtar key for the security.selinux xattr. */
        const val PAX_SELINUX = "RHT.security.selinux"
        /** star/bsdtar generic xattr spelling, accepted on read. */
        const val PAX_SELINUX_SCHILY = "SCHILY.xattr.security.selinux"

        fun newTarStream(out: OutputStream, blockSize: Int = TarConstants.DEFAULT_BLKSIZE): TarArchiveOutputStream =
            TarArchiveOutputStream(out, blockSize, Charsets.UTF_8.name()).apply {
                setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                setAddPaxHeadersForNonAsciiNames(true)
            }

        fun applyMetadata(entry: TarArchiveEntry, md: EntryMetadata, mode: Int) {
            entry.mode = mode
            md.modifiedAtMillis?.let { entry.setModTime(FileTime.fromMillis(it)) }
            md.uid?.let { entry.setUserId(it.toLong() and 0xFFFFFFFFL) }
            md.gid?.let { entry.setGroupId(it.toLong() and 0xFFFFFFFFL) }
            md.seLinuxContext?.takeIf { it.isNotEmpty() }?.let { entry.addPaxHeader(PAX_SELINUX, it) }
        }
    }
}
