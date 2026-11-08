package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.TransferMode

/** How one artifact's items are laid out at the destination. */
enum class ArtifactLayout {
    /** All items in one container written by an ArchiveWriter (tar/zip/7z). */
    ARCHIVE,

    /** Exactly one byte stream (a dd partition image) stored as one file. */
    RAW_STREAM,

    /** Every file stored as its own destination file under the artifact folder (1:1 copy). */
    FILE_TREE,
}

/**
 * Fully resolved recipe for one artifact. Exists so "the format implies the codec" and "dd/raw use
 * the profile codec" are decided once, in pure code, instead of inside the streaming loop.
 *
 * [format] is what the manifest records; [writerFormat] is what the ArchiveWriter is asked for
 * (always plain TAR for tar.* since the engine applies the codec itself, so compression can
 * never be applied twice), null for non-archive layouts. [substituted] is true when the profile
 * format could not apply to this source kind and a compatible one was chosen.
 */
data class ArtifactSpec(
    val layout: ArtifactLayout,
    val format: ArchiveFormat,
    val writerFormat: ArchiveFormat?,
    val codec: CompressionCodec,
    val compressionLevel: Int,
    val substituted: Boolean = false,
) {
    /** 7z needs random access, so its writer stages the whole archive locally first. */
    val needsLocalSpool: Boolean get() = writerFormat == ArchiveFormat.SEVEN_ZIP
}

/** Pure mapping from (source kind, profile options) to an [ArtifactSpec]. */
object ArtifactSpecResolver {

    /** Codec carried by a tar.* format; NONE for formats that compress internally or not at all. */
    fun impliedCodec(format: ArchiveFormat): CompressionCodec = when (format) {
        ArchiveFormat.TAR_GZ -> CompressionCodec.GZIP
        ArchiveFormat.TAR_XZ -> CompressionCodec.XZ
        ArchiveFormat.TAR_ZST -> CompressionCodec.ZSTD
        ArchiveFormat.TAR_BZ2 -> CompressionCodec.BZIP2
        else -> CompressionCodec.NONE
    }

    /** The tar.* format whose extension matches [codec]. */
    fun tarFormatFor(codec: CompressionCodec): ArchiveFormat = when (codec) {
        CompressionCodec.NONE -> ArchiveFormat.TAR
        CompressionCodec.GZIP -> ArchiveFormat.TAR_GZ
        CompressionCodec.XZ -> ArchiveFormat.TAR_XZ
        CompressionCodec.ZSTD -> ArchiveFormat.TAR_ZST
        CompressionCodec.BZIP2 -> ArchiveFormat.TAR_BZ2
    }

    fun isTarFamily(format: ArchiveFormat): Boolean = when (format) {
        ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST, ArchiveFormat.TAR_BZ2 -> true
        else -> false
    }

    /** Byte-for-byte storage (TWRP folders): no codec, one destination file per item. */
    val VERBATIM = ArtifactSpec(
        layout = ArtifactLayout.FILE_TREE,
        format = ArchiveFormat.RAW_COPY,
        writerFormat = null,
        codec = CompressionCodec.NONE,
        compressionLevel = 0,
    )

    fun resolve(kind: SourceKind, options: PipelineOptions): ArtifactSpec {
        val codecLevel = options.compressionLevel.coerceIn(0, options.compressionCodec.maxLevel)
        if (kind == SourceKind.PARTITION) {
            // A partition is always imaged; wrapping a block device in tar/zip gains nothing.
            return ArtifactSpec(
                layout = ArtifactLayout.RAW_STREAM,
                format = ArchiveFormat.DD_IMAGE,
                writerFormat = null,
                codec = options.compressionCodec,
                compressionLevel = codecLevel,
                // Imaging is the expected behaviour for partitions whatever the profile format, so
                // it is not reported as a substitution.
                substituted = false,
            )
        }
        val format = options.archiveFormat
        return when {
            isTarFamily(format) -> {
                val codec = impliedCodec(format)
                ArtifactSpec(
                    layout = ArtifactLayout.ARCHIVE,
                    format = format,
                    writerFormat = ArchiveFormat.TAR,
                    codec = codec,
                    compressionLevel = options.compressionLevel.coerceIn(0, codec.maxLevel),
                )
            }
            format == ArchiveFormat.ZIP || format == ArchiveFormat.SEVEN_ZIP -> ArtifactSpec(
                layout = ArtifactLayout.ARCHIVE,
                format = format,
                writerFormat = format,
                codec = CompressionCodec.NONE,
                compressionLevel = options.effectiveCompressionLevel,
            )
            format == ArchiveFormat.RAW_COPY -> ArtifactSpec(
                layout = ArtifactLayout.FILE_TREE,
                format = ArchiveFormat.RAW_COPY,
                writerFormat = null,
                codec = options.compressionCodec,
                compressionLevel = codecLevel,
            )
            else -> {
                // DD_IMAGE chosen for apps/files: a tree cannot be a block image, so archive it as
                // tar compressed with the profile codec (same bytes a tar.<codec> profile makes).
                ArtifactSpec(
                    layout = ArtifactLayout.ARCHIVE,
                    format = tarFormatFor(options.compressionCodec),
                    writerFormat = ArchiveFormat.TAR,
                    codec = options.compressionCodec,
                    compressionLevel = codecLevel,
                    substituted = true,
                )
            }
        }
    }
}

/** Result of deciding how bytes travel to the destination. */
sealed interface TransferResolution {
    /** Stream straight into `<name>.part` on the destination. */
    data object Direct : TransferResolution

    /** Build in the local staging dir, then upload with resume. */
    data object Staged : TransferResolution

    /** The requested mode cannot run; [detail] explains why (English, log-grade). */
    data class Unsupported(val detail: String, val forSsh: Boolean) : TransferResolution
}

/** Pure decision of the effective transfer strategy. */
object TransferPlanner {

    private val LOCAL_TYPES = setOf(DestinationType.LOCAL_INTERNAL, DestinationType.LOCAL_SAF, DestinationType.USB_OTG)

    fun isLocal(type: DestinationType): Boolean = type in LOCAL_TYPES

    fun resolve(mode: TransferMode, type: DestinationType): TransferResolution = when (mode) {
        TransferMode.DIRECT_STREAM -> TransferResolution.Direct
        // Staging next to a local destination would just copy the file twice on the same disk.
        TransferMode.LOCAL_STAGING -> if (isLocal(type)) TransferResolution.Direct else TransferResolution.Staged
        TransferMode.REMOTE_COMPRESS -> if (type == DestinationType.SSH || type == DestinationType.SFTP) {
            TransferResolution.Unsupported(
                "Remote compression needs a remote command channel, which the SSH storage session does not expose publicly yet",
                forSsh = true,
            )
        } else {
            TransferResolution.Unsupported("Remote compression is only possible on SSH destinations", forSsh = false)
        }
    }
}
