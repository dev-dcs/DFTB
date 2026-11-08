package ca.devdcs.backup.core.restore.engine

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.pipeline.checksum.DefaultChecksumEngine
import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.engine.FakeCheckpoints
import ca.devdcs.backup.core.pipeline.engine.FakeDeviceInfo
import ca.devdcs.backup.core.pipeline.engine.FakeHistory
import ca.devdcs.backup.core.pipeline.engine.FixedClock
import ca.devdcs.backup.core.pipeline.engine.JobServiceLauncher
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.core.restore.OpenedBackup
import ca.devdcs.backup.core.restore.RestoreUnitResult
import ca.devdcs.backup.core.restore.catalog.DefaultBackupCatalog
import ca.devdcs.backup.core.restore.catalog.RestoreFixtures
import ca.devdcs.backup.core.restore.catalog.TreeRegistry
import ca.devdcs.backup.core.restore.catalog.TreeStore
import ca.devdcs.backup.core.restore.catalog.XorRestoreCrypto
import ca.devdcs.backup.core.restore.files.ExtractIssue
import ca.devdcs.backup.core.restore.files.FileRestoreError
import ca.devdcs.backup.core.restore.files.FileRestoreSpec
import ca.devdcs.backup.core.restore.files.FileRestoreTarget
import ca.devdcs.backup.core.restore.files.FileRestorer
import ca.devdcs.backup.core.restore.files.NioTargetFileSystem
import ca.devdcs.backup.core.restore.files.OpenedTarget
import ca.devdcs.backup.core.restore.files.RestoreTargetOpener
import ca.devdcs.backup.core.restore.files.TargetLocation
import ca.devdcs.backup.core.restore.files.TargetUnavailableReason
import ca.devdcs.backup.core.storage.FakeDestinationRepository
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RestoreEngineTest {

    private val io = Dispatchers.Unconfined
    private val store = TreeStore()
    private val archives = RestoreFixtures.archiveFactory(io)
    private val checksums = DefaultChecksumEngine(io)
    private val catalog = DefaultBackupCatalog(TreeRegistry(store), XorRestoreCrypto(), archives, archives, checksums, FixedClock(), io)
    private val sandbox: File = Files.createTempDirectory("dftb-restore-engine").toFile()
    private val destinations = FakeDestinationRepository()
    private val checkpoints = FakeCheckpoints()
    private val history = FakeHistory()
    private val bus = JobProgressBus()
    private val jobDir = "Phone_20261019-080509"

    private val opener = object : RestoreTargetOpener {
        override suspend fun open(location: TargetLocation): OpenedTarget = when (location) {
            is TargetLocation.AbsolutePath -> OpenedTarget.Ready(NioTargetFileSystem(File(location.path).apply { mkdirs() }, io), ElevationTier.NONE)
            is TargetLocation.Tree -> OpenedTarget.Unavailable(TargetUnavailableReason.SAF_PERMISSION_MISSING, location.treeUri)
        }

        override fun primaryStorageRoot(): String = File(sandbox, "sdcard").path
    }

    private class Messages : RestoreMessages {
        override fun opening() = "opening"
        override fun preflight() = "preflight"
        override fun verifying() = "verifying"
        override fun restoring(name: String) = "restoring $name"
        override fun completed(unitCount: Int) = "completed $unitCount"
        override fun completedWithWarnings(unitCount: Int, warningCount: Int) = "completed $unitCount with $warningCount"
        override fun cancelled() = "cancelled"
        override fun failure(failure: RestoreFailure) = "failed ${failure.code}"
        override fun fileIssue(issue: ExtractIssue) = "issue ${issue.code} ${issue.path}"
        override fun fileError(error: FileRestoreError, detail: String?) = "error $error"
        override fun moreIssues(count: Int) = "more $count"
        override fun unmapped(count: Int) = "unmapped $count"
        override fun skippedExisting(count: Int) = "kept $count"
        override fun restorerMissing(kind: RestoreUnitKind) = "missing $kind"
        override fun unitBlocked(name: String, reason: String) = "blocked $name: $reason"
        override fun spaceUnknown(name: String) = "space unknown $name"
        override fun spaceOverridden(name: String, shortfallBytes: Long) = "space overridden $name"
        override fun crossDevice(backupModel: String, thisModel: String) = "cross $backupModel $thisModel"
        override fun resumeSkipped(name: String) = "skipped $name"
        override fun checkpointFailed() = "checkpoint failed"
        override fun partialBackup() = "partial backup"
    }

    private fun engine(app: AppRestorer? = null) = RestoreEngine(
        RestoreDeps(
            catalog = catalog,
            fileRestorer = FileRestorer(catalog, catalog, archives, checksums, opener, io),
            appRestorer = app,
            partitionRestorer = null,
            destinationRepository = destinations,
            checkpointRepository = checkpoints,
            historyRepository = history,
            progressBus = bus,
            serviceLauncher = JobServiceLauncher { },
            deviceInfo = FakeDeviceInfo(),
            clock = FixedClock(),
            messages = Messages(),
            io = io,
        ),
    )

    private fun backupWith(contents: Map<String, String>, corrupt: Boolean = false, profilePaths: List<String> = listOf("/storage/emulated/0/DCIM")): ManifestArtifact {
        val tar = RestoreFixtures.tar(archives, contents.map { RestoreFixtures.file(it.key, it.value) })
        val stored = if (corrupt) tar.copyOf().also { it[it.size - 1] = 1 } else tar
        store.put("$jobDir/files_DCIM.tar", stored)
        val artifact = RestoreFixtures.tarArtifact("files:abc", "files_DCIM.tar", tar, contents)
        val app = artifact.copy(sourceId = "apps:123", kind = "app", storedName = "app_com.x.tar", name = "app_com.x.tar", items = emptyList())
        store.put("$jobDir/app_com.x.tar", tar)
        store.put(
            "$jobDir/dftb-manifest.json",
            BackupManifest.encode(RestoreFixtures.manifest(listOf(artifact, app), profile = RestoreFixtures.profile(profilePaths))),
        )
        return artifact
    }

    @Test
    fun restoresFilesToAChosenFolderAndReportsAppsWithoutRestorer() = runTest {
        destinations.save(RestoreFixtures.destination)
        backupWith(mapOf("files/DCIM/a.jpg" to "AAA", "files/DCIM/sub/b.jpg" to "BB"))
        val target = File(sandbox, "chosen")
        val events = engine().restore(
            RestoreRequest(destinationId = "d1", jobDir = jobDir, fileSpec = FileRestoreSpec(FileRestoreTarget.Folder(target.path)), jobId = "r1"),
        ).toList()
        val outcome = (events.last() as RestoreEvent.Finished).outcome
        assertEquals(JobStatus.COMPLETED_WITH_WARNINGS, outcome.status)
        assertEquals("AAA", File(target, "DCIM/a.jpg").readText())
        assertEquals("BB", File(target, "DCIM/sub/b.jpg").readText())
        assertEquals(5L, outcome.bytesWritten)
        val files = outcome.results.first { it.unitId == "files:abc" }
        assertTrue(files.ok)
        val app = outcome.results.first { it.unitId == "apps:123" }
        assertFalse(app.ok)
        assertEquals("missing APP", app.error)
        assertTrue(outcome.warnings.any { it.startsWith("blocked") })

        val record = history.rows["r1"]!!
        assertEquals(JobType.RESTORE, record.type)
        assertEquals(JobStatus.COMPLETED_WITH_WARNINGS, record.status)
        val cp = checkpoints.rows["r1"]!!
        assertEquals(JobType.RESTORE, cp.type)
        assertEquals(listOf("files:abc"), cp.state.completedItems)
        assertFalse(cp.resumeAfterReboot)
        assertTrue(bus.activeJobs.value.isEmpty())
    }

    @Test
    fun restoresToTheOriginalLocationAndRoutesAppsToTheAppRestorer() = runTest {
        destinations.save(RestoreFixtures.destination)
        val original = File(sandbox, "storage/DCIM")
        backupWith(mapOf("files/DCIM/a.jpg" to "AAA"), profilePaths = listOf(original.path))
        val seen = ArrayList<List<ManifestArtifact>>()
        val app = object : AppRestorer {
            override suspend fun preflight(backup: OpenedBackup, artifacts: List<ManifestArtifact>, options: RestoreOptions) =
                UnitPreflight(artifacts.first().sourceId, available = true, requiredTier = ElevationTier.NONE, requiredBytes = 0L)

            override suspend fun restore(backup: OpenedBackup, artifacts: List<ManifestArtifact>, options: RestoreOptions, progress: RestoreProgress): RestoreUnitResult {
                seen += artifacts
                progress.onBytes(10)
                return RestoreUnitResult(artifacts.first().sourceId, ok = true)
            }
        }
        val outcome = engine(app).execute(RestoreRequest(destinationId = "d1", jobDir = jobDir, jobId = "r2"))
        assertEquals(JobStatus.COMPLETED, outcome.status)
        assertEquals("AAA", File(original, "a.jpg").readText())
        assertEquals(listOf("apps:123"), seen.single().map { it.sourceId })
        assertEquals(13L, outcome.bytesWritten)
    }

    @Test
    fun corruptedArtifactFailsTheUnitAndTheJob() = runTest {
        destinations.save(RestoreFixtures.destination)
        backupWith(mapOf("files/DCIM/a.jpg" to "AAA"), corrupt = true)
        val outcome = engine().execute(
            RestoreRequest(
                destinationId = "d1",
                jobDir = jobDir,
                unitIds = setOf("files:abc"),
                fileSpec = FileRestoreSpec(FileRestoreTarget.Folder(File(sandbox, "c").path)),
                jobId = "r3",
            ),
        )
        assertEquals(JobStatus.FAILED, outcome.status)
        assertEquals(RestoreErrorCode.ALL_UNITS_FAILED, outcome.failure?.code)
        assertEquals("error CORRUPT_ARTIFACT", outcome.results.single().error)
        assertEquals(JobStatus.FAILED, history.rows["r3"]!!.status)
    }

    @Test
    fun verifyBeforeRestoreWritesNothingWhenDamaged() = runTest {
        destinations.save(RestoreFixtures.destination)
        backupWith(mapOf("files/DCIM/a.jpg" to "AAA"), corrupt = true)
        val target = File(sandbox, "v")
        val outcome = engine().execute(
            RestoreRequest(
                destinationId = "d1",
                jobDir = jobDir,
                unitIds = setOf("files:abc"),
                fileSpec = FileRestoreSpec(FileRestoreTarget.Folder(target.path)),
                options = RestoreOptions(verifyBeforeRestore = true),
                jobId = "r4",
            ),
        )
        assertEquals(RestoreErrorCode.VERIFY_FAILED, outcome.failure?.code)
        assertFalse(File(target, "DCIM/a.jpg").exists())
    }

    @Test
    fun unknownDestinationFailsCleanly() = runTest {
        val outcome = engine().execute(RestoreRequest(destinationId = "nope", jobDir = jobDir, jobId = "r5"))
        assertEquals(JobStatus.FAILED, outcome.status)
        assertEquals(RestoreErrorCode.DESTINATION_NOT_FOUND, outcome.failure?.code)
    }

    @Test
    fun unitKindRoutesOnTheManifestKind() {
        val base = RestoreFixtures.tarArtifact("partitions:abc", "boot.img", ByteArray(1), emptyMap(), kind = "partition")
        assertEquals(RestoreUnitKind.PARTITION, RestoreUnitKind.of(base))
        assertEquals(RestoreUnitKind.APP, RestoreUnitKind.of(base.copy(sourceId = "apps:1", kind = "app")))
        assertEquals(RestoreUnitKind.FILE_TREE, RestoreUnitKind.of(base.copy(sourceId = "nandroid:1", kind = "nandroid")))
        assertEquals(RestoreUnitKind.FILE_TREE, RestoreUnitKind.of(base.copy(sourceId = "files:1", kind = "files")))
        assertEquals(RestoreUnitKind.PARTITION, RestoreUnitKind.of(base.copy(kind = "unknown-kind")))
    }
}
