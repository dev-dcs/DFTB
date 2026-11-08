package ca.devdcs.backup.data.db.mappers

import ca.devdcs.backup.data.db.entity.HistoryRecordEntity
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType

/**
 * Entity <-> domain for history. Optional enum columns stay null when null; a non-null unknown key
 * falls back through the enum's own default so old builds still render newer rows.
 */
fun HistoryRecordEntity.toDomain(): HistoryRecord = HistoryRecord(
    id = id,
    profileId = profileId,
    destinationId = destinationId,
    type = JobType.fromStorageKey(type),
    status = JobStatus.fromStorageKey(status),
    startedAt = startedAt,
    finishedAt = finishedAt,
    bytesProcessed = bytesProcessed,
    itemCount = itemCount,
    artifactPath = artifactPath,
    artifactSizeBytes = artifactSizeBytes,
    archiveFormat = archiveFormat?.let(ArchiveFormat::fromStorageKey),
    checksumAlgorithm = checksumAlgorithm?.let(ChecksumAlgorithm::fromStorageKey),
    checksumManifestPath = checksumManifestPath,
    errorMessage = errorMessage,
    logExcerpt = logExcerpt,
)

fun HistoryRecord.toEntity(): HistoryRecordEntity = HistoryRecordEntity(
    id = id,
    profileId = profileId,
    destinationId = destinationId,
    type = type.storageKey,
    status = status.storageKey,
    startedAt = startedAt,
    finishedAt = finishedAt,
    bytesProcessed = bytesProcessed,
    itemCount = itemCount,
    artifactPath = artifactPath,
    artifactSizeBytes = artifactSizeBytes,
    archiveFormat = archiveFormat?.storageKey,
    checksumAlgorithm = checksumAlgorithm?.storageKey,
    checksumManifestPath = checksumManifestPath,
    errorMessage = errorMessage,
    logExcerpt = logExcerpt,
)
