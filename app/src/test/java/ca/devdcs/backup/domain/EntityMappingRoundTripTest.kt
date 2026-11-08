package ca.devdcs.backup.domain

import ca.devdcs.backup.data.db.converters.DftbTypeConverters
import ca.devdcs.backup.data.db.entity.DestinationEntity
import ca.devdcs.backup.data.db.entity.HistoryRecordEntity
import ca.devdcs.backup.data.db.mappers.toDomain
import ca.devdcs.backup.data.db.mappers.toDomainOrNull
import ca.devdcs.backup.data.db.mappers.toEntity
import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.domain.model.AppTarget
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.CheckpointState
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationConfig
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.HashRecord
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.domain.model.PartitionTarget
import ca.devdcs.backup.domain.model.PathTarget
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.RetentionPolicy
import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SchedulePolicy
import ca.devdcs.backup.domain.model.SlotPolicy
import ca.devdcs.backup.domain.model.TargetSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class EntityMappingRoundTripTest {

    private val converters = DftbTypeConverters()

    private val profile = BackupProfile(
        id = UUID.randomUUID().toString(),
        name = "Nightly",
        description = "Photos and chat apps",
        targets = TargetSelection(
            partitions = listOf(PartitionTarget("boot", "/dev/block/by-name/boot_a", SlotPolicy.ACTIVE)),
            apps = listOf(AppTarget("org.example", setOf(AppComponent.APK, AppComponent.INTERNAL_DATA))),
            paths = listOf(PathTarget("/sdcard/DCIM")),
            categories = setOf(FileCategory.IMAGES),
        ),
        destinationId = UUID.randomUUID().toString(),
        pipeline = PipelineOptions(archiveFormat = ArchiveFormat.TAR_XZ, encryptionMode = EncryptionMode.AES_256_GCM, encryptionKeyRef = "vault:1"),
        schedule = SchedulePolicy(frequency = ScheduleFrequency.WEEKLY, hour = 3, minute = 30, daysOfWeek = setOf(1, 7)),
        retention = RetentionPolicy(keepLast = 5, maxAgeDays = 90),
        createdAt = 1_000L,
        updatedAt = 2_000L,
        lastRunAt = 3_000L,
        enabled = false,
    )

    @Test
    fun `profile round trips through entity`() {
        assertEquals(profile, profile.toEntity().toDomain())
        val withoutSchedule = profile.copy(schedule = null, destinationId = null, lastRunAt = null)
        assertEquals(withoutSchedule, withoutSchedule.toEntity().toDomain())
    }

    @Test
    fun `profile nested columns round trip through the type converters`() {
        val entity = profile.toEntity()
        assertEquals(entity.targets, converters.jsonToTargetSelection(converters.targetSelectionToJson(entity.targets)))
        assertEquals(entity.pipeline, converters.jsonToPipelineOptions(converters.pipelineOptionsToJson(entity.pipeline)))
        assertEquals(entity.schedule, converters.jsonToSchedulePolicy(converters.schedulePolicyToJson(entity.schedule)))
        assertNull(converters.schedulePolicyToJson(null))
        assertNull(converters.jsonToSchedulePolicy(null))
        assertEquals(entity.retention, converters.jsonToRetentionPolicy(converters.retentionPolicyToJson(entity.retention)))
    }

    @Test
    fun `destination round trips and stores type by storageKey`() {
        val destination = Destination(
            id = UUID.randomUUID().toString(),
            name = "NAS",
            type = DestinationType.SFTP,
            config = DestinationConfig(host = "nas.local", port = 22, basePath = "/backups", username = "me", options = mapOf("hostKey" to "sha256:abc")),
            credentialRef = "vault:sftp:nas",
            createdAt = 1L,
            updatedAt = 2L,
            lastVerifiedAt = 3L,
            lastKnownFreeBytes = 4L,
            lastKnownTotalBytes = 10L,
        )
        val entity = destination.toEntity()
        assertEquals("sftp", entity.type)
        assertEquals(destination, entity.toDomainOrNull())
        assertEquals(entity.config, converters.jsonToDestinationConfig(converters.destinationConfigToJson(entity.config)))
        assertEquals(0.6f, destination.usedFraction!!, 0.0001f)
    }

    @Test
    fun `destination with unknown type maps to null instead of throwing`() {
        val entity = DestinationEntity(
            id = "x", name = "future", type = "teleport", config = DestinationConfig.EMPTY,
            createdAt = 0L, updatedAt = 0L,
        )
        assertNull(entity.toDomainOrNull())
    }

    @Test
    fun `history record round trips including nullable enums`() {
        val full = HistoryRecord(
            id = UUID.randomUUID().toString(),
            profileId = profile.id,
            destinationId = profile.destinationId,
            type = JobType.BACKUP,
            status = JobStatus.COMPLETED_WITH_WARNINGS,
            startedAt = 10L,
            finishedAt = 20L,
            bytesProcessed = 123_456L,
            itemCount = 42,
            artifactPath = "/backups/nightly.tar.zst",
            artifactSizeBytes = 100_000L,
            archiveFormat = ArchiveFormat.TAR_ZST,
            checksumAlgorithm = ChecksumAlgorithm.SHA512,
            checksumManifestPath = "/backups/nightly.sha512",
            errorMessage = "2 files skipped",
            logExcerpt = "...tail...",
        )
        val entity = full.toEntity()
        assertEquals("completed_with_warnings", entity.status)
        assertEquals("tar_zst", entity.archiveFormat)
        assertEquals(full, entity.toDomain())
        assertEquals(10L, full.durationMillis)

        val minimal = HistoryRecord(id = "m", type = JobType.VERIFY, status = JobStatus.RUNNING, startedAt = 1L)
        val minimalEntity = minimal.toEntity()
        assertNull(minimalEntity.archiveFormat)
        assertNull(minimalEntity.checksumAlgorithm)
        assertEquals(minimal, minimalEntity.toDomain())
    }

    @Test
    fun `history record with unknown keys falls back to enum defaults`() {
        val entity = HistoryRecordEntity(id = "h", type = "teleport", status = "limbo", startedAt = 0L, archiveFormat = "future")
        val domain = entity.toDomain()
        assertEquals(JobType.BACKUP, domain.type)
        assertEquals(JobStatus.FAILED, domain.status)
        assertEquals(ArchiveFormat.DEFAULT, domain.archiveFormat)
    }

    @Test
    fun `checkpoint round trips with state`() {
        val checkpoint = JobCheckpoint(
            jobId = UUID.randomUUID().toString(),
            profileId = profile.id,
            type = JobType.RESTORE,
            status = JobStatus.INTERRUPTED,
            attempt = 3,
            state = CheckpointState(
                completedItems = listOf("a.jpg", "b.jpg"),
                currentItem = "c.jpg",
                currentOffsetBytes = 4096L,
                partIndex = 2,
                extras = mapOf("uploadId" to "u-1"),
            ),
            createdAt = 1L,
            updatedAt = 2L,
            resumeAfterReboot = false,
        )
        val entity = checkpoint.toEntity()
        assertEquals("interrupted", entity.status)
        assertEquals(checkpoint, entity.toDomain())
        assertEquals(entity.state, converters.jsonToCheckpointState(converters.checkpointStateToJson(entity.state)))
    }

    @Test
    fun `hash record round trips`() {
        val record = HashRecord(
            id = 77L,
            backupId = "b",
            relativePath = "DCIM/1.jpg",
            algorithm = ChecksumAlgorithm.BLAKE3,
            hash = "deadbeef",
            sizeBytes = 1L,
            modifiedAt = 2L,
        )
        val entity = record.toEntity()
        assertEquals("blake3", entity.algorithm)
        assertEquals(record, entity.toDomain())
    }
}
