package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.domain.model.ArchiveFormat
import kotlinx.coroutines.CoroutineDispatcher
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.attribute.FileTime

/**
 * 7z (LZMA2) for the best ratio on general files. The format writes its header at the end and
 * patches the start header, so it needs random access: the whole archive is staged as a temp
 * file in [stagingDir] (free space = final compressed size) and copied to [out] on finish. The
 * temp file is deleted on finish, failure and close.
 *
 * Unix mode (incl. symlink type) uses the p7zip convention: FILE_ATTRIBUTE_UNIX_EXTENSION
 * (0x8000) with st_mode in the high 16 bits; symlink targets are the entry content. uid/gid and
 * SELinux labels have no 7z field and are not stored.
 */
internal class SevenZipArchiveWriter(
    out: OutputStream,
    level: Int,
    stagingDir: File,
    io: CoroutineDispatcher,
) : BaseArchiveWriter(out, io) {

    override val format: ArchiveFormat = ArchiveFormat.SEVEN_ZIP

    /** Exposed for tests asserting cleanup. */
    internal val stagingFile: File

    private val sevenZ: SevenZOutputFile

    init {
        if (!stagingDir.isDirectory && !stagingDir.mkdirs() && !stagingDir.isDirectory) {
            throw IOException("Cannot create staging directory ${stagingDir.absolutePath}")
        }
        stagingFile = File.createTempFile("dftb-7z-", ".7z.part", stagingDir)
        try {
            sevenZ = SevenZOutputFile(stagingFile)
            sevenZ.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, Codecs.lzma2Options(level))))
        } catch (t: Throwable) {
            stagingFile.delete()
            throw t
        }
    }

    override suspend fun writeItem(item: SourceItem, path: String) {
        when (item) {
            is SourceItem.Directory -> {
                val entry = newEntry(path, item.metadata, fullMode(item.metadata, S_IFDIR, DEFAULT_DIR_PERM))
                entry.isDirectory = true
                entry.setHasStream(false)
                sevenZ.putArchiveEntry(entry)
                sevenZ.closeArchiveEntry()
            }
            is SourceItem.Symlink -> {
                val target = item.target.toByteArray(Charsets.UTF_8)
                val entry = newEntry(path, item.metadata, fullMode(item.metadata, S_IFLNK, DEFAULT_LINK_PERM))
                sevenZ.putArchiveEntry(entry)
                sevenZ.write(target)
                sevenZ.closeArchiveEntry()
            }
            is SourceItem.File -> {
                val entry = newEntry(path, item.metadata, fullMode(item.metadata, S_IFREG, DEFAULT_FILE_PERM))
                item.open().use { input ->
                    sevenZ.putArchiveEntry(entry)
                    StreamCopy.copyExactly(input, item.sizeBytes, path) { b, o, l -> sevenZ.write(b, o, l) }
                    sevenZ.closeArchiveEntry()
                }
            }
            is SourceItem.Stream -> {
                // 7z needs no size up front; verify it only when the source promised one.
                val entry = newEntry(path, item.metadata, fullMode(item.metadata, S_IFREG, DEFAULT_FILE_PERM))
                val exact = item.expectedSizeBytes?.takeIf { item.sizeIsExact }
                item.open().use { input ->
                    sevenZ.putArchiveEntry(entry)
                    if (exact != null) {
                        StreamCopy.copyExactly(input, exact, path) { b, o, l -> sevenZ.write(b, o, l) }
                    } else {
                        StreamCopy.copyAll(input) { b, o, l -> sevenZ.write(b, o, l) }
                    }
                    sevenZ.closeArchiveEntry()
                }
            }
        }
    }

    override suspend fun finishArchive() {
        try {
            sevenZ.finish()
            sevenZ.close()
            FileInputStream(stagingFile).use { input -> StreamCopy.copyAll(input) { b, o, l -> out.write(b, o, l) } }
        } finally {
            stagingFile.delete()
        }
    }

    override fun release(completed: Boolean) {
        try {
            sevenZ.close() // idempotent; only matters when finish() did not run to the end
        } finally {
            stagingFile.delete()
        }
    }

    private fun newEntry(path: String, md: EntryMetadata, mode: Int): SevenZArchiveEntry =
        SevenZArchiveEntry().apply {
            name = path
            md.modifiedAtMillis?.let {
                setHasLastModifiedDate(true)
                lastModifiedTime = FileTime.fromMillis(it)
            }
            setHasWindowsAttributes(true)
            val dosDir = if (mode and S_IFMT == S_IFDIR) FILE_ATTRIBUTE_DIRECTORY else 0
            windowsAttributes = FILE_ATTRIBUTE_UNIX_EXTENSION or dosDir or (mode shl 16)
        }

    internal companion object {
        const val FILE_ATTRIBUTE_DIRECTORY = 0x10
        const val FILE_ATTRIBUTE_UNIX_EXTENSION = 0x8000
    }
}
