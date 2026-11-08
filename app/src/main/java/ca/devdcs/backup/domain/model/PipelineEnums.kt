package ca.devdcs.backup.domain.model

/**
 * Pure-Kotlin vocabulary shared by the database, the engines and the UI. No Android imports: the
 * UI maps these to localized labels in `ui/mapping`, and the engines map them to CLI flags.
 */

/** Container / method used to produce a backup artifact. */
enum class ArchiveFormat(
    val storageKey: String,
    val fileExtension: String,
    val supportsCompressionLevel: Boolean,
    /** Highest meaningful compression level for the underlying tool; 0 = store. */
    val maxCompressionLevel: Int,
    val isArchive: Boolean,
) {
    TAR("tar", ".tar", supportsCompressionLevel = false, maxCompressionLevel = 0, isArchive = true),
    TAR_GZ("tar_gz", ".tar.gz", supportsCompressionLevel = true, maxCompressionLevel = 9, isArchive = true),
    TAR_XZ("tar_xz", ".tar.xz", supportsCompressionLevel = true, maxCompressionLevel = 9, isArchive = true),
    TAR_ZST("tar_zst", ".tar.zst", supportsCompressionLevel = true, maxCompressionLevel = 19, isArchive = true),
    TAR_BZ2("tar_bz2", ".tar.bz2", supportsCompressionLevel = true, maxCompressionLevel = 9, isArchive = true),
    ZIP("zip", ".zip", supportsCompressionLevel = true, maxCompressionLevel = 9, isArchive = true),
    SEVEN_ZIP("7z", ".7z", supportsCompressionLevel = true, maxCompressionLevel = 9, isArchive = true),
    /** 1:1 file copy / sync (rsync, rclone copy, raw stream). */
    RAW_COPY("raw_copy", "", supportsCompressionLevel = false, maxCompressionLevel = 0, isArchive = false),
    /** Block-level partition image (dd), optionally compressed by [CompressionCodec]. */
    DD_IMAGE("dd_image", ".img", supportsCompressionLevel = true, maxCompressionLevel = 19, isArchive = false);

    companion object {
        val DEFAULT = TAR_ZST
        fun fromStorageKey(key: String?): ArchiveFormat = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/** Stand-alone stream compressor, used for dd images and raw streaming pipelines. */
enum class CompressionCodec(val storageKey: String, val fileExtension: String, val maxLevel: Int) {
    NONE("none", "", 0),
    GZIP("gzip", ".gz", 9),
    XZ("xz", ".xz", 9),
    ZSTD("zstd", ".zst", 19),
    BZIP2("bzip2", ".bz2", 9);

    companion object {
        fun fromStorageKey(key: String?): CompressionCodec = entries.firstOrNull { it.storageKey == key } ?: ZSTD
    }
}

enum class ChecksumAlgorithm(val storageKey: String, val fileExtension: String, val digestLengthBytes: Int) {
    MD5("md5", ".md5", 16),
    SHA1("sha1", ".sha1", 20),
    SHA256("sha256", ".sha256", 32),
    SHA512("sha512", ".sha512", 64),
    BLAKE3("blake3", ".blake3", 32);

    companion object {
        val DEFAULT = SHA256
        fun fromStorageKey(key: String?): ChecksumAlgorithm = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

enum class EncryptionMode(val storageKey: String) {
    NONE("none"),
    AES_256_GCM("aes_256_gcm"),
    ZIP_AES("zip_aes"),
    OPENSSL("openssl"),
    GPG("gpg");

    companion object {
        fun fromStorageKey(key: String?): EncryptionMode = entries.firstOrNull { it.storageKey == key } ?: NONE
    }
}

/** How backed-up files are laid out at the destination. */
enum class StructureMode(val storageKey: String) {
    PRESERVE_HIERARCHY("preserve_hierarchy"),
    CATEGORIZED("categorized");

    companion object {
        fun fromStorageKey(key: String?): StructureMode = entries.firstOrNull { it.storageKey == key } ?: PRESERVE_HIERARCHY
    }
}

/** Where compression/encryption happens relative to the transfer. */
enum class TransferMode(val storageKey: String) {
    /** Build the artifact in local temp storage, then upload. */
    LOCAL_STAGING("local_staging"),
    /** tar/dd → compressor → cipher → destination stream, no large temp files. */
    DIRECT_STREAM("direct_stream"),
    /** Stream raw over SSH and compress on the remote host. */
    REMOTE_COMPRESS("remote_compress");

    companion object {
        fun fromStorageKey(key: String?): TransferMode = entries.firstOrNull { it.storageKey == key } ?: LOCAL_STAGING
    }
}

/** Kind of remote or local endpoint a backup is written to. */
enum class DestinationType(val storageKey: String, val isNetwork: Boolean, val isCloud: Boolean) {
    LOCAL_INTERNAL("local_internal", isNetwork = false, isCloud = false),
    LOCAL_SAF("local_saf", isNetwork = false, isCloud = false),
    USB_OTG("usb_otg", isNetwork = false, isCloud = false),
    FTP("ftp", isNetwork = true, isCloud = false),
    FTPS("ftps", isNetwork = true, isCloud = false),
    SFTP("sftp", isNetwork = true, isCloud = false),
    SSH("ssh", isNetwork = true, isCloud = false),
    SMB("smb", isNetwork = true, isCloud = false),
    WEBDAV("webdav", isNetwork = true, isCloud = false),
    NFS("nfs", isNetwork = true, isCloud = false),
    /** Any provider reachable through a bundled rclone remote. */
    RCLONE("rclone", isNetwork = true, isCloud = true),
    S3("s3", isNetwork = true, isCloud = true),
    MEGA("mega", isNetwork = true, isCloud = true),
    PCLOUD("pcloud", isNetwork = true, isCloud = true);

    companion object {
        fun fromStorageKey(key: String?): DestinationType? = entries.firstOrNull { it.storageKey == key }
    }
}

/** Terminal/active state of a recorded job. */
enum class JobStatus(val storageKey: String, val isTerminal: Boolean) {
    RUNNING("running", isTerminal = false),
    INTERRUPTED("interrupted", isTerminal = false),
    COMPLETED("completed", isTerminal = true),
    COMPLETED_WITH_WARNINGS("completed_with_warnings", isTerminal = true),
    FAILED("failed", isTerminal = true),
    CANCELLED("cancelled", isTerminal = true);

    companion object {
        fun fromStorageKey(key: String?): JobStatus = entries.firstOrNull { it.storageKey == key } ?: FAILED
    }
}

/** What a job does. Mirrors ca.devdcs.backup.core.progress.JobKind but stays Android-free. */
enum class JobType(val storageKey: String) {
    BACKUP("backup"),
    RESTORE("restore"),
    VERIFY("verify"),
    SYNC("sync");

    companion object {
        fun fromStorageKey(key: String?): JobType = entries.firstOrNull { it.storageKey == key } ?: BACKUP
    }
}
