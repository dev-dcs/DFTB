package ca.devdcs.backup.data.db.mappers

import ca.devdcs.backup.data.db.entity.DestinationEntity
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType

/**
 * Entity <-> domain for destinations. A row whose `type` key is unknown (written by a newer build)
 * maps to null so one unreadable destination never breaks the whole list.
 */
fun DestinationEntity.toDomainOrNull(): Destination? {
    val resolvedType = DestinationType.fromStorageKey(type) ?: return null
    return Destination(
        id = id,
        name = name,
        type = resolvedType,
        config = config,
        credentialRef = credentialRef,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastVerifiedAt = lastVerifiedAt,
        lastKnownFreeBytes = lastKnownFreeBytes,
        lastKnownTotalBytes = lastKnownTotalBytes,
    )
}

fun Destination.toEntity(): DestinationEntity = DestinationEntity(
    id = id,
    name = name,
    type = type.storageKey,
    config = config,
    credentialRef = credentialRef,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastVerifiedAt = lastVerifiedAt,
    lastKnownFreeBytes = lastKnownFreeBytes,
    lastKnownTotalBytes = lastKnownTotalBytes,
)
