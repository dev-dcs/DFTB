package ca.devdcs.backup.core.restore.engine

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.ThroughputMeter
import ca.devdcs.backup.core.progress.JobKind
import ca.devdcs.backup.core.progress.JobPhase
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.restore.OpenedBackup
import ca.devdcs.backup.core.restore.RestoreUnitResult
import ca.devdcs.backup.core.restore.apps.AppRestoreOptions
import ca.devdcs.backup.core.restore.apps.DefaultAppRestorer
import ca.devdcs.backup.core.restore.catalog.CatalogException
import ca.devdcs.backup.core.restore.files.ExtractIssueCode
import ca.devdcs.backup.core.restore.files.ExtractProgress
import ca.devdcs.backup.core.restore.files.FileRestoreSpec
import ca.devdcs.backup.core.restore.partition.RootPartitionRestorer
import ca.devdcs.backup.core.storage.FreeSpaceVerifier
import ca.devdcs.backup.core.storage.PreflightResult
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.domain.model.CheckpointState
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * State machine of ONE restore run; short-lived so concurrent runs never share counters, the
 * opened backup or key material.
 */
internal class RestoreJob(
    private val deps: RestoreDeps,
    private val request: RestoreRequest,
    private val onEvent: suspend (RestoreEvent) -> Unit,
) {
    private val jobId = request.jobId
    private val messages = deps.messages

    /** One restore unit: all artifacts of one source. */
    private class PlannedUnit(val id: String, val kind: RestoreUnitKind, val artifacts: List<ManifestArtifact>, val name: String)

    private val warnings: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** Warnings that do not turn COMPLETED into "with warnings" (e.g. existing files kept by policy). */
    private val notes: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var warningsEmitted = 0
    private val results = ArrayList<RestoreUnitResult>()

    private val processedBytes = AtomicLong(0L)
    private val itemsDone = AtomicInteger(0)
    @Volatile private var phase: JobPhase = JobPhase.QUEUED
    @Volatile private var currentItem: String? = null
    @Volatile private var message: String? = null
    @Volatile private var totalBytes: Long = 0L
    @Volatile private var totalItems: Int = 0
    @Volatile private var title: String = request.title ?: request.jobDir
    private val meter = ThroughputMeter()
    private val publishLock = Any()
    @Volatile private var finished = false
    @Volatile private var finalOutcome: RestoreOutcome? = null

    private var startedAt = 0L
    @Volatile private var destination: Destination? = null
    @Volatile private var backup: OpenedBackup? = null
    private var checkpoint: JobCheckpoint? = null
    private var checkpointWarned = false
    @Volatile private var workJob: Job? = null
    private val isCancelled: () -> Boolean = { workJob?.isCancelled == true }

    private class StepException(val failure: RestoreFailure, cause: Throwable? = null) : Exception(failure.detail ?: failure.code.name, cause)

    // --- entry ---------------------------------------------------------------------------------

    suspend fun execute(): RestoreOutcome = coroutineScope {
        startedAt = deps.clock.nowMillis()
        deps.serviceLauncher.ensureStarted()
        phase = JobPhase.PREFLIGHT
        message = messages.opening()
        publishProgress()

        val userCancelled = AtomicBoolean(false)
        val work = async { perform() }
        workJob = work
        val watcher = launch {
            deps.progressBus.cancelRequests.first { jobId in it }
            userCancelled.set(true)
            work.cancel(CancellationException("Restore cancelled by the user"))
        }
        val ticker = launch {
            while (isActive) {
                delay(deps.progressIntervalMillis)
                publishProgress()
            }
        }
        // Subscribed before any unit runs: the app restorer's prompts have no replay.
        val appPrompts = deps.appEvents?.let { events ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                events.events.collect { event ->
                    emitQuietly(RestoreEvent.AppUserAction(jobId, event, events.pendingConfirmations.value))
                }
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            watcher.cancel()
            ticker.cancel()
            appPrompts?.cancel()
            val status = if (userCancelled.get()) JobStatus.CANCELLED else JobStatus.INTERRUPTED
            val outcome = withContext(NonCancellable) { finish(status, null) }
            if (userCancelled.get() && currentCoroutineContext().isActive) outcome else throw e
        } finally {
            watcher.cancel()
            ticker.cancel()
            appPrompts?.cancel()
            releaseResources()
        }
    }

    private suspend fun perform(): RestoreOutcome {
        try {
            val dest = deps.destinationRepository.get(request.destinationId)
                ?: throw step(RestoreErrorCode.DESTINATION_NOT_FOUND, request.destinationId)
            destination = dest
            recordStart()
            val opened = open(dest)
            backup = opened
            if (request.title == null) title = opened.manifest.profile.name
            // Interrupted backup: restore what it holds, but say that it is incomplete.
            if (opened.manifest.partial) warn(messages.partialBackup())

            val units = units(opened)
            if (units.isEmpty()) throw step(RestoreErrorCode.NOTHING_TO_RESTORE, null)
            emit(RestoreEvent.Opened(jobId, opened.summary, units.size, opened.summary.totalBytes ?: 0L))
            updateCheckpoint { it.copy(extras = it.extras + mapOf(EXTRA_DESTINATION_ID to dest.id, EXTRA_JOB_DIR to opened.summary.jobDir)) }

            if (request.options.verifyBeforeRestore) verifyFirst(opened, units)
            val runnable = preflight(opened, units)
            flushWarnings()

            phase = JobPhase.RUNNING
            message = null
            runnable.forEachIndexed { index, unit -> runUnit(opened, index, unit) }

            val attempted = results.filter { it.unitId in runnable.map(PlannedUnit::id) }
            if (attempted.isNotEmpty() && attempted.none { it.ok }) {
                throw step(RestoreErrorCode.ALL_UNITS_FAILED, attempted.mapNotNull { it.error }.firstOrNull())
            }
            phase = JobPhase.FINALIZING
            currentItem = null
            val degraded = results.any { !it.ok } || warnings.isNotEmpty()
            return finish(if (degraded) JobStatus.COMPLETED_WITH_WARNINGS else JobStatus.COMPLETED, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StepException) {
            return finish(JobStatus.FAILED, e.failure)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            DftbLog.e(TAG, "Restore $jobId failed", e)
            return finish(JobStatus.FAILED, RestoreFailure(RestoreErrorCode.INTERNAL, e.message ?: e.javaClass.simpleName))
        }
    }

    private suspend fun open(dest: Destination): OpenedBackup = try {
        deps.catalog.open(dest, request.jobDir, request.key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: CatalogException) {
        val code = when (e.reason) {
            CatalogException.Reason.KEY_REQUIRED -> RestoreErrorCode.KEY_REQUIRED
            CatalogException.Reason.INCONSISTENT_RUNS, CatalogException.Reason.UNSUPPORTED_SCHEMA,
            CatalogException.Reason.UNSUPPORTED_ENCRYPTION -> RestoreErrorCode.UNSUPPORTED_BACKUP
            else -> RestoreErrorCode.OPEN_FAILED
        }
        throw step(code, e.message, e)
    } catch (e: CipherIntegrityException) {
        val code = if (e.reason == CipherIntegrityException.Reason.WRONG_KEY || e.reason == CipherIntegrityException.Reason.KEY_TYPE_MISMATCH) {
            RestoreErrorCode.WRONG_KEY
        } else {
            RestoreErrorCode.OPEN_FAILED
        }
        throw step(code, e.message, e)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        throw step(RestoreErrorCode.OPEN_FAILED, e.message ?: e.javaClass.simpleName, e)
    }

    private fun units(opened: OpenedBackup): List<PlannedUnit> {
        val wanted = request.unitIds
        return opened.manifest.artifacts
            .filter { wanted == null || it.sourceId in wanted }
            .groupBy { it.sourceId }
            .map { (id, artifacts) ->
                val first = artifacts.first()
                PlannedUnit(id, RestoreUnitKind.of(first), artifacts, first.name ?: first.storedName)
            }
    }

    private suspend fun verifyFirst(opened: OpenedBackup, units: List<PlannedUnit>) {
        message = messages.verifying()
        publishProgress()
        val ids = units.map { it.id }.toSet()
        val checks = deps.catalog.verify(opened, deep = false) { done -> processedBytes.set(done) }
        processedBytes.set(0L)
        val bad = checks.filter { !it.ok && it.artifact.sourceId in ids }
        if (bad.isNotEmpty()) {
            throw step(RestoreErrorCode.VERIFY_FAILED, bad.joinToString("; ") { "${it.artifact.name ?: it.artifact.storedName}: ${it.problem}" })
        }
        message = null
    }

    // --- preflight -----------------------------------------------------------------------------

    private suspend fun preflight(opened: OpenedBackup, units: List<PlannedUnit>): List<PlannedUnit> {
        phase = JobPhase.PREFLIGHT
        message = messages.preflight()
        publishProgress()
        val checks = ArrayList<UnitPreflight>()
        val runnable = ArrayList<PlannedUnit>()
        var crossDeviceChecked = false
        for (unit in units) {
            currentCoroutineContext().ensureActive()
            if (unit.id in request.skipUnitIds) {
                notes += messages.resumeSkipped(unit.name)
                continue
            }
            if (!crossDeviceChecked && unit.kind != RestoreUnitKind.FILE_TREE) {
                crossDeviceChecked = true
                checkDevice(opened)
            }
            val check = unitPreflight(opened, unit)
            checks += check
            if (!check.available) {
                val reason = check.reason ?: messages.restorerMissing(unit.kind)
                warn(messages.unitBlocked(unit.name, reason))
                results += RestoreUnitResult(unit.id, ok = false, error = reason)
                continue
            }
            check.warnings.forEach(::warn)
            checkSpace(unit, check)
            runnable += unit
        }
        emit(RestoreEvent.PreflightChecked(jobId, checks))
        if (runnable.isEmpty() && checks.isNotEmpty()) {
            throw step(RestoreErrorCode.PREFLIGHT_BLOCKED, checks.mapNotNull { it.reason }.distinct().joinToString("; "))
        }
        totalBytes = runnable.sumOf { unit -> checks.firstOrNull { it.unitId == unit.id }?.requiredBytes ?: 0L }
        totalItems = runnable.sumOf { unit -> unit.artifacts.sumOf { it.items.size } }
        return runnable
    }

    private suspend fun unitPreflight(opened: OpenedBackup, unit: PlannedUnit): UnitPreflight = try {
        when (unit.kind) {
            RestoreUnitKind.FILE_TREE -> {
                val artifact = unit.artifacts.first()
                val p = deps.fileRestorer.preflight(opened, artifact, specFor(unit))
                UnitPreflight(
                    unitId = unit.id,
                    available = p.available,
                    requiredTier = p.tier,
                    requiredBytes = p.requiredBytes,
                    freeBytes = p.freeBytes,
                    reason = p.error?.takeIf { !p.available }?.let { messages.fileError(it, p.detail) },
                    warnings = listOfNotNull(p.error?.takeIf { p.available }?.let { messages.fileError(it, p.detail) }),
                )
            }
            RestoreUnitKind.APP -> appPreflight(opened, unit) ?: missing(unit)
            RestoreUnitKind.PARTITION -> partitionPreflight(opened, unit) ?: missing(unit)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        DftbLog.w(TAG, "Preflight of ${unit.id} failed", e)
        UnitPreflight(unit.id, available = false, requiredTier = ElevationTier.NONE, reason = e.message ?: e.javaClass.simpleName)
    }

    /** The default app restorer always gets explicit per-run options (never a global setting). */
    private suspend fun appPreflight(opened: OpenedBackup, unit: PlannedUnit): UnitPreflight? {
        val restorer = deps.appRestorer ?: return null
        return if (restorer is DefaultAppRestorer) {
            restorer.preflightApp(unit.artifacts, request.options, request.options.app ?: AppRestoreOptions())
        } else {
            restorer.preflight(opened, unit.artifacts, request.options)
        }
    }

    private suspend fun appRestore(opened: OpenedBackup, unit: PlannedUnit): RestoreUnitResult? {
        val restorer = deps.appRestorer ?: return null
        return if (restorer is DefaultAppRestorer) {
            restorer.restoreApp(opened, unit.artifacts, request.options, request.options.app ?: AppRestoreOptions(), unitProgress()).toUnitResult()
        } else {
            restorer.restore(opened, unit.artifacts, request.options, unitProgress())
        }
    }

    /** Explicit partition gates go to the root restorer's dedicated overload when set. */
    private suspend fun partitionPreflight(opened: OpenedBackup, unit: PlannedUnit): UnitPreflight? {
        val restorer = deps.partitionRestorer ?: return null
        val explicit = request.options.partition
        return if (explicit != null && restorer is RootPartitionRestorer) {
            restorer.preflight(opened, unit.artifacts, explicit)
        } else {
            restorer.preflight(opened, unit.artifacts, request.options)
        }
    }

    private suspend fun partitionRestore(opened: OpenedBackup, unit: PlannedUnit): RestoreUnitResult? {
        val restorer = deps.partitionRestorer ?: return null
        val explicit = request.options.partition
        return if (explicit != null && restorer is RootPartitionRestorer) {
            restorer.restore(opened, unit.artifacts, explicit, unitProgress())
        } else {
            restorer.restore(opened, unit.artifacts, request.options, unitProgress())
        }
    }

    private fun missing(unit: PlannedUnit) =
        UnitPreflight(unit.id, available = false, requiredTier = ElevationTier.NONE, reason = messages.restorerMissing(unit.kind))

    private fun checkSpace(unit: PlannedUnit, check: UnitPreflight) {
        val required = check.requiredBytes ?: return
        if (required <= 0L) return
        when (val result = FreeSpaceVerifier.evaluate(check.freeBytes?.let { SpaceInfo(freeBytes = it, totalBytes = null) }, required)) {
            is PreflightResult.Sufficient -> Unit
            is PreflightResult.Unknown -> notes += messages.spaceUnknown(unit.name)
            is PreflightResult.Insufficient -> {
                if (!request.options.ignoreInsufficientSpace) {
                    throw step(RestoreErrorCode.INSUFFICIENT_SPACE, "${unit.name}: short by ${result.shortfallBytes} bytes")
                }
                warn(messages.spaceOverridden(unit.name, result.shortfallBytes))
            }
        }
    }

    private suspend fun checkDevice(opened: OpenedBackup) {
        val here = try {
            deps.deviceInfo.device()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val from = opened.manifest.device
        if (from.device != here.device || from.model != here.model) warn(messages.crossDevice(from.model, here.model))
    }

    private fun specFor(unit: PlannedUnit): FileRestoreSpec = request.fileSpecs[unit.id] ?: request.fileSpec

    // --- units ---------------------------------------------------------------------------------

    private suspend fun runUnit(opened: OpenedBackup, index: Int, unit: PlannedUnit) {
        currentCoroutineContext().ensureActive()
        currentItem = unit.name
        message = messages.restoring(unit.name)
        updateCheckpoint { it.copy(currentItem = unit.id) }
        emit(RestoreEvent.UnitStarted(jobId, index, unit.id, unit.kind, unit.name))
        val result = try {
            when (unit.kind) {
                RestoreUnitKind.FILE_TREE -> restoreFiles(opened, unit)
                RestoreUnitKind.APP -> appRestore(opened, unit)
                    ?: RestoreUnitResult(unit.id, false, error = messages.restorerMissing(unit.kind))
                RestoreUnitKind.PARTITION -> partitionRestore(opened, unit)
                    ?: RestoreUnitResult(unit.id, false, error = messages.restorerMissing(unit.kind))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            DftbLog.w(TAG, "Unit ${unit.id} failed", e)
            RestoreUnitResult(unit.id, false, error = e.message ?: e.javaClass.simpleName)
        }
        results += result
        result.warnings.forEach(::warn)
        if (result.ok) updateCheckpoint { it.copy(completedItems = it.completedItems + unit.id, currentItem = null) }
        emit(RestoreEvent.UnitFinished(jobId, result))
        flushWarnings()
    }

    private suspend fun restoreFiles(opened: OpenedBackup, unit: PlannedUnit): RestoreUnitResult {
        val artifact = unit.artifacts.first()
        val progress = object : ExtractProgress {
            override fun onBytes(delta: Long) {
                processedBytes.addAndGet(delta)
            }

            override fun onItem(path: String) {
                itemsDone.incrementAndGet()
                currentItem = unit.name + "/" + path
            }

            override fun isCancelled(): Boolean = this@RestoreJob.isCancelled()
        }
        val outcome = deps.fileRestorer.restore(opened, artifact, specFor(unit), request.options.conflictPolicy, request.options.restoreMetadata, progress)
        val summary = outcome.summary
        val unitWarnings = ArrayList<String>()
        summary?.issues?.filter { it.code != ExtractIssueCode.SKIPPED_EXISTING }?.forEach { unitWarnings += messages.fileIssue(it) }
        val shown = summary?.issues?.size ?: 0
        if (summary != null && summary.issueCount > shown) unitWarnings += messages.moreIssues(summary.issueCount - shown)
        if (outcome.unmapped > 0) unitWarnings += messages.unmapped(outcome.unmapped)
        val kept = summary?.issues?.count { it.code == ExtractIssueCode.SKIPPED_EXISTING } ?: 0
        if (kept > 0) notes += messages.skippedExisting(kept)
        return RestoreUnitResult(
            unitId = unit.id,
            ok = outcome.ok,
            warnings = unitWarnings,
            error = outcome.error?.let { messages.fileError(it, outcome.detail) },
        )
    }

    private fun unitProgress(): RestoreProgress = object : RestoreProgress {
        override fun onBytes(delta: Long) {
            processedBytes.addAndGet(delta)
        }

        override fun onItem(name: String) {
            itemsDone.incrementAndGet()
            currentItem = name
        }

        override fun onWarning(message: String) = warn(message)

        override fun isCancelled(): Boolean = this@RestoreJob.isCancelled()
    }

    // --- journal, history, progress ------------------------------------------------------------

    private suspend fun recordStart() {
        val existing = try {
            deps.checkpointRepository.get(jobId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val cp = JobCheckpoint(
            jobId = jobId,
            profileId = request.profileId,
            type = JobType.RESTORE,
            status = JobStatus.RUNNING,
            attempt = (existing?.attempt ?: 0) + 1,
            state = existing?.state ?: CheckpointState.INITIAL,
            createdAt = existing?.createdAt ?: startedAt,
            updatedAt = startedAt,
            // Writing apps/partitions while the system boots is unsafe: resume only on request.
            resumeAfterReboot = false,
        )
        checkpoint = cp
        saveCheckpoint(cp)
        saveHistory(historyRecord(JobStatus.RUNNING, null, null, null))
    }

    private suspend fun updateCheckpoint(transform: (CheckpointState) -> CheckpointState) {
        val cp = checkpoint ?: return
        val next = cp.copy(state = transform(cp.state), updatedAt = deps.clock.nowMillis())
        checkpoint = next
        saveCheckpoint(next)
    }

    private suspend fun saveCheckpoint(cp: JobCheckpoint) {
        try {
            deps.checkpointRepository.save(cp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Checkpoint write failed for $jobId", e)
            if (!checkpointWarned) {
                checkpointWarned = true
                notes += messages.checkpointFailed()
            }
        }
    }

    private suspend fun saveHistory(record: HistoryRecord) {
        try {
            deps.historyRepository.save(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "History write failed for $jobId", e)
        }
    }

    private fun historyRecord(status: JobStatus, finishedAt: Long?, outcome: RestoreOutcome?, errorMessage: String?): HistoryRecord {
        val opened = backup
        return HistoryRecord(
            id = jobId,
            profileId = request.profileId,
            destinationId = destination?.id,
            type = JobType.RESTORE,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            bytesProcessed = outcome?.bytesWritten ?: 0L,
            itemCount = outcome?.itemCount ?: 0,
            artifactPath = request.jobDir,
            artifactSizeBytes = opened?.summary?.totalBytes,
            checksumAlgorithm = opened?.manifest?.checksumAlgorithm,
            checksumManifestPath = opened?.summary?.manifestPath,
            errorMessage = errorMessage,
            logExcerpt = outcome?.warnings?.takeIf { it.isNotEmpty() }?.joinToString("\n")?.take(LOG_EXCERPT_LIMIT),
        )
    }

    /** Terminal bookkeeping; non-cancellable so a cancelled restore is still recorded. */
    private suspend fun finish(status: JobStatus, failure: RestoreFailure?): RestoreOutcome = withContext(NonCancellable) {
        finalOutcome?.let { return@withContext it }
        val now = deps.clock.nowMillis()
        val okCount = results.count { it.ok }
        val allWarnings = synchronized(warnings) { warnings.toList() } + synchronized(notes) { notes.toList() }
        val outcome = RestoreOutcome(
            jobId = jobId,
            status = status,
            destinationId = request.destinationId,
            jobDir = request.jobDir,
            results = results.toList(),
            bytesWritten = processedBytes.get(),
            itemCount = itemsDone.get(),
            startedAtMillis = startedAt,
            finishedAtMillis = now,
            warnings = allWarnings,
            failure = failure,
        )
        val summary = when (status) {
            JobStatus.COMPLETED -> messages.completed(okCount)
            JobStatus.COMPLETED_WITH_WARNINGS -> messages.completedWithWarnings(okCount, allWarnings.size + results.count { !it.ok })
            JobStatus.CANCELLED, JobStatus.INTERRUPTED -> messages.cancelled()
            else -> messages.failure(failure ?: RestoreFailure(RestoreErrorCode.INTERNAL))
        }
        saveHistory(historyRecord(status, now, outcome, if (outcome.isSuccessful) null else summary))
        checkpoint?.let { cp ->
            val next = cp.copy(status = status, updatedAt = now)
            checkpoint = next
            saveCheckpoint(next)
        }
        flushWarningsQuietly()
        phase = when (status) {
            JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS -> JobPhase.COMPLETED
            JobStatus.CANCELLED, JobStatus.INTERRUPTED -> JobPhase.CANCELLED
            else -> JobPhase.FAILED
        }
        message = summary
        currentItem = null
        val terminal = buildProgress(now)
        synchronized(publishLock) {
            finished = true
            deps.progressBus.publish(terminal)
        }
        finalOutcome = outcome
        emitQuietly(RestoreEvent.Progress(jobId, terminal))
        emitQuietly(RestoreEvent.Finished(jobId, outcome))
        outcome
    }

    private fun releaseResources() {
        try {
            backup?.close()
        } catch (e: Exception) {
            DftbLog.w(TAG, "Closing the backup failed", e)
        }
        backup = null
    }

    private fun buildProgress(now: Long): JobProgress {
        val processed = processedBytes.get()
        val rate = synchronized(meter) { meter.sample(now, processed) }
        return JobProgress(
            jobId = jobId,
            kind = JobKind.RESTORE,
            title = title,
            phase = phase,
            currentItem = currentItem,
            processedBytes = processed,
            totalBytes = totalBytes,
            processedItems = itemsDone.get(),
            totalItems = totalItems,
            bytesPerSecond = rate,
            startedAtMillis = startedAt,
            updatedAtMillis = now,
            message = message,
        )
    }

    private suspend fun publishProgress() {
        val progress = buildProgress(deps.clock.nowMillis())
        val published = synchronized(publishLock) {
            if (finished) {
                false
            } else {
                deps.progressBus.publish(progress)
                true
            }
        }
        if (published) emit(RestoreEvent.Progress(jobId, progress))
    }

    // --- warnings & events ---------------------------------------------------------------------

    private fun warn(text: String) {
        warnings += text
    }

    private suspend fun flushWarnings() {
        val pending = synchronized(warnings) {
            val slice = warnings.subList(warningsEmitted, warnings.size).toList()
            warningsEmitted = warnings.size
            slice
        }
        pending.forEach { emit(RestoreEvent.Warning(jobId, it)) }
    }

    private suspend fun flushWarningsQuietly() {
        try {
            flushWarnings()
        } catch (e: Exception) {
            DftbLog.d(TAG, "Event collector gone while flushing warnings")
        }
    }

    private suspend fun emit(event: RestoreEvent) = onEvent(event)

    private suspend fun emitQuietly(event: RestoreEvent) {
        try {
            onEvent(event)
        } catch (e: Exception) {
            DftbLog.d(TAG, "Event collector gone; dropped ${event.javaClass.simpleName}")
        }
    }

    private fun step(code: RestoreErrorCode, detail: String?, cause: Throwable? = null) = StepException(RestoreFailure(code, detail), cause)

    companion object {
        private const val TAG = "RestoreEngine"
        /** Checkpoint extras (read by a restore resume). */
        const val EXTRA_JOB_DIR = "jobDir"
        const val EXTRA_DESTINATION_ID = "destinationId"
        private const val LOG_EXCERPT_LIMIT = 4_000
    }
}
