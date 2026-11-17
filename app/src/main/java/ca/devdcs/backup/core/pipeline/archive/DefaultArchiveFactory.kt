package ca.devdcs.backup.core.pipeline.archive

import android.content.Context
import ca.devdcs.backup.core.pipeline.ArchiveFactory
import ca.devdcs.backup.core.pipeline.ArchiveWriter
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.CompressionCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one [ArchiveFactory]: maps an [ArchiveFormat] to its writer (and reader for restore) and a
 * [CompressionCodec] to its stream, so every pipeline path produces byte-identical containers.
 *
 * Writers never close the `out` they are given (the caller owns it); see [BaseArchiveWriter].
 * Temp space in [stagingDir] (app cache) is needed for: 7z (the whole archive), tar/zip streams
 * without an exact size (that stream), and reading zip/7z from a non-file source (the archive).
 * [localStagingBytes] gives the figure for free-space pre-flight.
 */
@Singleton
class DefaultArchiveFactory internal constructor(
    private val stagingDirProvider: () -> File,
    private val io: CoroutineDispatcher,
) : ArchiveFactory, ArchiveReaderFactory {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this({ File(context.cacheDir, STAGING_DIR_NAME) }, io)

    private val stagingDir: File get() = stagingDirProvider()

    /**
     * [compressionLevel] is clamped to the format's range; TAR ignores it. For RAW_COPY and
     * DD_IMAGE the caller wraps [out] with [compress] itself (PipelineOptions.compressionCodec).
     */
    override fun writer(format: ArchiveFormat, out: OutputStream, compressionLevel: Int): ArchiveWriter {
        val level = compressionLevel.coerceIn(0, format.maxCompressionLevel)
        return when (format) {
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST, ArchiveFormat.TAR_BZ2 ->
                TarArchiveWriter(format, out, tarCodec(format), level, stagingDir, io)
            ArchiveFormat.ZIP -> ZipArchiveWriter(out, level, stagingDir, io)
            ArchiveFormat.SEVEN_ZIP -> SevenZipArchiveWriter(out, level, stagingDir, io)
            ArchiveFormat.RAW_COPY, ArchiveFormat.DD_IMAGE -> SingleItemArchiveWriter(format, out, io)
        }
    }

    override fun compress(codec: CompressionCodec, level: Int, out: OutputStream): OutputStream =
        Codecs.compress(codec, level, out)

    override fun decompress(codec: CompressionCodec, input: InputStream): InputStream =
        Codecs.decompress(codec, input)

    /**
     * Opens [source] for sequential restore/verify. Tar formats are decompressed per [format];
     * zip/7z from a [ArchiveReadSource.Streamed] source are spooled to the staging dir first.
     *
     * @throws ArchiveArgumentException ([ArchiveErrorKind.UNSUPPORTED_FORMAT]) for RAW_COPY/DD_IMAGE,
     * which are plain byte streams: use [decompress] directly.
     */
    override suspend fun reader(format: ArchiveFormat, source: ArchiveReadSource): ArchiveReader = withContext(io) {
        when (format) {
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST, ArchiveFormat.TAR_BZ2 -> {
                val raw = when (source) {
                    is ArchiveReadSource.LocalFile -> FileInputStream(source.file)
                    is ArchiveReadSource.Streamed -> source.open()
                }
                try {
                    TarArchiveReader(format, Codecs.decompress(tarCodec(format), BufferedInputStream(raw, StreamCopy.BUFFER_SIZE)))
                } catch (t: Throwable) {
                    raw.close()
                    throw t
                }
            }
            ArchiveFormat.ZIP, ArchiveFormat.SEVEN_ZIP -> {
                val (file, spool) = when (source) {
                    is ArchiveReadSource.LocalFile -> source.file to null
                    is ArchiveReadSource.Streamed -> spoolArchive(source).let { it to it }
                }
                try {
                    if (format == ArchiveFormat.ZIP) ZipArchiveReader(file, spool) else SevenZipArchiveReader(file, spool)
                } catch (t: Throwable) {
                    spool?.delete()
                    throw t
                }
            }
            ArchiveFormat.RAW_COPY, ArchiveFormat.DD_IMAGE -> throw ArchiveArgumentException(
                ArchiveErrorKind.UNSUPPORTED_FORMAT,
                "${format.name} is a plain byte stream, not an archive; read it with decompress()",
            )
        }
    }

    /**
     * Local temp bytes [format] needs while writing: [totalBytes] (upper bound of the final
     * archive) for 7z, else the largest stream without an exact size ([largestInexactStreamBytes])
     * for tar/zip, else 0.
     */
    fun localStagingBytes(format: ArchiveFormat, totalBytes: Long, largestInexactStreamBytes: Long): Long = when (format) {
        ArchiveFormat.SEVEN_ZIP -> totalBytes
        ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST, ArchiveFormat.TAR_BZ2,
        ArchiveFormat.ZIP -> largestInexactStreamBytes
        ArchiveFormat.RAW_COPY, ArchiveFormat.DD_IMAGE -> 0L
    }

    /** The staging directory, for free-space checks against the right volume. */
    fun stagingDirectory(): File = stagingDir

    private suspend fun spoolArchive(source: ArchiveReadSource.Streamed): File {
        val dir = stagingDir
        if (!dir.isDirectory) dir.mkdirs()
        val tmp = File.createTempFile("dftb-read-", ".part", dir)
        try {
            source.open().use { input ->
                FileOutputStream(tmp).use { fos -> StreamCopy.copyAll(input) { b, o, l -> fos.write(b, o, l) } }
            }
            return tmp
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    internal companion object {
        const val STAGING_DIR_NAME = "archive_staging"

        fun tarCodec(format: ArchiveFormat): CompressionCodec = when (format) {
            ArchiveFormat.TAR_GZ -> CompressionCodec.GZIP
            ArchiveFormat.TAR_XZ -> CompressionCodec.XZ
            ArchiveFormat.TAR_ZST -> CompressionCodec.ZSTD
            ArchiveFormat.TAR_BZ2 -> CompressionCodec.BZIP2
            else -> CompressionCodec.NONE
        }
    }
}
