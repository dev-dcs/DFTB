package ca.devdcs.backup.core.restore.twrp

import ca.devdcs.backup.domain.model.ChecksumAlgorithm

/** What a TWRP `.win` set holds. */
enum class TwrpPayloadKind {
    /** `<name>.emmc.win`: a raw `dd` image of one partition. */
    RAW_IMAGE,

    /** `<name>.<fs>.win[NNN]`: a tar of a mounted filesystem (ext4, f2fs, vfat...). */
    FILESYSTEM_ARCHIVE,
}

/** A checksum sidecar next to one `.win` file (`.sha2`, `.sha256`, `.md5`...). */
data class TwrpSidecar(val fileName: String, val path: String, val algorithm: ChecksumAlgorithm)

/** One stored file of a partition backup (the whole archive, or one split part). */
data class TwrpFile(
    val fileName: String,
    /** Session-relative path. */
    val path: String,
    val sizeBytes: Long?,
    /** Split index (`000` -> 0, `101` -> 101), null for an unsplit file. */
    val splitIndex: Int?,
    val sidecar: TwrpSidecar?,
)

/** One partition of a TWRP backup folder. */
data class TwrpPartitionBackup(
    /** TWRP backup name: `boot`, `data`, `system_image`, `efs`... */
    val backupName: String,
    /** `emmc` for raw images, else the filesystem TWRP read the files from. */
    val fileSystem: String,
    val kind: TwrpPayloadKind,
    /** Ordered: a single file, or every split part by index. */
    val parts: List<TwrpFile>,
    /** `<name>.info` when present (TWRP's optional restore hints; not needed to restore). */
    val infoFile: String?,
) {
    val isSplit: Boolean get() = parts.any { it.splitIndex != null }
    val totalBytes: Long? get() = parts.map { it.sizeBytes }.takeIf { sizes -> sizes.all { it != null } }?.sumOf { it ?: 0L }

    /** The partition this image normally restores to (`system_image` -> `system`). */
    val partitionName: String get() = backupName.removeSuffix(TwrpFolderParser.IMAGE_NAME_SUFFIX)
}

/** Non-fatal oddities of a folder, reported to the user instead of hidden. */
enum class TwrpFolderProblemKind {
    /** A split set misses an index between its first and last part. */
    SPLIT_GAP,

    /** Both `<file>` and `<file>000` exist; TWRP restores the unsplit file, so the parts are ignored. */
    SPLIT_AND_SINGLE,

    /** A checksum file without its archive. */
    ORPHAN_SIDECAR,

    /** Several checksum files for one archive; the strongest is used. */
    DUPLICATE_SIDECAR,
}

data class TwrpFolderProblem(val kind: TwrpFolderProblemKind, val subject: String)

/**
 * A TWRP / OrangeFox backup folder (`TWRP/BACKUPS/<serial>/<name>/`), as written by TWRP itself or
 * by DFTB's NandroidLayout. Exists so restore can treat both the same way.
 */
data class TwrpBackup(
    /** Session-relative folder path. */
    val folderPath: String,
    /** Backup name (folder name). */
    val name: String,
    /** `<serial>` folder above it when the folder sits under `TWRP/BACKUPS/<serial>/`; else null. */
    val deviceId: String?,
    val partitions: List<TwrpPartitionBackup>,
    /** `recovery.log` path when present. */
    val logFile: String?,
    val otherFiles: List<String>,
    val problems: List<TwrpFolderProblem>,
)

/** How the first bytes of a `.win` look. */
enum class TwrpPayloadFormat {
    /** Uncompressed tar (`ustar` magic or a valid header checksum). */
    TAR,

    /** gzip (TWRP's "compression" option, pigz); decompresses to tar. */
    GZIP,

    /** TWRP-encrypted (OpenAES, `OAES` magic). DFTB cannot decrypt it. */
    ENCRYPTED,

    /** Raw partition bytes (emmc images). */
    RAW,

    /** Not a recognised archive (filesystem backups only). */
    UNKNOWN,

    /** Empty file. */
    EMPTY,
}

/** Status of one checksum comparison. */
enum class TwrpCheckStatus { OK, MISMATCH, NO_SIDECAR, SIDECAR_UNREADABLE, READ_FAILED }

data class TwrpFileCheck(
    val file: TwrpFile,
    val status: TwrpCheckStatus,
    val algorithm: ChecksumAlgorithm?,
    val expectedHex: String?,
    val actualHex: String?,
    /** Technical detail for READ_FAILED / SIDECAR_UNREADABLE. */
    val detail: String? = null,
) {
    val ok: Boolean get() = status == TwrpCheckStatus.OK
}

/** Outcome of extracting a filesystem `.win` set into a folder. */
data class TwrpExtractResult(
    val ok: Boolean,
    val filesWritten: Long,
    val directoriesCreated: Long,
    val bytesWritten: Long,
    val warnings: List<String>,
    val error: String? = null,
)
