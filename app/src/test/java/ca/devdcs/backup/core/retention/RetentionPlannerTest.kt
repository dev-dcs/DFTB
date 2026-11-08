package ca.devdcs.backup.core.retention

import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.engine.ManifestApp
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.ManifestDevice
import ca.devdcs.backup.core.pipeline.engine.ManifestItem
import ca.devdcs.backup.core.pipeline.engine.ManifestProfile
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.domain.model.RetentionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPlannerTest {

    private val day = RetentionPolicy.MILLIS_PER_DAY
    private val now = 100 * day

    private fun rec(id: String, ageDays: Int, dest: String? = "d1", status: JobStatus = JobStatus.COMPLETED, dir: String? = "job-$id") = HistoryRecord(
        id = id,
        profileId = "p1",
        destinationId = dest,
        type = JobType.BACKUP,
        status = status,
        startedAt = now - ageDays * day,
        finishedAt = now - ageDays * day + 1,
        artifactPath = dir,
    )

    /** What HistoryRepository.expiredRecords returns for keepLast (newest first order of input). */
    private fun beyondKeep(records: List<HistoryRecord>, keep: Int) = records.sortedByDescending { it.startedAt }.drop(keep)

    @Test
    fun unlimitedPolicyPlansNothing() {
        val all = listOf(rec("a", 1), rec("b", 2))
        val plan = RetentionPlanner.plan("p1", RetentionPolicy.KEEP_ALL, now, all, all, emptyList(), emptySet())
        assertTrue(plan.isEmpty)
    }

    @Test
    fun keepLastDeletesOlderTrackedJobs() {
        val all = listOf(rec("a", 1), rec("b", 2), rec("c", 3), rec("d", 4))
        val plan = RetentionPlanner.plan("p1", RetentionPolicy(keepLast = 2), now, all, beyondKeep(all, 2), emptyList(), emptySet())
        assertEquals(listOf("job-d", "job-c"), plan.deletions.map { it.jobDir })
        assertEquals(listOf(listOf("d"), listOf("c")), plan.deletions.map { it.historyIds })
        assertTrue(plan.deletions.all { it.reason == RetentionReason.BEYOND_KEEP_LAST && it.destinationId == "d1" })
    }

    @Test
    fun newestSuccessfulIsNeverDeletedEvenWhenTooOld() {
        val all = listOf(rec("a", 40), rec("b", 50))
        // maxAge 30 days: the repository reports both as expired.
        val plan = RetentionPlanner.plan("p1", RetentionPolicy(maxAgeDays = 30), now, all, all, emptyList(), emptySet())
        assertEquals(listOf("job-b"), plan.deletions.map { it.jobDir })
        assertEquals(RetentionReason.OLDER_THAN_MAX_AGE, plan.deletions.single().reason)
        assertEquals(RetentionKept("d1", "job-a", listOf("a"), RetentionHold.NEWEST_SUCCESSFUL), plan.kept.single())
    }

    @Test
    fun activeJobFolderIsHeld() {
        val all = listOf(rec("a", 1), rec("b", 2), rec("c", 3))
        val plan = RetentionPlanner.plan("p1", RetentionPolicy(keepLast = 1), now, all, beyondKeep(all, 1), emptyList(), setOf("d1" to "job-c"))
        assertEquals(listOf("job-b"), plan.deletions.map { it.jobDir })
        assertEquals(RetentionHold.ACTIVE_JOB, plan.kept.single().hold)
    }

    @Test
    fun unsafeOrTwrpPathsAreHeld() {
        val all = listOf(rec("a", 1), rec("b", 2, dir = "../escape"), rec("c", 3, dir = "TWRP/BACKUPS/x/y"), rec("e", 4, dir = ""))
        val plan = RetentionPlanner.plan("p1", RetentionPolicy(keepLast = 1), now, all, beyondKeep(all, 1), emptyList(), emptySet())
        assertTrue(plan.deletions.isEmpty())
        assertTrue(plan.kept.all { it.hold == RetentionHold.UNSAFE_PATH })
        assertEquals(3, plan.kept.size)
    }

    @Test
    fun recordsWithoutDestinationOnlyDropRows() {
        val all = listOf(rec("a", 1), rec("b", 2, dest = null))
        val plan = RetentionPlanner.plan("p1", RetentionPolicy(keepLast = 1), now, all, beyondKeep(all, 1), emptyList(), emptySet())
        val only = plan.deletions.single()
        assertNull(only.jobDir)
        assertEquals(listOf("b"), only.historyIds)
    }

    @Test
    fun untrackedBackupsRankWithTrackedOnes() {
        val tracked = listOf(rec("a", 1), rec("b", 5))
        val untracked = listOf(
            CatalogBackup("d1", "old-1", now - 3 * day),
            CatalogBackup("d1", "old-2", now - 10 * day, twrpFolders = listOf("TWRP/BACKUPS/serial/2026-01-01--00-00-00_x", "bad/path")),
            // Same folder as a tracked record: must not be planned twice.
            CatalogBackup("d1", "job-b", now - 5 * day),
        )
        val plan = RetentionPlanner.plan("p1", RetentionPolicy(keepLast = 2), now, tracked, beyondKeep(tracked, 2), untracked, emptySet())
        // Ranking: a(1d), old-1(3d), b(5d), old-2(10d) -> keep a, old-1.
        assertEquals(listOf("old-2"), plan.deletions.map { it.jobDir })
        assertEquals(listOf("TWRP/BACKUPS/serial/2026-01-01--00-00-00_x"), plan.deletions.single().extraFolders)
        assertTrue(plan.deletions.single().historyIds.isEmpty())
    }

    @Test
    fun newestOverallIsProtectedEvenIfUntracked() {
        val untracked = listOf(CatalogBackup("d1", "only", now - 90 * day))
        val plan = RetentionPlanner.plan("p1", RetentionPolicy(maxAgeDays = 7), now, emptyList(), emptyList(), untracked, emptySet())
        assertTrue(plan.deletions.isEmpty())
        assertEquals(RetentionHold.NEWEST_SUCCESSFUL, plan.kept.single().hold)
    }

    @Test
    fun newestFullyCompletedIsKeptBehindDegradedOnes() {
        val warn = JobStatus.COMPLETED_WITH_WARNINGS
        val all = listOf(rec("w1", 1, status = warn), rec("w2", 2, status = warn), rec("ok", 3), rec("old", 4))
        val plan = RetentionPlanner.plan("p1", RetentionPolicy(keepLast = 1), now, all, beyondKeep(all, 1), emptyList(), emptySet())
        assertEquals(listOf("job-old", "job-w2"), plan.deletions.map { it.jobDir })
        assertEquals(RetentionHold.NEWEST_COMPLETE, plan.kept.single { it.jobDir == "job-ok" }.hold)
    }

    @Test
    fun failedRecordsAreIgnored() {
        val all = listOf(rec("a", 1), rec("f", 2, status = JobStatus.FAILED))
        val plan = RetentionPlanner.plan("p1", RetentionPolicy(keepLast = 1), now, all, listOf(all[1]), emptyList(), emptySet())
        assertTrue(plan.deletions.isEmpty())
    }

    @Test
    fun twrpFolderIsTheFourSegmentBackupFolder() {
        val profile = BackupProfile(id = "p1", name = "TWRP", createdAt = 0L, updatedAt = 0L)
        fun artifact(treeRoot: String?, vararg stored: String) = ManifestArtifact(
            sourceId = "s",
            kind = "nandroid",
            name = "n",
            storedName = "n",
            masked = false,
            layout = "file_tree",
            format = ArchiveFormat.DEFAULT,
            codec = CompressionCodec.NONE,
            compressionLevel = 0,
            sizeBytes = 0L,
            sourceBytes = 0L,
            digests = emptyMap(),
            checksumFile = "n.sha256",
            treeRoot = treeRoot,
            items = stored.map { ManifestItem(archivePath = it, type = ManifestItem.ItemType.FILE, storedPath = it) },
        )
        val manifest = BackupManifest(
            jobId = "j",
            createdAtMillis = 0L,
            finishedAtMillis = 0L,
            app = ManifestApp("ca.devdcs.backup", "0.0.1", 1L),
            device = ManifestDevice("m", "m", "d", "f", 35),
            profile = ManifestProfile.of(profile, null),
            checksumAlgorithm = ChecksumAlgorithm.DEFAULT,
            artifacts = listOf(
                artifact("", "TWRP/BACKUPS/ABC123/2026-10-19--02-00-00_BP2A/boot.emmc.win", "TWRP/BACKUPS/ABC123/2026-10-19--02-00-00_BP2A/boot.emmc.win.sha2"),
                // Non-verbatim tree inside the job folder: not a TWRP folder.
                artifact("job/files", "TWRP/BACKUPS/ABC123/other/x"),
                // Too shallow to be a backup folder.
                artifact("", "TWRP/BACKUPS/ABC123"),
            ),
        )
        assertEquals(listOf("TWRP/BACKUPS/ABC123/2026-10-19--02-00-00_BP2A"), RetentionPlanner.twrpFolders(manifest))
        assertEquals("TWRP/BACKUPS/a/b", RetentionPlanner.safeTwrpFolder("/TWRP/BACKUPS/a/b/"))
        assertNull(RetentionPlanner.safeTwrpFolder("TWRP/BACKUPS/a"))
        assertNull(RetentionPlanner.safeTwrpFolder("TWRP/BACKUPS/a/b/c"))
        assertNull(RetentionPlanner.safeTwrpFolder("other/BACKUPS/a/b"))
    }
}
