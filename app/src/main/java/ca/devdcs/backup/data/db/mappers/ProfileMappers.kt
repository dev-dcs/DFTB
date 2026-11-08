package ca.devdcs.backup.data.db.mappers

import ca.devdcs.backup.data.db.entity.BackupProfileEntity
import ca.devdcs.backup.domain.model.BackupProfile

/** Entity <-> domain for backup profiles. Field-for-field; nested objects are shared types. */
fun BackupProfileEntity.toDomain(): BackupProfile = BackupProfile(
    id = id,
    name = name,
    description = description,
    targets = targets,
    destinationId = destinationId,
    pipeline = pipeline,
    schedule = schedule,
    retention = retention,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRunAt = lastRunAt,
    enabled = enabled,
)

fun BackupProfile.toEntity(): BackupProfileEntity = BackupProfileEntity(
    id = id,
    name = name,
    description = description,
    targets = targets,
    destinationId = destinationId,
    pipeline = pipeline,
    schedule = schedule,
    retention = retention,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRunAt = lastRunAt,
    enabled = enabled,
)
