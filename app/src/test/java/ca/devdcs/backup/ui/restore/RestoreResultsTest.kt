package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException
import ca.devdcs.backup.core.progress.JobKind
import ca.devdcs.backup.core.progress.JobPhase
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.restore.ArtifactCheck
import ca.devdcs.backup.core.restore.BackupSummary
import ca.devdcs.backup.core.restore.RestoreUnitResult
import ca.devdcs.backup.core.restore.catalog.CatalogException
import ca.devdcs.backup.core.restore.engine.RestoreErrorCode
import ca.devdcs.backup.core.restore.engine.RestoreEvent
import ca.devdcs.backup.core.restore.engine.RestoreFailure
import ca.devdcs.backup.core.restore.engine.RestoreOutcome
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.core.restore.engine.UnitPreflight
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.ui.restore.RestoreTestData.unit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreResultsTest {

    // --- verification problems -----------------------------------------------------------------

    @Test
    fun `catalog codes are decoded with their detail`() {
        assertEquals(
            ArtifactProblem.Catalog(CatalogException.Reason.DIGEST_MISMATCH, "boot.img", 0),
            ArtifactProblemParser.parse("DIGEST_MISMATCH|boot.img"),
        )
        assertEquals(ArtifactProblem.Catalog(CatalogException.Reason.MISSING_FILE, null, 0), ArtifactProblemParser.parse("MISSING_FILE"))
    }

    @Test
    fun `cipher and io problems are recognised`() {
        assertEquals(
            ArtifactProblem.Cipher(CipherIntegrityException.Reason.TAMPERED, "chunk 3", 0),
            ArtifactProblemParser.parse("CIPHER_TAMPERED|chunk 3"),
        )
        assertEquals(ArtifactProblem.Io("Connection reset", 0), ArtifactProblemParser.parse("IO|Connection reset"))
    }

    @Test
    fun `tree problems keep the count of further problems`() {
        assertEquals(
            ArtifactProblem.Catalog(CatalogException.Reason.SIZE_MISMATCH, "10 != 12", 4),
            ArtifactProblemParser.parse("SIZE_MISMATCH|10 != 12 (+4)"),
        )
    }

    @Test
    fun `unknown text is kept verbatim and blanks are no problem`() {
        assertEquals(ArtifactProblem.Unknown("something odd", 0), ArtifactProblemParser.parse("something odd"))
        assertEquals(ArtifactProblem.Unknown("CIPHER_NOPE|x", 0), ArtifactProblemParser.parse("CIPHER_NOPE|x"))
        assertNull(ArtifactProblemParser.parse(null))
        assertNull(ArtifactProblemParser.parse(" "))
    }

    @Test
    fun `verification summary counts failures`() {
        val a = RestoreTestData.files("a", "A", 1)
        val checks = listOf(
            ArtifactCheck(a, ok = true, storedBytes = 1, expected = emptyMap(), actual = emptyMap()),
            ArtifactCheck(a, ok = false, storedBytes = 1, expected = emptyMap(), actual = emptyMap(), problem = "MISSING_FILE"),
        )
        val summary = summarizeChecks(checks)
        assertEquals(2, summary.total)
        assertEquals(1, summary.failed)
        assertFalse(summary.allOk)
        assertFalse(summarizeChecks(emptyList()).allOk)
    }

    // --- preflight -----------------------------------------------------------------------------

    @Test
    fun `space verdict follows the engine's margin rule`() {
        assertEquals(SpaceVerdict.NOT_NEEDED to null, PreflightRules.space(0L, null))
        assertEquals(SpaceVerdict.UNKNOWN to null, PreflightRules.space(null, 10L))
        assertEquals(SpaceVerdict.UNKNOWN to null, PreflightRules.space(1_000L, null))
        assertEquals(SpaceVerdict.SUFFICIENT, PreflightRules.space(1_000L, 1_000_000_000L).first)
        val (verdict, shortfall) = PreflightRules.space(10_000_000L, 1_000L)
        assertEquals(SpaceVerdict.INSUFFICIENT, verdict)
        assertTrue(shortfall!! >= 10_000_000L - 1_000L)
    }

    private fun check(id: String, available: Boolean, space: SpaceVerdict = SpaceVerdict.SUFFICIENT) =
        UnitCheck(unit(id, RestoreUnitKind.APP), UnitPreflight(id, available, ElevationTier.NONE), space)

    @Test
    fun `start is blocked when nothing is available`() {
        assertEquals(StartBlocker.NOTHING_AVAILABLE, PreflightRules.blocker(listOf(check("a", false)), ignoreInsufficientSpace = false))
        assertEquals(StartBlocker.NOTHING_AVAILABLE, PreflightRules.blocker(emptyList(), ignoreInsufficientSpace = true))
    }

    @Test
    fun `insufficient space blocks unless explicitly overridden`() {
        val checks = listOf(check("a", true), check("b", true, SpaceVerdict.INSUFFICIENT), check("c", false, SpaceVerdict.INSUFFICIENT))
        assertEquals(StartBlocker.INSUFFICIENT_SPACE, PreflightRules.blocker(checks, ignoreInsufficientSpace = false))
        assertNull(PreflightRules.blocker(checks, ignoreInsufficientSpace = true))
        assertEquals(2, PreflightRules.availableCount(checks))
    }

    @Test
    fun `blocked units' space does not block the start`() {
        val checks = listOf(check("a", true), check("b", false, SpaceVerdict.INSUFFICIENT))
        assertNull(PreflightRules.blocker(checks, ignoreInsufficientSpace = false))
    }

    // --- run state -----------------------------------------------------------------------------

    private val job = "job-1"
    private val units = listOf(
        unit("files:docs", RestoreUnitKind.FILE_TREE),
        unit("apps:a", RestoreUnitKind.APP),
        unit("partitions:boot", RestoreUnitKind.PARTITION),
        unit("partitions:dtbo", RestoreUnitKind.PARTITION),
    )

    private fun initial() = RestoreRunState(job, "dest", "dir", "Profile", units, startedAtMillis = 0L)

    private fun outcome(status: JobStatus, results: List<RestoreUnitResult>, warnings: List<String> = emptyList(), failure: RestoreFailure? = null) =
        RestoreOutcome(job, status, "dest", "dir", results, 0L, 0, 0L, 1L, warnings, failure)

    private fun run(vararg events: RestoreEvent): RestoreRunState = events.fold(initial(), RestoreRunReducer::reduce)

    private val summary = BackupSummary("dest", "dir", "dir/dftb-manifest.json", true, 0L, "Profile", 100L, false)

    @Test
    fun `events fold into the run state`() {
        val progress = JobProgress(jobId = job, kind = JobKind.RESTORE, title = "t", phase = JobPhase.RUNNING, processedBytes = 5, totalBytes = 10)
        val state = run(
            RestoreEvent.Opened(job, summary, unitCount = 4, totalBytes = 100L),
            RestoreEvent.PreflightChecked(job, listOf(UnitPreflight("apps:a", false, ElevationTier.ROOT, reason = "needs root"))),
            RestoreEvent.Warning(job, "w1"),
            RestoreEvent.Warning(job, "w1"),
            RestoreEvent.UnitStarted(job, 0, "files:docs", RestoreUnitKind.FILE_TREE, "Docs"),
            RestoreEvent.Progress(job, progress),
        )
        assertEquals(100L, state.totalBytes)
        assertEquals(1, state.preflight.size)
        assertEquals(listOf("w1"), state.warnings)
        assertEquals("files:docs", state.currentUnit?.unitId)
        assertEquals(progress, state.progress)
        assertFalse(state.finished)
        assertEquals(UnitRunStatus.RUNNING, RunSummarizer.status(state, "files:docs"))
        assertEquals(UnitRunStatus.WAITING, RunSummarizer.status(state, "partitions:boot"))
        assertEquals(RunHeadline.RUNNING, RunSummarizer.headline(state))
    }

    @Test
    fun `finished run merges outcome results and warnings`() {
        val state = run(
            RestoreEvent.UnitStarted(job, 0, "files:docs", RestoreUnitKind.FILE_TREE, "Docs"),
            RestoreEvent.UnitFinished(job, RestoreUnitResult("files:docs", ok = true)),
            RestoreEvent.Warning(job, "w1"),
            RestoreEvent.Finished(
                job,
                outcome(
                    JobStatus.COMPLETED_WITH_WARNINGS,
                    listOf(RestoreUnitResult("apps:a", ok = false, error = "blocked"), RestoreUnitResult("files:docs", ok = true)),
                    warnings = listOf("w1", "note"),
                ),
            ),
        )
        assertTrue(state.finished)
        assertNull(state.currentUnit)
        assertEquals(listOf("w1", "note"), state.warnings)
        assertEquals(UnitRunStatus.RESTORED, RunSummarizer.status(state, "files:docs"))
        assertEquals(UnitRunStatus.BLOCKED, RunSummarizer.status(state, "apps:a"))
        assertEquals(UnitRunStatus.NOT_RUN, RunSummarizer.status(state, "partitions:boot"))
        val s = RunSummarizer.summarize(state)
        assertEquals(1, s.restored)
        assertEquals(0, s.failed)
        assertEquals(1, s.blocked)
        assertEquals(2, s.notRun)
        assertEquals(2, s.warnings)
        assertFalse(s.doNotReboot)
        assertEquals(RunHeadline.COMPLETED_WITH_WARNINGS, RunSummarizer.headline(state))
    }

    @Test
    fun `a failed partition write means do not reboot`() {
        val state = run(
            RestoreEvent.UnitStarted(job, 0, "partitions:boot", RestoreUnitKind.PARTITION, "boot"),
            RestoreEvent.UnitFinished(job, RestoreUnitResult("partitions:boot", ok = false, error = "partial write")),
        )
        assertTrue(RunSummarizer.doNotReboot(state))
        assertEquals(UnitRunStatus.FAILED, RunSummarizer.status(state, "partitions:boot"))
        assertFalse(RunSummarizer.writingPartition(state))
    }

    @Test
    fun `a partition still being written is flagged as writing, not yet as failed`() {
        val state = run(RestoreEvent.UnitStarted(job, 0, "partitions:boot", RestoreUnitKind.PARTITION, "boot"))
        assertTrue(RunSummarizer.writingPartition(state))
        assertFalse(RunSummarizer.doNotReboot(state))
    }

    @Test
    fun `a run that ends during a partition unit without its result means do not reboot`() {
        val state = run(
            RestoreEvent.UnitStarted(job, 0, "partitions:boot", RestoreUnitKind.PARTITION, "boot"),
            RestoreEvent.Finished(job, outcome(JobStatus.CANCELLED, emptyList())),
        )
        assertTrue(RunSummarizer.doNotReboot(state))
        assertEquals(RunHeadline.CANCELLED, RunSummarizer.headline(state))
        assertEquals(UnitRunStatus.NOT_RUN, RunSummarizer.status(state, "partitions:boot"))
    }

    @Test
    fun `successful partitions and failed files do not trigger the reboot warning`() {
        val state = run(
            RestoreEvent.UnitStarted(job, 0, "partitions:boot", RestoreUnitKind.PARTITION, "boot"),
            RestoreEvent.UnitFinished(job, RestoreUnitResult("partitions:boot", ok = true)),
            RestoreEvent.UnitStarted(job, 1, "files:docs", RestoreUnitKind.FILE_TREE, "Docs"),
            RestoreEvent.UnitFinished(job, RestoreUnitResult("files:docs", ok = false, error = "x")),
            RestoreEvent.Finished(job, outcome(JobStatus.COMPLETED_WITH_WARNINGS, emptyList())),
        )
        assertFalse(RunSummarizer.doNotReboot(state))
        assertEquals(1, RunSummarizer.summarize(state).failed)
    }

    @Test
    fun `preflight-blocked partitions never trigger the reboot warning`() {
        val state = run(
            RestoreEvent.Finished(
                job,
                outcome(JobStatus.FAILED, listOf(RestoreUnitResult("partitions:boot", ok = false, error = "root")), failure = RestoreFailure(RestoreErrorCode.PREFLIGHT_BLOCKED)),
            ),
        )
        assertFalse(RunSummarizer.doNotReboot(state))
        assertEquals(UnitRunStatus.BLOCKED, RunSummarizer.status(state, "partitions:boot"))
        assertEquals(RunHeadline.FAILED, RunSummarizer.headline(state))
    }

    @Test
    fun `cancel request and crash are reflected in the headline`() {
        assertEquals(RunHeadline.CANCELLING, RunSummarizer.headline(initial().copy(cancelRequested = true)))
        val crashed = RestoreRunReducer.crashed(initial(), "boom")
        assertTrue(crashed.finished)
        assertEquals(RunHeadline.FAILED, RunSummarizer.headline(crashed))
        assertEquals(RunHeadline.COMPLETED, RunSummarizer.headline(run(RestoreEvent.Finished(job, outcome(JobStatus.COMPLETED, emptyList())))))
    }

    @Test
    fun `a restarted unit is listed once`() {
        val state = run(
            RestoreEvent.UnitStarted(job, 0, "apps:a", RestoreUnitKind.APP, "a"),
            RestoreEvent.UnitStarted(job, 1, "apps:a", RestoreUnitKind.APP, "a"),
        )
        assertEquals(1, state.started.size)
        assertEquals(1, state.started.single().index)
    }
}
