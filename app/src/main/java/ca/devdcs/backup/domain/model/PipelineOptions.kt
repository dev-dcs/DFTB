@file:UseSerializers(
    ArchiveFormatSerializer::class,
    CompressionCodecSerializer::class,
    ChecksumAlgorithmSerializer::class,
    EncryptionModeSerializer::class,
    StructureModeSerializer::class,
    TransferModeSerializer::class,
)

package ca.devdcs.backup.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Everything the engines need to know about *how* to produce and ship an artifact, independent of
 * *what* is backed up ([TargetSelection]) and *where* it goes ([Destination]). Persisted as one
 * JSON column on the profile so adding an option never needs a schema migration.
 *
 * [encryptionKeyRef] is a handle into the credential vault, never the key or passphrase itself.
 */
@Serializable
data class PipelineOptions(
    val archiveFormat: ArchiveFormat = ArchiveFormat.DEFAULT,
    val compressionCodec: CompressionCodec = CompressionCodec.ZSTD,
    val compressionLevel: Int = DEFAULT_COMPRESSION_LEVEL,
    val encryptionMode: EncryptionMode = EncryptionMode.NONE,
    val encryptionKeyRef: String? = null,
    val maskFilenames: Boolean = false,
    val preservePosixMetadata: Boolean = true,
    val preserveSelinuxContext: Boolean = true,
    val structureMode: StructureMode = StructureMode.PRESERVE_HIERARCHY,
    val transferMode: TransferMode = TransferMode.LOCAL_STAGING,
    val checksumAlgorithm: ChecksumAlgorithm = ChecksumAlgorithm.DEFAULT,
    val verifyAfterBackup: Boolean = true,
    val twrpCompatible: Boolean = false,
) {
    /** True when the artifact must be encrypted and therefore needs a vault entry at run time. */
    val isEncrypted: Boolean get() = encryptionMode != EncryptionMode.NONE

    /**
     * [compressionLevel] clamped to what the selected format (or codec, for non-archive formats)
     * actually accepts, so a profile edited for zstd (0..19) still works after switching to gzip.
     */
    val effectiveCompressionLevel: Int
        get() {
            val max = when {
                archiveFormat.isArchive -> archiveFormat.maxCompressionLevel
                else -> compressionCodec.maxLevel
            }
            return compressionLevel.coerceIn(0, max)
        }

    companion object {
        const val DEFAULT_COMPRESSION_LEVEL = 3
        val DEFAULT = PipelineOptions()
    }
}
