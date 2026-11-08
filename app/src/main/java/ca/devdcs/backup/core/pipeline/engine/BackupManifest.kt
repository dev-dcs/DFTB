@file:UseSerializers(
    ArchiveFormatSerializer::class,
    CompressionCodecSerializer::class,
    ChecksumAlgorithmSerializer::class,
)

package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.EncryptionHeader
import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ArchiveFormatSerializer
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.ChecksumAlgorithmSerializer
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.CompressionCodecSerializer
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.TargetSelection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json

/**
 * `dftb-manifest.json`: the self-describing index of one backup folder. Exists so a restore (or a
 * human with `sha256sum`) can find, verify and decrypt every artifact without DFTB's database —
 * the database may be gone when a restore is needed most. Never contains secrets: the profile
 * snapshot drops the vault reference and the encryption header holds only public parameters.
 */
@Serializable
data class BackupManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val generator: String = GENERATOR,
    val jobId: String,
    val createdAtMillis: Long,
    val finishedAtMillis: Long,
    val app: ManifestApp,
    val device: ManifestDevice,
    val profile: ManifestProfile,
    val checksumAlgorithm: ChecksumAlgorithm,
    val artifacts: List<ManifestArtifact>,
    /** Filename-masking parameters (scheme, salt, KDF cost) when names are masked; never a key. */
    val masking: Map<String, String>? = null,
    val warnings: List<String> = emptyList(),
    /** True for an index rewritten during the run (or left by an interrupted run). */
    val partial: Boolean = false,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val GENERATOR = "DFTB"

        /** Stable, human-readable encoding (pretty, defaults included, unknown keys tolerated). */
        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun encode(manifest: BackupManifest): String = json.encodeToString(serializer(), manifest)

        fun decode(text: String): BackupManifest = json.decodeFromString(serializer(), text)
    }
}

/** Which DFTB build wrote the backup (restore may refuse newer schema versions). */
@Serializable
data class ManifestApp(val packageName: String, val versionName: String, val versionCode: Long)

/** Source device identity, so a restore can warn before flashing images onto another device or slot. */
@Serializable
data class ManifestDevice(
    val manufacturer: String,
    val model: String,
    val device: String,
    val fingerprint: String,
    val sdkInt: Int,
    /** `_a`/`_b` on A/B devices, null when not slotted or unknown. */
    val slotSuffix: String? = null,
)

/** The profile as it was when the job ran, minus anything that points at a secret. */
@Serializable
data class ManifestProfile(
    val id: String,
    val name: String,
    val description: String,
    val targets: TargetSelection,
    val destinationId: String?,
    val destinationType: String?,
    val pipeline: PipelineOptions,
    val hadEncryptionKeyRef: Boolean,
) {
    companion object {
        fun of(profile: BackupProfile, destinationType: DestinationType?): ManifestProfile = ManifestProfile(
            id = profile.id,
            name = profile.name,
            description = profile.description,
            targets = profile.targets,
            destinationId = profile.destinationId,
            destinationType = destinationType?.storageKey,
            pipeline = profile.pipeline.copy(encryptionKeyRef = null),
            hadEncryptionKeyRef = profile.pipeline.encryptionKeyRef != null,
        )
    }
}

/** Public parameters of an encrypted stream (never key material). */
@Serializable
data class ManifestEncryption(val scheme: String, val params: Map<String, String>) {
    companion object {
        fun of(header: EncryptionHeader): ManifestEncryption = ManifestEncryption(header.scheme, header.params)
    }
}

/** One artifact of the job folder: how it was produced, where it is and how to check it. */
@Serializable
data class ManifestArtifact(
    val sourceId: String,
    val kind: String,
    /** Unmasked name; omitted when masked and the manifest itself is not encrypted. */
    val name: String?,
    /** Name at the destination, relative to the job folder. */
    val storedName: String,
    val masked: Boolean,
    val layout: String,
    val format: ArchiveFormat,
    val codec: CompressionCodec,
    val compressionLevel: Int,
    /** Stored bytes (FILE_TREE: sum of stored files). */
    val sizeBytes: Long,
    val sourceBytes: Long,
    /** Digests of the stored bytes keyed by algorithm storage key (empty for FILE_TREE). */
    val digests: Map<String, String>,
    val encryption: ManifestEncryption? = null,
    /** Sidecar path relative to the job folder. */
    val checksumFile: String,
    val verified: Boolean? = null,
    /** FILE_TREE only: destination-relative folder that items' storedPath is under; "" = the destination root (TWRP). */
    val treeRoot: String? = null,
    val items: List<ManifestItem>,
)

/** One source item inside an artifact, with what a restore needs to recreate it exactly. */
@Serializable
data class ManifestItem(
    val archivePath: String,
    val type: ItemType,
    val sizeBytes: Long? = null,
    val linkTarget: String? = null,
    val metadata: ManifestMetadata? = null,
    /** Digest of the item's source bytes (what a restore must reproduce). */
    val digests: Map<String, String>? = null,
    /** FILE_TREE only: stored path relative to the artifact folder, plus its stored digests. */
    val storedPath: String? = null,
    val storedBytes: Long? = null,
    val storedDigests: Map<String, String>? = null,
    val encryption: ManifestEncryption? = null,
) {
    @Serializable
    enum class ItemType {
        @SerialName("file") FILE,
        @SerialName("directory") DIRECTORY,
        @SerialName("symlink") SYMLINK,
        @SerialName("stream") STREAM,
    }
}

/** Serializable mirror of EntryMetadata (ownership, mode, SELinux label, mtime). */
@Serializable
data class ManifestMetadata(
    val modifiedAtMillis: Long? = null,
    val mode: Int? = null,
    val uid: Int? = null,
    val gid: Int? = null,
    val seLinuxContext: String? = null,
) {
    companion object {
        /** Null when nothing is known, so the JSON stays compact for unprivileged sources. */
        fun of(metadata: EntryMetadata): ManifestMetadata? =
            if (metadata == EntryMetadata()) {
                null
            } else {
                ManifestMetadata(metadata.modifiedAtMillis, metadata.mode, metadata.uid, metadata.gid, metadata.seLinuxContext)
            }
    }
}

/** Pure helpers shared by the engine and tests. */
object ManifestText {

    fun digestMap(hex: Map<ChecksumAlgorithm, String>): Map<String, String> =
        hex.entries.sortedBy { it.key.ordinal }.associate { it.key.storageKey to it.value }

    fun itemType(item: SourceItem): ManifestItem.ItemType = when (item) {
        is SourceItem.Directory -> ManifestItem.ItemType.DIRECTORY
        is SourceItem.File -> ManifestItem.ItemType.FILE
        is SourceItem.Symlink -> ManifestItem.ItemType.SYMLINK
        is SourceItem.Stream -> ManifestItem.ItemType.STREAM
    }

    /** Declared size (File exact, Stream expected) for the manifest. */
    fun declaredSize(item: SourceItem): Long? = when (item) {
        is SourceItem.File -> item.sizeBytes
        is SourceItem.Stream -> item.expectedSizeBytes
        else -> null
    }
}
