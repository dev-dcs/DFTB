package ca.devdcs.backup.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes a shared pipeline enum (PipelineEnums.kt) by its `storageKey` instead of its Kotlin constant name, so a
 * constant can be renamed in code without invalidating JSON already persisted in Room columns.
 * Unknown keys resolve through each enum's own `fromStorageKey` fallback rather than failing the
 * whole row, which keeps profiles written by a newer build readable by an older one.
 */
abstract class StorageKeyEnumSerializer<E : Enum<E>>(
    serialName: String,
    private val keyOf: (E) -> String,
    private val parse: (String) -> E,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(keyOf(value))

    override fun deserialize(decoder: Decoder): E = parse(decoder.decodeString())
}

object ArchiveFormatSerializer : StorageKeyEnumSerializer<ArchiveFormat>(
    "ArchiveFormat", ArchiveFormat::storageKey, ArchiveFormat::fromStorageKey,
)

object CompressionCodecSerializer : StorageKeyEnumSerializer<CompressionCodec>(
    "CompressionCodec", CompressionCodec::storageKey, CompressionCodec::fromStorageKey,
)

object ChecksumAlgorithmSerializer : StorageKeyEnumSerializer<ChecksumAlgorithm>(
    "ChecksumAlgorithm", ChecksumAlgorithm::storageKey, ChecksumAlgorithm::fromStorageKey,
)

object EncryptionModeSerializer : StorageKeyEnumSerializer<EncryptionMode>(
    "EncryptionMode", EncryptionMode::storageKey, EncryptionMode::fromStorageKey,
)

object StructureModeSerializer : StorageKeyEnumSerializer<StructureMode>(
    "StructureMode", StructureMode::storageKey, StructureMode::fromStorageKey,
)

object TransferModeSerializer : StorageKeyEnumSerializer<TransferMode>(
    "TransferMode", TransferMode::storageKey, TransferMode::fromStorageKey,
)
