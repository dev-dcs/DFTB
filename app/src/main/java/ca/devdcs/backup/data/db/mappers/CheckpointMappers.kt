package ca.devdcs.backup.data.db.mappers

import ca.devdcs.backup.data.db.entity.JobCheckpointEntity
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType

/** Entity <-> domain for job checkpoints. */
fun JobCheckpointEntity.toDomain(): JobCheckpoint = JobCheckpoint(
    jobId = jobId,
    profileId = profileId,
    type = JobType.fromStorageKey(type),
    status = JobStatus.fromStorageKey(status),
    attempt = attempt,
    state = state,
    createdAt = createdAt,
    updatedAt = updatedAt,
    resumeAfterReboot = resumeAfterReboot,
)

fun JobCheckpoint.toEntity(): JobCheckpointEntity = JobCheckpointEntity(
    jobId = jobId,
    profileId = profileId,
    type = type.storageKey,
    status = status.storageKey,
    attempt = attempt,
    state = state,
    createdAt = createdAt,
    updatedAt = updatedAt,
    resumeAfterReboot = resumeAfterReboot,
)
