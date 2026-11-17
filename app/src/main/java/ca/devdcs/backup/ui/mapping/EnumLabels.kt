package ca.devdcs.backup.ui.mapping

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ca.devdcs.backup.R
import ca.devdcs.backup.core.credits.BundleKind
import ca.devdcs.backup.core.environment.CheckStatus
import ca.devdcs.backup.core.environment.Readiness
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.StructureMode
import ca.devdcs.backup.domain.model.TransferMode

/*
 * Localized labels for the pure-Kotlin pipeline vocabulary (domain/model/PipelineEnums.kt). The
 * domain enums deliberately carry no Android resource ids, so this is the single place where a
 * storage key becomes user-facing text. Every `xxx.label()` has a non-composable `xxx.labelRes()`
 * twin for ViewModels, notifications and anything else that resolves strings through a Context.
 */

@StringRes
fun ArchiveFormat.labelRes(): Int = when (this) {
    ArchiveFormat.TAR -> R.string.format_tar
    ArchiveFormat.TAR_GZ -> R.string.format_tar_gz
    ArchiveFormat.TAR_XZ -> R.string.format_tar_xz
    ArchiveFormat.TAR_ZST -> R.string.format_tar_zst
    ArchiveFormat.TAR_BZ2 -> R.string.format_tar_bz2
    ArchiveFormat.ZIP -> R.string.format_zip
    ArchiveFormat.SEVEN_ZIP -> R.string.format_7z
    ArchiveFormat.RAW_COPY -> R.string.format_raw_copy
    ArchiveFormat.DD_IMAGE -> R.string.format_dd_image
}

@Composable
fun ArchiveFormat.label(): String = stringResource(labelRes())

@StringRes
fun ChecksumAlgorithm.labelRes(): Int = when (this) {
    ChecksumAlgorithm.MD5 -> R.string.checksum_md5
    ChecksumAlgorithm.SHA1 -> R.string.checksum_sha1
    ChecksumAlgorithm.SHA256 -> R.string.checksum_sha256
    ChecksumAlgorithm.SHA512 -> R.string.checksum_sha512
    ChecksumAlgorithm.BLAKE3 -> R.string.checksum_blake3
}

@Composable
fun ChecksumAlgorithm.label(): String = stringResource(labelRes())

@StringRes
fun CompressionCodec.labelRes(): Int = when (this) {
    CompressionCodec.NONE -> R.string.codec_none
    CompressionCodec.GZIP -> R.string.codec_gzip
    CompressionCodec.XZ -> R.string.codec_xz
    CompressionCodec.ZSTD -> R.string.codec_zstd
    CompressionCodec.BZIP2 -> R.string.codec_bzip2
}

@Composable
fun CompressionCodec.label(): String = stringResource(labelRes())

@StringRes
fun EncryptionMode.labelRes(): Int = when (this) {
    EncryptionMode.NONE -> R.string.encryption_none
    EncryptionMode.AES_256_GCM -> R.string.encryption_aes_256_gcm
    EncryptionMode.ZIP_AES -> R.string.encryption_zip_aes
    EncryptionMode.OPENSSL -> R.string.encryption_openssl
    EncryptionMode.GPG -> R.string.encryption_gpg
}

@Composable
fun EncryptionMode.label(): String = stringResource(labelRes())

@StringRes
fun StructureMode.labelRes(): Int = when (this) {
    StructureMode.PRESERVE_HIERARCHY -> R.string.structure_preserve_hierarchy
    StructureMode.CATEGORIZED -> R.string.structure_categorized
}

@Composable
fun StructureMode.label(): String = stringResource(labelRes())

@StringRes
fun TransferMode.labelRes(): Int = when (this) {
    TransferMode.LOCAL_STAGING -> R.string.transfer_local_staging
    TransferMode.DIRECT_STREAM -> R.string.transfer_direct_stream
    TransferMode.REMOTE_COMPRESS -> R.string.transfer_remote_compress
}

@Composable
fun TransferMode.label(): String = stringResource(labelRes())

@StringRes
fun DestinationType.labelRes(): Int = when (this) {
    DestinationType.LOCAL_INTERNAL -> R.string.destination_type_local_internal
    DestinationType.LOCAL_SAF -> R.string.destination_type_local_saf
    DestinationType.USB_OTG -> R.string.destination_type_usb_otg
    DestinationType.FTP -> R.string.destination_type_ftp
    DestinationType.FTPS -> R.string.destination_type_ftps
    DestinationType.SFTP -> R.string.destination_type_sftp
    DestinationType.SSH -> R.string.destination_type_ssh
    DestinationType.SMB -> R.string.destination_type_smb
    DestinationType.WEBDAV -> R.string.destination_type_webdav
    DestinationType.NFS -> R.string.destination_type_nfs
    DestinationType.RCLONE -> R.string.destination_type_rclone
    DestinationType.S3 -> R.string.destination_type_s3
    DestinationType.MEGA -> R.string.destination_type_mega
    DestinationType.PCLOUD -> R.string.destination_type_pcloud
}

@Composable
fun DestinationType.label(): String = stringResource(labelRes())

@StringRes
fun Readiness.labelRes(): Int = when (this) {
    Readiness.READY -> R.string.readiness_ready
    Readiness.DEGRADED -> R.string.readiness_degraded
    Readiness.UNAVAILABLE -> R.string.readiness_unavailable
}

@Composable
fun Readiness.label(): String = stringResource(labelRes())

/** [CheckStatus] already carries its resource id; exposed here so call sites read uniformly. */
@Composable
fun CheckStatus.label(): String = stringResource(labelRes)

/** [BundleKind] already carries its resource id; exposed here so call sites read uniformly. */
@Composable
fun BundleKind.label(): String = stringResource(labelRes)

/** Levels at or above this on a 0..19 scale (zstd, zstd-compressed dd images) are "ultra". */
const val ULTRA_COMPRESSION_THRESHOLD = 16

/**
 * Maps a numeric compression level to a coarse human label so a slider reads "Balanced" instead of
 * "7". The thirds are computed from the format's own maximum so the same wording fits a 0..9 gzip
 * scale and a 0..19 zstd scale.
 */
@StringRes
fun compressionLevelLabelRes(format: ArchiveFormat, level: Int): Int {
    if (level <= 0 || !format.supportsCompressionLevel) return R.string.compression_level_store
    val max = format.maxCompressionLevel.coerceAtLeast(1)
    if (max >= ULTRA_COMPRESSION_THRESHOLD && level >= ULTRA_COMPRESSION_THRESHOLD) return R.string.compression_level_ultra
    val third = (max / 3).coerceAtLeast(1)
    return when {
        level <= third -> R.string.compression_level_fast
        level <= third * 2 -> R.string.compression_level_balanced
        else -> R.string.compression_level_maximum
    }
}

@Composable
fun compressionLevelLabel(format: ArchiveFormat, level: Int): String = stringResource(compressionLevelLabelRes(format, level))
