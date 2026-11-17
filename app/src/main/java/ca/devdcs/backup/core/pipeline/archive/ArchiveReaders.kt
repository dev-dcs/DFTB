package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.domain.model.ArchiveFormat
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.X7875_NewUnix
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

private const val S_IFMT = 0xF000
private const val S_IFLNK = 0xA000
private const val PERM_MASK = 0xFFF
private const val MAX_SYMLINK_TARGET = 64 * 1024

private val EMPTY: InputStream get() = ByteArrayInputStream(ByteArray(0))

/** Shields the shared archive stream from an entry consumer's close(). */
private class EntryStream(input: InputStream) : FilterInputStream(input) {
    override fun close() = Unit
}

/** Single-use guard + close bookkeeping shared by the readers. */
internal abstract class BaseArchiveReader(
    final override val format: ArchiveFormat,
    private val spool: File?,
) : ArchiveReader {
    private var iterated = false
    private var closed = false

    final override fun entries(): Sequence<ArchiveReadEntry> {
        check(!closed) { "${format.name} reader is closed" }
        check(!iterated) { "${format.name} reader entries() is single-use" }
        iterated = true
        return Sequence { iterator() }
    }

    protected abstract fun iterator(): Iterator<ArchiveReadEntry>
    protected abstract fun closeArchive()

    final override fun close() {
        if (closed) return
        closed = true
        try {
            closeArchive()
        } catch (_: Throwable) {
            // Never throws by contract.
        } finally {
            spool?.delete()
        }
    }
}

/** tar (already decompressed [input]); pax/GNU long names, pax sizes and SELinux labels. */
internal class TarArchiveReader(format: ArchiveFormat, input: InputStream) : BaseArchiveReader(format, null) {
    private val tar = EofTrackingTarInputStream(input)

    override fun iterator(): Iterator<ArchiveReadEntry> = iterator {
        while (true) {
            val entry = tar.nextEntry ?: break
            val path = ArchivePaths.normalizeForRead(entry.name) ?: continue
            val info = info(entry, path)
            yield(ArchiveReadEntry(info, if (info.type == ArchiveEntryType.FILE) EntryStream(tar) else EMPTY))
        }
        if (!tar.sawEofRecord) {
            throw ArchiveIoException(ArchiveErrorKind.TRUNCATED, "tar ended without its end-of-archive record")
        }
    }

    /**
     * commons-compress treats a stream that simply stops (or a short final record) like a proper
     * end. Only a real all-zero 512-byte record counts here, so truncation is reported.
     */
    private class EofTrackingTarInputStream(input: InputStream) : TarArchiveInputStream(input, Charsets.UTF_8.name()) {
        var sawEofRecord = false
            private set

        override fun isEOFRecord(record: ByteArray?): Boolean {
            val eof = super.isEOFRecord(record)
            if (eof && record != null) sawEofRecord = true
            return eof
        }
    }

    override fun closeArchive() = tar.close()

    internal companion object {
        fun info(entry: TarArchiveEntry, path: String): ArchiveEntryInfo {
            val type = when {
                entry.isDirectory -> ArchiveEntryType.DIRECTORY
                entry.isSymbolicLink -> ArchiveEntryType.SYMLINK
                entry.isLink -> ArchiveEntryType.HARD_LINK
                // isFile() is also true for FIFO/char/block entries; restore must not make them regular files.
                entry.isFIFO || entry.isCharacterDevice || entry.isBlockDevice -> ArchiveEntryType.OTHER
                entry.isFile -> ArchiveEntryType.FILE
                else -> ArchiveEntryType.OTHER
            }
            val target = when (type) {
                ArchiveEntryType.SYMLINK -> entry.linkName
                ArchiveEntryType.HARD_LINK -> ArchivePaths.normalizeForRead(entry.linkName)
                else -> null
            }
            val selinux = (entry.getExtraPaxHeader(TarArchiveWriter.PAX_SELINUX)
                ?: entry.getExtraPaxHeader(TarArchiveWriter.PAX_SELINUX_SCHILY))
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
    }
}

/** zip via the central directory (random access), entries in physical order. */
internal class ZipArchiveReader(file: File, spool: File?) : BaseArchiveReader(ArchiveFormat.ZIP, spool) {
    private val zip: ZipFile = ZipFile.builder().setFile(file).get()
    private var current: InputStream? = null

