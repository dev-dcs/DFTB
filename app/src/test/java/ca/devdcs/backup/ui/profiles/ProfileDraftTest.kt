package ca.devdcs.backup.ui.profiles

import ca.devdcs.backup.core.boot.ResumePlanner
import ca.devdcs.backup.core.discovery.partition.DiscoveredPartition
import ca.devdcs.backup.core.discovery.partition.PartitionCategory
import ca.devdcs.backup.core.discovery.partition.PartitionGroup
import ca.devdcs.backup.core.discovery.partition.PartitionRisk
import ca.devdcs.backup.core.discovery.partition.PartitionSlot
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.pipeline.engine.BackupJob
import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.domain.model.AppTarget
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.CheckpointState
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.FileFilter
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
import ca.devdcs.backup.ui.discovery.FileFilterFormParser
import ca.devdcs.backup.ui.discovery.SizeUnit
import ca.devdcs.backup.ui.discovery.partitionGroupKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ProfileDraftTest {

    private val profile = BackupProfile(
        id = "p1",
        name = "Phone",
        description = "Everything",
        targets = TargetSelection(
            partitions = listOf(PartitionTarget("boot", slotPolicy = SlotPolicy.BOTH, logical = false)),
            apps = listOf(AppTarget("org.app", setOf(AppComponent.APK, AppComponent.OBB))),
            paths = listOf(PathTarget("/storage/emulated/0/DCIM", recursive = false)),
            categories = setOf(FileCategory.DOCUMENTS),
        ),
        destinationId = "dest",
        pipeline = PipelineOptions(
            archiveFormat = ArchiveFormat.TAR_XZ,
            compressionLevel = 6,
            encryptionMode = EncryptionMode.AES_256_GCM,
            encryptionKeyRef = "enckey-1234",
            maskFilenames = true,
            checksumAlgorithm = ChecksumAlgorithm.BLAKE3,
        ),
        schedule = SchedulePolicy(frequency = ScheduleFrequency.INTERVAL, intervalMinutes = 120, hour = 1, minute = 15),
        retention = RetentionPolicy(keepLast = 7, maxAgeDays = null),
        createdAt = 100L,
        updatedAt = 200L,
        lastRunAt = 150L,
        enabled = false,
    )

    @Test
    fun `editing an unchanged profile saves the same profile`() {
        val draft = ProfileDraft.fromProfile(profile, WizardStep.OPTIONS)
        assertFalse(draft.isNew)
        assertEquals(WizardStep.OPTIONS, draft.step)
        assertEquals(KeyChoice.VAULT_KEY, draft.keyChoice)
        assertEquals(SlotPolicy.BOTH, draft.slotPolicy)
        assertEquals("2", draft.intervalText)
        assertEquals(IntervalUnit.HOURS, draft.intervalUnit)
        assertEquals(profile.copy(updatedAt = 999L), draft.toProfile(now = 999L, keyRef = "enckey-1234"))
    }

    @Test
    fun `draft survives a json round trip`() {
        val draft = ProfileDraft.fromProfile(profile).copy(dirty = true, showErrorsFor = setOf(WizardStep.NAME), intervalText = "1")
        assertEquals(draft, ProfileDraft.fromJson(draft.toJson()))
    }

    @Test
    fun `new drafts start from the defaults`() {
        val draft = ProfileDraft.create(
            "id",
            now = 5L,
            defaults = DraftDefaults(ArchiveFormat.ZIP, 7, ChecksumAlgorithm.SHA512, destinationId = "d"),
        )
        assertTrue(draft.isNew)
        assertEquals(WizardStep.NAME, draft.step)
        assertEquals(ArchiveFormat.ZIP, draft.pipeline.archiveFormat)
        assertEquals(7, draft.pipeline.compressionLevel)
        assertEquals(ChecksumAlgorithm.SHA512, draft.pipeline.checksumAlgorithm)
        assertEquals("d", draft.destinationId)
        assertNull(draft.effectiveSchedule())
        assertTrue(draft.effectiveRetention().isUnlimited)
        // A DD_IMAGE default would be substituted for trees; the draft shows the real result.
        val dd = ProfileDraft.create("id", 5L, DraftDefaults(archiveFormat = ArchiveFormat.DD_IMAGE))
        assertEquals(ArchiveFormat.TAR_ZST, dd.pipeline.archiveFormat)
    }

    @Test
    fun `unsupported encryption is flagged instead of silently dropped`() {
        val gpg = profile.copy(pipeline = profile.pipeline.copy(encryptionMode = EncryptionMode.GPG))
        val draft = ProfileDraft.fromProfile(gpg)
        assertEquals("gpg", draft.unsupportedEncryption)
        assertEquals(KeyChoice.NONE, draft.keyChoice)
        assertFalse(draft.pipeline.maskFilenames)
    }

    @Test
    fun `key refs are classified by their vault prefix`() {
        assertEquals(KeyChoice.PASSPHRASE, KeyChoice.ofRef(null))
        assertEquals(KeyChoice.PASSPHRASE, KeyChoice.ofRef(" "))
        assertEquals(KeyChoice.VAULT_KEY, KeyChoice.ofRef("enckey-5f0e"))
        assertEquals(KeyChoice.STORED_SECRET, KeyChoice.ofRef("pass-5f0e"))
    }

    @Test
    fun `saved pipeline follows the key choice and clamps the level`() {
        val draft = ProfileDraft.create("id", 0L, DraftDefaults()).copy(
            targets = TargetSelection(paths = listOf(PathTarget("/x"))),
            pipeline = PipelineOptions(archiveFormat = ArchiveFormat.TAR_GZ, compressionLevel = 19, maskFilenames = true),
            keyChoice = KeyChoice.PASSPHRASE,
        )
        val saved = draft.toProfile(1L, keyRef = "ignored-for-passphrase").pipeline
        assertEquals(EncryptionMode.AES_256_GCM, saved.encryptionMode)
        assertNull(saved.encryptionKeyRef)
        assertTrue(saved.maskFilenames)
        assertEquals(9, saved.compressionLevel)
        assertTrue(ResumePlanner.requiresTypedPassphrase(draft.toProfile(1L, null)))

        val vault = draft.copy(keyChoice = KeyChoice.VAULT_KEY).toProfile(1L, keyRef = "enckey-9").pipeline
        assertEquals("enckey-9", vault.encryptionKeyRef)
        val none = draft.copy(keyChoice = KeyChoice.NONE).toProfile(1L, keyRef = "enckey-9").pipeline
        assertEquals(EncryptionMode.NONE, none.encryptionMode)
        assertNull(none.encryptionKeyRef)
        assertFalse(none.maskFilenames)
    }

    @Test
    fun `schedule and retention text become policies`() {
        val base = ProfileDraft.create("id", 0L, DraftDefaults())
        val interval = base.copy(
            scheduleEnabled = true,
            schedule = SchedulePolicy(frequency = ScheduleFrequency.INTERVAL),
            intervalText = "45",
            intervalUnit = IntervalUnit.MINUTES,
        )
        assertEquals(45, interval.effectiveSchedule()?.intervalMinutes)
        val daily = interval.copy(schedule = SchedulePolicy(frequency = ScheduleFrequency.DAILY, intervalMinutes = 30))
        assertNull(daily.effectiveSchedule()?.intervalMinutes)
        assertEquals(
            RetentionPolicy(keepLast = 4, maxAgeDays = null),
            base.copy(keepLastEnabled = true, keepLastText = " 4 ", maxAgeEnabled = false, maxAgeText = "10").effectiveRetention(),
        )
        assertEquals(RetentionPolicy(), base.copy(keepLastEnabled = true, keepLastText = "abc").effectiveRetention())
    }

    @Test
    fun `partition selection keys match the discovery browser`() {
        val physical = group("boot", PartitionSource.BY_NAME, slotted = true)
        val mapper = group("system", PartitionSource.MAPPER, slotted = true)
        val persist = group("persist", PartitionSource.BY_NAME, slotted = false)
        var targets = TargetSelection.EMPTY
        listOf(physical, mapper, persist).forEach { g ->
            targets = TargetEdits.togglePartition(targets, g, partitionGroupKey(g), SlotPolicy.ACTIVE)
        }
        assertEquals(
            setOf(partitionGroupKey(physical), partitionGroupKey(mapper), partitionGroupKey(persist)),
            targets.partitions.map { it.groupKey() }.toSet(),
        )
        assertEquals(SlotPolicy.NONE, targets.partitions.first { it.name == "persist" }.slotPolicy)

        val inactive = TargetEdits.applySlotPolicy(targets, SlotPolicy.INACTIVE)
        assertEquals(SlotPolicy.INACTIVE, inactive.partitions.first { it.name == "boot" }.slotPolicy)
        assertEquals(SlotPolicy.NONE, inactive.partitions.first { it.name == "persist" }.slotPolicy)

        val removed = TargetEdits.togglePartition(targets, mapper, partitionGroupKey(mapper), SlotPolicy.ACTIVE)
        assertFalse(removed.partitions.any { it.name == "system" })
    }

    @Test
    fun `app edits`() {
        var targets = TargetEdits.toggleApp(TargetSelection.EMPTY, "a.b", setOf(AppComponent.APK))
        targets = TargetEdits.toggleApp(targets, "c.d", setOf(AppComponent.APK, AppComponent.INTERNAL_DATA))
        assertEquals(listOf("a.b", "c.d"), targets.apps.map { it.packageName })
        targets = TargetEdits.setAppComponents(targets, "a.b", setOf(AppComponent.OBB))
        assertEquals(setOf(AppComponent.OBB), targets.apps.first().components)
        targets = TargetEdits.applyComponentsToAll(targets, setOf(AppComponent.EXTERNAL_DATA))
        assertTrue(targets.apps.all { it.components == setOf(AppComponent.EXTERNAL_DATA) })
        targets = TargetEdits.toggleApp(targets, "a.b", emptySet())
        assertEquals(listOf("c.d"), targets.apps.map { it.packageName })
    }

    @Test
    fun `locations normalize trailing slashes`() {
        assertEquals("/sdcard/DCIM", TargetEdits.normalizeLocation(" /sdcard/DCIM/ "))
        assertEquals("/", TargetEdits.normalizeLocation("/"))
    }

    @Test
    fun `filters reopen in the editor and parse back to the same filter`() {
        val zone = ZoneId.of("America/Toronto")
        val day = { d: LocalDate -> d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val after = FileFilterFormParser.modifiedAfterBound(day(LocalDate.of(2026, 3, 8)), zone)
        val before = FileFilterFormParser.modifiedBeforeBound(day(LocalDate.of(2026, 11, 1)), zone)
        val filter = FileFilter(
            includeExtensions = setOf("jpg", "png"),
            excludeExtensions = setOf("tmp"),
            minSizeBytes = 5_000_000L,
            maxSizeBytes = 1_500_000_000L,
            modifiedAfterEpochMillis = after,
            modifiedBeforeEpochMillis = before,
            includeRegex = "^DCIM/",
            excludeRegex = "\\.trashed",
        )
        val form = FilterFormMapper.toForm(filter, zone)
        assertEquals(SizeUnit.MB, form.minUnit)
        assertEquals("5", form.minSize)
        assertEquals(SizeUnit.MB, form.maxUnit)
        assertEquals("1500", form.maxSize)
        assertEquals(day(LocalDate.of(2026, 3, 8)), form.modifiedOnOrAfterDay)
        assertEquals(day(LocalDate.of(2026, 11, 1)), form.modifiedOnOrBeforeDay)
        val parsed = FileFilterFormParser.parse(form, zone)
        assertTrue(parsed.isValid)
        assertEquals(filter, parsed.filter)

        val empty = FilterFormMapper.toForm(null, zone)
        assertTrue(FileFilterFormParser.parse(empty, zone).filter.isEmpty)
        val snapshot = FilterEditorSnapshot.of("/x", form)
        assertEquals(form, snapshot.toForm())
    }

    @Test
    fun `typed passphrase profiles can resume where the planner stops`() {
        val typed = profile.copy(enabled = true, pipeline = profile.pipeline.copy(encryptionKeyRef = null))
        val checkpoint = JobCheckpoint(
            jobId = "job-1",
            profileId = typed.id,
            type = JobType.BACKUP,
            status = JobStatus.INTERRUPTED,
            state = CheckpointState(
                completedItems = listOf("app:org.app"),
                extras = mapOf(BackupJob.EXTRA_JOB_DIR to "2026-10-20_0200", BackupJob.EXTRA_DESTINATION_ID to "other"),
            ),
            createdAt = 1_000L,
            updatedAt = 2_000L,
        )
        val resume = ResumeOptions.resumeFor(typed, listOf(checkpoint), nowMillis = 3_000L, destinationIds = setOf("dest", "other"))
        requireNotNull(resume)
        assertEquals("job-1", resume.jobId)
        assertEquals("other", resume.profile.destinationId)
        assertEquals(setOf("app:org.app"), resume.skipSourceIds)
        assertEquals("2026-10-20_0200", resume.jobDirName)

        // The job's destination was deleted: nothing to continue.
        assertNull(ResumeOptions.resumeFor(typed, listOf(checkpoint), 3_000L, destinationIds = setOf("dest")))
        // Finished jobs are not offered.
        assertNull(ResumeOptions.resumeFor(typed, listOf(checkpoint.copy(status = JobStatus.COMPLETED)), 3_000L, setOf("dest", "other")))
        // Stored keys resume exactly as the planner decides.
        val stored = typed.copy(pipeline = typed.pipeline.copy(encryptionKeyRef = "enckey-1"))
        assertEquals("job-1", ResumeOptions.resumeFor(stored, listOf(checkpoint), 3_000L, setOf("dest", "other"))?.jobId)
    }

    private fun group(base: String, source: PartitionSource, slotted: Boolean): PartitionGroup {
        val slots = if (slotted) listOf(PartitionSlot.A, PartitionSlot.B) else listOf(PartitionSlot.NONE)
        val members = slots.map { slot ->
            DiscoveredPartition(
                name = base + slot.suffix,
                baseName = base,
                slot = slot,
                blockDevice = (if (source == PartitionSource.MAPPER) "/dev/block/mapper/" else "/dev/block/by-name/") + base + slot.suffix,
                realDevice = null,
                sizeBytes = 1024L,
                isLogical = source == PartitionSource.MAPPER,
                category = PartitionCategory.OTHER,
                isActiveSlot = slot != PartitionSlot.B,
                risk = PartitionRisk.SAFE_TO_IMAGE,
                source = source,
            )
        }
        return PartitionGroup(baseName = base, members = members, inStandardSet = false)
    }
}
