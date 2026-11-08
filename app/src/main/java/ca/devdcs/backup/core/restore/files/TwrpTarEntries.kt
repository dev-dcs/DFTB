package ca.devdcs.backup.core.restore.files

import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryInfo
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryType
import ca.devdcs.backup.core.pipeline.archive.ArchiveErrorKind
import ca.devdcs.backup.core.pipeline.archive.ArchiveIoException
import ca.devdcs.backup.core.pipeline.archive.ArchivePaths
import ca.devdcs.backup.core.pipeline.archive.ArchiveReadEntry
import ca.devdcs.backup.core.pipeline.archive.TarArchiveWriter
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Reads TWRP / OrangeFox `*.win` / `*.winNNN` tar volumes. Unlike DFTB's own archives, TWRP
 * member names are often absolute (`/data/app/...`) and hard links point at absolute paths; GNU
 * tar strips the leading '/' on extraction and so does this reader, after which the normal
 * traversal checks ([ArchivePaths.normalizeForRead]) still apply, so `..` remains refused.
 * gzip-compressed volumes are detected by magic; TWRP-encrypted volumes are refused.
 */
object TwrpTarEntries {

    private val TAR_VOLUME = Regex("^.+\\.[A-Za-z0-9_-]+\\.win(\\d{3})?$")
    private const val PERM_MASK = 0xFFF

    /** A TWRP file-system tar volume (`data.ext4.win`, `system.ext4.win001`), not a raw image. */
    fun isTarVolumeName(fileName: String): Boolean {
        if (!TAR_VOLUME.matches(fileName)) return false
        val fs = fileName.substringBeforeLast(".win").substringAfterLast('.')
        return fs != "emmc" && fs != "mtd" && fs != "bml"
    }

    /** TWRP-style member name → safe relative path, or null for the archive root itself. */
    fun normalizeName(raw: String): String? = ArchivePaths.normalizeForRead(raw.trimStart('/'))

    /**
     * Entries of one volume. Single pass; the returned streams are valid until the next entry.
     * @throws ArchiveIoException TRUNCATED when the volume lacks its end-of-archive record,
     * UNSUPPORTED_FORMAT when the volume is neither tar nor gzip-compressed tar.
     */
    fun entries(input: InputStream): Sequence<ArchiveReadEntry> = sequence {
        val tar = EofTar(sniff(input))
        while (true) {
            val entry = tar.nextEntry ?: break
            val path = normalizeName(entry.name) ?: continue
            val info = info(entry, path)
            yield(ArchiveReadEntry(info, if (info.type == ArchiveEntryType.FILE) Shield(tar) else ByteArrayInputStream(ByteArray(0))))
        }
        if (!tar.sawEof) throw ArchiveIoException(ArchiveErrorKind.TRUNCATED, "TWRP volume ended without its end-of-archive record")
    }

    private fun sniff(raw: InputStream): InputStream {
        val input = BufferedInputStream(raw, 64 * 1024)
        input.mark(512)
        val head = ByteArray(512)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        input.reset()
        if (read >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) return GZIPInputStream(input, 64 * 1024)
        if (read >= 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar") return input
        if (read >= 8 && String(head, 0, 8, Charsets.US_ASCII).startsWith("OAES")) {
            throw ArchiveIoException(ArchiveErrorKind.UNSUPPORTED_FORMAT, "TWRP-encrypted volume (OpenAES) cannot be read")
        }
        throw ArchiveIoException(ArchiveErrorKind.UNSUPPORTED_FORMAT, "Not a tar volume")
    }

    private fun info(entry: TarArchiveEntry, path: String): ArchiveEntryInfo {
        val type = when {
            entry.isDirectory -> ArchiveEntryType.DIRECTORY
            entry.isSymbolicLink -> ArchiveEntryType.SYMLINK
            entry.isLink -> ArchiveEntryType.HARD_LINK
            entry.isFIFO || entry.isCharacterDevice || entry.isBlockDevice -> ArchiveEntryType.OTHER
            entry.isFile -> ArchiveEntryType.FILE
            else -> ArchiveEntryType.OTHER
        }
        val target = when (type) {
            ArchiveEntryType.SYMLINK -> entry.linkName
            ArchiveEntryType.HARD_LINK -> normalizeName(entry.linkName)
            else -> null
        }
        val selinux = (entry.getExtraPaxHeader(TarArchiveWriter.PAX_SELINUX) ?: entry.getExtraPaxHeader(TarArchiveWriter.PAX_SELINUX_SCHILY))
            ?.trimEnd('\u0000')?.takeIf { it.isNotEmpty() }
        return ArchiveEntryInfo(
            path = path,
            type = type,
            sizeBytes = if (type == ArchiveEntryType.FILE) entry.size else 0L,
            linkTarget = target,
            metadata = EntryMetadata(
                modifiedAtMillis = entry.lastModifiedTime?.toMillis(),
                mode = entry.mode and PERM_MASK,
                uid = entry.longUserId.toInt(),
                gid = entry.longGroupId.toInt(),
                seLinuxContext = selinux,
            ),
        )
    }

    private class EofTar(input: InputStream) : TarArchiveInputStream(input, Charsets.UTF_8.name()) {
        var sawEof = false
            private set

        override fun isEOFRecord(record: ByteArray?): Boolean {
            val eof = super.isEOFRecord(record)
            if (eof && record != null) sawEof = true
            return eof
        }
    }

    private class Shield(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }
}
