package ca.devdcs.backup.data.db.mappers

import ca.devdcs.backup.data.db.entity.HashRecordEntity
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.HashRecord

/** Entity <-> domain for hash records. */
fun HashRecordEntity.toDomain(): HashRecord = HashRecord(
    id = id,
    backupId = backupId,
    relativePath = relativePath,
    algorithm = ChecksumAlgorithm.fromStorageKey(algorithm),
    hash = hash,
    sizeBytes = sizeBytes,
    modifiedAt = modifiedAt,
)

fun HashRecord.toEntity(): HashRecordEntity = HashRecordEntity(
    id = id,
    backupId = backupId,
    relativePath = relativePath,
    algorithm = algorithm.storageKey,
    hash = hash,
    sizeBytes = sizeBytes,
    modifiedAt = modifiedAt,
)
