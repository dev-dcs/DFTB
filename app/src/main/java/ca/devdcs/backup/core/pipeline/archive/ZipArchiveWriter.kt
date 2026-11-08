package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.domain.model.ArchiveFormat
import kotlinx.coroutines.CoroutineDispatcher
import org.apache.commons.compress.archivers.zip.X7875_NewUnix
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry

/**
 * Streaming ZIP for users who want an archive any desktop opens. Deflate at the profile level,
 * Zip64 as needed (every entry's size is set up front, so >4 GiB entries get Zip64 local
 * headers even on a non-seekable stream), unix mode in the external attributes, uid/gid in the
 * Info-ZIP 0x7875 extra field, symlinks as unix symlink entries. ZIP has no standard place for
 * SELinux labels, so they are not stored; use tar for app data.
 *
 * Streams without an exact size are spooled to [stagingDir] first so Zip64 can be decided.
 */
internal class ZipArchiveWriter(
    out: OutputStream,
    level: Int,
    private val stagingDir: File,
    io: CoroutineDispatcher,
) : BaseArchiveWriter(out, io) {

    override val format: ArchiveFormat = ArchiveFormat.ZIP

    private val zip = ZipArchiveOutputStream(NonClosingOutputStream(out)).apply {
        setUseZip64(Zip64Mode.AsNeeded)
        setMethod(ZipEntry.DEFLATED)
        setLevel(level.coerceIn(0, 9))
        setEncoding(Charsets.UTF_8.name())
        setUseLanguageEncodingFlag(true)
    }

    override suspend fun writeItem(item: SourceItem, path: String) {
        when (item) {
            is SourceItem.Directory -> {
                val entry = ZipArchiveEntry("$path/")
                applyMetadata(entry, item.metadata, fullMode(item.metadata, S_IFDIR, DEFAULT_DIR_PERM))
                entry.size = 0
                zip.putArchiveEntry(entry)
                zip.closeArchiveEntry()
            }
            is SourceItem.Symlink -> {
                val target = item.target.toByteArray(Charsets.UTF_8)
                val entry = ZipArchiveEntry(path)
                applyMetadata(entry, item.metadata, fullMode(item.metadata, S_IFLNK, DEFAULT_LINK_PERM))
                entry.size = target.size.toLong()
                zip.putArchiveEntry(entry)
                zip.write(target, 0, target.size)
                zip.closeArchiveEntry()
            }
            is SourceItem.File, is SourceItem.Stream -> {
                val sized = StreamCopy.sized(item, stagingDir)
                try {
                    val entry = ZipArchiveEntry(path)
                    applyMetadata(entry, item.metadata, fullMode(item.metadata, S_IFREG, DEFAULT_FILE_PERM))
                    entry.size = sized.size
                    // On a plain stream AsNeeded decides on the uncompressed size only; deflate can
                    // expand incompressible data past 4 GiB, so force Zip64 for this entry near the limit.
                    zip.setUseZip64(zip64ModeFor(sized.size))
                    try {
                        sized.open().use { input ->
                            zip.putArchiveEntry(entry)
                            StreamCopy.copyExactly(input, sized.size, path) { b, o, l -> zip.write(b, o, l) }
                            zip.closeArchiveEntry()
                        }
                    } finally {
                        zip.setUseZip64(Zip64Mode.AsNeeded)
                    }
                } finally {
                    sized.release()
                }
            }
        }
    }

    override suspend fun finishArchive() {
        zip.finish()
        zip.close()
    }

    override fun release(completed: Boolean) {
        // Nothing beyond the Deflater, which is reclaimed with the stream; closing here would
        // write a central directory and make an abandoned archive look complete.
    }

    private fun applyMetadata(entry: ZipArchiveEntry, md: EntryMetadata, mode: Int) {
        entry.unixMode = mode
        md.modifiedAtMillis?.let { entry.setTime(it) }
        val uid = md.uid
        val gid = md.gid
        if (uid != null && gid != null) {
            entry.addExtraField(
                X7875_NewUnix().apply {
                    setUID(uid.toLong() and 0xFFFFFFFFL)
                    setGID(gid.toLong() and 0xFFFFFFFFL)
                },
            )
        }
    }

    internal companion object {
        private const val ZIP32_LIMIT = 0xFFFFFFFFL

        /** zlib deflateBound(): worst-case deflate output for [size] input bytes. */
        fun deflateBound(size: Long): Long = size + (size shr 12) + (size shr 14) + (size shr 25) + 13

        /** Always when the entry or its worst-case compressed form may reach the 32-bit limit. */
        fun zip64ModeFor(size: Long): Zip64Mode =
            if (deflateBound(size) + 4096 >= ZIP32_LIMIT) Zip64Mode.Always else Zip64Mode.AsNeeded
    }
}