    override fun iterator(): Iterator<ArchiveReadEntry> = iterator {
        val all = zip.entriesInPhysicalOrder
        while (all.hasMoreElements()) {
            current?.close()
            current = null
            val entry = all.nextElement()
            val path = ArchivePaths.normalizeForRead(entry.name) ?: continue
            val type = when {
                entry.isDirectory -> ArchiveEntryType.DIRECTORY
                entry.isUnixSymlink -> ArchiveEntryType.SYMLINK
                else -> ArchiveEntryType.FILE
            }
            val unix = entry.extraFields.filterIsInstance<X7875_NewUnix>().firstOrNull()
            val info = ArchiveEntryInfo(
                path = path,
                type = type,
                sizeBytes = if (type == ArchiveEntryType.FILE) entry.size else 0L,
                linkTarget = if (type == ArchiveEntryType.SYMLINK) {
                    zip.getInputStream(entry).use { readSymlinkTarget(it) }
                } else {
                    null
                },
                metadata = EntryMetadata(
                    modifiedAtMillis = entry.time.takeIf { it >= 0 },
                    mode = unixPerm(entry),
                    uid = unix?.getUID()?.toInt(),
                    gid = unix?.getGID()?.toInt(),
                ),
            )
            val stream = if (type == ArchiveEntryType.FILE) zip.getInputStream(entry).also { current = it } else EMPTY
            yield(ArchiveReadEntry(info, stream))
        }
        current?.close()
        current = null
    }

    override fun closeArchive() {
        try {
            current?.close()
        } finally {
            zip.close()
        }
    }

    private fun unixPerm(entry: ZipArchiveEntry): Int? =
        if (entry.platform == ZipArchiveEntry.PLATFORM_UNIX && entry.unixMode != 0) entry.unixMode and PERM_MASK else null
}

/** 7z read sequentially (solid blocks decode once), p7zip unix attribute convention. */
internal class SevenZipArchiveReader(file: File, spool: File?) : BaseArchiveReader(ArchiveFormat.SEVEN_ZIP, spool) {
    private val sevenZ: SevenZFile = SevenZFile.builder().setFile(file).get()

    private val entryStream = object : InputStream() {
        override fun read(): Int = sevenZ.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = sevenZ.read(b, off, len)
        override fun close() = Unit
    }

    override fun iterator(): Iterator<ArchiveReadEntry> = iterator {
        while (true) {
            val entry = sevenZ.nextEntry ?: break
            if (entry.isAntiItem) continue
            val path = ArchivePaths.normalizeForRead(entry.name ?: continue) ?: continue
            val unixMode = unixMode(entry)
            val type = when {
                entry.isDirectory -> ArchiveEntryType.DIRECTORY
                unixMode != null && unixMode and S_IFMT == S_IFLNK -> ArchiveEntryType.SYMLINK
                else -> ArchiveEntryType.FILE
            }
            val target = if (type == ArchiveEntryType.SYMLINK) readSymlinkTarget(entryStream) else null
            val info = ArchiveEntryInfo(
                path = path,
                type = type,
                sizeBytes = if (type == ArchiveEntryType.FILE) entry.size else 0L,
                linkTarget = target,
                metadata = EntryMetadata(
                    modifiedAtMillis = if (entry.hasLastModifiedDate) entry.lastModifiedTime?.toMillis() else null,
                    mode = unixMode?.and(PERM_MASK),
                ),
            )
            yield(ArchiveReadEntry(info, if (type == ArchiveEntryType.FILE && entry.hasStream()) entryStream else EMPTY))
        }
    }

    override fun closeArchive() = sevenZ.close()

    private fun unixMode(entry: SevenZArchiveEntry): Int? {
        if (!entry.hasWindowsAttributes) return null
        val attrs = entry.windowsAttributes
        if (attrs and SevenZipArchiveWriter.FILE_ATTRIBUTE_UNIX_EXTENSION == 0) return null
        return (attrs ushr 16).takeIf { it != 0 }
    }
}

/** Reads a symlink target with a hard cap, so a hostile archive cannot make us buffer gigabytes. */
private fun readSymlinkTarget(input: InputStream): String {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > MAX_SYMLINK_TARGET) {
            throw ArchiveIoException(ArchiveErrorKind.UNSAFE_PATH, "Symlink target longer than $MAX_SYMLINK_TARGET bytes")
        }
    }
    return out.toString(Charsets.UTF_8.name())
}
