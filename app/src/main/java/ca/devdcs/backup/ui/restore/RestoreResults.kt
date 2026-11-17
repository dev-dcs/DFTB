package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.restore.ArtifactCheck
import ca.devdcs.backup.core.restore.RestoreUnitResult
import ca.devdcs.backup.core.restore.apps.AppRestoreEvent
import ca.devdcs.backup.core.restore.catalog.ArtifactProblems
import ca.devdcs.backup.core.restore.catalog.CatalogException
import ca.devdcs.backup.core.restore.engine.RestoreEvent
import ca.devdcs.backup.core.restore.engine.RestoreOutcome
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.core.restore.engine.UnitPreflight
import ca.devdcs.backup.core.storage.FreeSpaceVerifier
import ca.devdcs.backup.core.storage.PreflightResult
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.domain.model.JobStatus

/**
 * A verification problem decoded from [ArtifactCheck.problem] (`CODE|detail`, `CIPHER_<reason>|…`,
 * `IO|…`, optionally followed by ` (+N)` for further problems of the same tree). Exists so the UI
 * shows a localized cause instead of the machine code.
 */
sealed interface ArtifactProblem {
    /** How many further problems the same artifact had. */
    val more: Int

    data class Catalog(val reason: CatalogException.Reason, val detail: String?, override val more: Int = 0) : ArtifactProblem
    data class Cipher(val reason: CipherIntegrityException.Reason, val detail: String?, override val more: Int = 0) : ArtifactProblem
    data class Io(val detail: String?, override val more: Int = 0) : ArtifactProblem
    /** Text the parser does not recognise; shown verbatim. */
    data class Unknown(val text: String, override val more: Int = 0) : ArtifactProblem
}

/** Decodes [ArtifactCheck.problem] strings so the UI never shows raw machine codes. */
object ArtifactProblemParser {
    private val MORE = Regex("""\s\(\+(\d+)\)$""")
    private const val CIPHER_PREFIX = "CIPHER_"
    private const val IO_CODE = "IO"

    fun parse(problem: String?): ArtifactProblem? {
        if (problem.isNullOrBlank()) return null
        val match = MORE.find(problem)
        val more = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val core = if (match != null) problem.substring(0, match.range.first) else problem
        val code = core.substringBefore(ArtifactProblems.SEPARATOR)
        val detail = core.substringAfter(ArtifactProblems.SEPARATOR, "").ifBlank { null }
        if (code.startsWith(CIPHER_PREFIX)) {
            val reason = CipherIntegrityException.Reason.entries.firstOrNull { it.name == code.removePrefix(CIPHER_PREFIX) }
            if (reason != null) return ArtifactProblem.Cipher(reason, detail, more)
        }
        if (code == IO_CODE) return ArtifactProblem.Io(detail, more)
        val (reason, catalogDetail) = ArtifactProblems.parse(core)
        return if (reason != null) ArtifactProblem.Catalog(reason, catalogDetail, more) else ArtifactProblem.Unknown(core, more)
    }
}

/** Counts of one verification pass. */
data class VerifySummary(val total: Int, val ok: Int) {
    val failed: Int get() = total - ok
    val allOk: Boolean get() = total > 0 && failed == 0
}

fun summarizeChecks(checks: List<ArtifactCheck>): VerifySummary = VerifySummary(total = checks.size, ok = checks.count { it.ok })

/** Free-space verdict of one unit's target. */
enum class SpaceVerdict { NOT_NEEDED, SUFFICIENT, UNKNOWN, INSUFFICIENT }

/** One unit's dry-run preflight as the wizard shows it. */
data class UnitCheck(
    val unit: RestoreUnitInfo,
    val preflight: UnitPreflight,
    val space: SpaceVerdict,
    val shortfallBytes: Long? = null,
    /** Typed cause when the preflight itself threw (rendered localized; [UnitPreflight.reason] stays as raw detail). */
    val failure: OpenFailure? = null,
)

/** Why the restore cannot start from the preflight step. */
enum class StartBlocker {
    /** Every selected unit is blocked here. */
    NOTHING_AVAILABLE,
    /** An available unit lacks space and the override is off (the engine would stop the run). */
    INSUFFICIENT_SPACE,
}

/** Pure rules turning preflight facts into what the wizard allows. */
object PreflightRules {

    /** Same decision as the engine (FreeSpaceVerifier margin), so the dry run predicts the run. */
    fun space(requiredBytes: Long?, freeBytes: Long?): Pair<SpaceVerdict, Long?> {
        val required = requiredBytes ?: return SpaceVerdict.UNKNOWN to null
        if (required <= 0L) return SpaceVerdict.NOT_NEEDED to null
        return when (val result = FreeSpaceVerifier.evaluate(freeBytes?.let { SpaceInfo(freeBytes = it, totalBytes = null) }, required)) {
            is PreflightResult.Sufficient -> SpaceVerdict.SUFFICIENT to null
            is PreflightResult.Unknown -> SpaceVerdict.UNKNOWN to null
            is PreflightResult.Insufficient -> SpaceVerdict.INSUFFICIENT to result.shortfallBytes
        }
    }

    fun blocker(checks: List<UnitCheck>, ignoreInsufficientSpace: Boolean): StartBlocker? {
        val available = checks.filter { it.preflight.available }
        if (available.isEmpty()) return StartBlocker.NOTHING_AVAILABLE
        if (!ignoreInsufficientSpace && available.any { it.space == SpaceVerdict.INSUFFICIENT }) return StartBlocker.INSUFFICIENT_SPACE
        return null
    }

    fun availableCount(checks: List<UnitCheck>): Int = checks.count { it.preflight.available }
}

/** A unit the engine started, in order. */
data class StartedUnit(val unitId: String, val kind: RestoreUnitKind, val name: String, val index: Int)

/**
 * Live state of one restore run, folded from [RestoreEvent]s by [RestoreRunReducer]. Kept by the
 * run coordinator (not a ViewModel) so leaving the screen never interrupts a restore.
 */
data class RestoreRunState(
    val jobId: String,
    val destinationId: String,
    val jobDir: String,
    val title: String?,
    /** The selected units, so blocked/never-started ones can be reported by kind and name. */
    val units: List<RestoreUnitInfo>,
    val startedAtMillis: Long,
    /** SAF tree the file units are written to, if any (its grant must outlive the run). */
    val treeUri: String? = null,
    val totalBytes: Long? = null,
    val progress: JobProgress? = null,
    val preflight: List<UnitPreflight> = emptyList(),
    val started: List<StartedUnit> = emptyList(),
    val results: List<RestoreUnitResult> = emptyList(),
    val warnings: List<String> = emptyList(),
    val pendingInstalls: List<AppRestoreEvent.InstallConfirmationRequired> = emptyList(),
    val permissionPrompts: List<AppRestoreEvent.InstallPermissionRequired> = emptyList(),
    val outcome: RestoreOutcome? = null,
    val cancelRequested: Boolean = false,
    /** The event flow itself failed (never expected; the engine reports failures in its outcome). */
    val crashDetail: String? = null,
    /** Typed cause of the crash, rendered localized; [crashDetail] stays as the raw secondary detail. */
    val crashFailure: OpenFailure? = null,
) {
    val finished: Boolean get() = outcome != null || crashDetail != null

    /** The unit being restored right now, or null between units / after the run. */
    val currentUnit: StartedUnit?
        get() {
            if (finished) return null
            val last = started.lastOrNull() ?: return null
            return last.takeIf { s -> results.none { it.unitId == s.unitId } }
        }

    fun resultOf(unitId: String): RestoreUnitResult? = results.lastOrNull { it.unitId == unitId }
}

/** Pure fold of engine events into [RestoreRunState], so the run panel is testable without the engine. */
object RestoreRunReducer {

    fun reduce(state: RestoreRunState, event: RestoreEvent): RestoreRunState = when (event) {
        is RestoreEvent.Opened -> state.copy(totalBytes = event.totalBytes)
        is RestoreEvent.PreflightChecked -> state.copy(preflight = event.units)
        is RestoreEvent.UnitStarted -> state.copy(
            started = state.started.filterNot { it.unitId == event.unitId } + StartedUnit(event.unitId, event.kind, event.name, event.index),
        )
        is RestoreEvent.UnitFinished -> state.copy(results = upsert(state.results, listOf(event.result)))
        is RestoreEvent.Warning -> if (event.message in state.warnings) state else state.copy(warnings = state.warnings + event.message)
        is RestoreEvent.AppUserAction -> {
            val prompts = when (val e = event.event) {
                is AppRestoreEvent.InstallPermissionRequired ->
                    state.permissionPrompts.filterNot { it.packageName == e.packageName } + e
                else -> state.permissionPrompts
            }
            state.copy(pendingInstalls = event.pending, permissionPrompts = prompts)
        }
        is RestoreEvent.Progress -> state.copy(progress = event.progress)
        is RestoreEvent.Finished -> state.copy(
            outcome = event.outcome,
            results = upsert(state.results, event.outcome.results),
            warnings = (state.warnings + event.outcome.warnings).distinct(),
            pendingInstalls = emptyList(),
        )
    }

    fun crashed(state: RestoreRunState, detail: String, failure: OpenFailure? = null): RestoreRunState =
        state.copy(crashDetail = detail, crashFailure = failure, pendingInstalls = emptyList())

    private fun upsert(current: List<RestoreUnitResult>, incoming: List<RestoreUnitResult>): List<RestoreUnitResult> {
        if (incoming.isEmpty()) return current
        val byId = LinkedHashMap<String, RestoreUnitResult>()
        current.forEach { byId[it.unitId] = it }
        incoming.forEach { byId[it.unitId] = it }
        return byId.values.toList()
    }
}

/** Per-unit status in the run panel. */
enum class UnitRunStatus { WAITING, RUNNING, RESTORED, FAILED, BLOCKED, NOT_RUN }

/** Counts of one run, for the summary line and banners. */
data class RunSummary(
    val restored: Int,
    val failed: Int,
    val blocked: Int,
    val notRun: Int,
    val warnings: Int,
    /** A partition write started and did not end with a verified success: the device may not boot. */
    val doNotReboot: Boolean,
)

/** Derives per-unit status, counts and the safety banners of a run from its state. */
object RunSummarizer {

    fun status(state: RestoreRunState, unitId: String): UnitRunStatus {
        val result = state.resultOf(unitId)
        val started = state.started.any { it.unitId == unitId }
        return when {
            result != null && result.ok -> UnitRunStatus.RESTORED
            result != null && started -> UnitRunStatus.FAILED
            result != null -> UnitRunStatus.BLOCKED
            state.currentUnit?.unitId == unitId -> UnitRunStatus.RUNNING
            state.finished -> UnitRunStatus.NOT_RUN
            started -> UnitRunStatus.RUNNING
            else -> UnitRunStatus.WAITING
        }
    }

    /**
     * Conservative "do not reboot" rule: a partition unit that the engine started and that has no
     * successful result. The writer reports every failure after the first byte as a partial write,
     * and a cancelled or interrupted run may end without the unit's result at all.
     */
    fun doNotReboot(state: RestoreRunState): Boolean =
        state.started.any { s -> s.kind == RestoreUnitKind.PARTITION && state.resultOf(s.unitId)?.ok != true && (state.finished || state.resultOf(s.unitId) != null) }

    /** True while a partition unit is being written (the user must not power off now). */
    fun writingPartition(state: RestoreRunState): Boolean = state.currentUnit?.kind == RestoreUnitKind.PARTITION

    fun summarize(state: RestoreRunState): RunSummary {
        val statuses = state.units.map { status(state, it.id) }
        return RunSummary(
            restored = statuses.count { it == UnitRunStatus.RESTORED },
            failed = statuses.count { it == UnitRunStatus.FAILED },
            blocked = statuses.count { it == UnitRunStatus.BLOCKED },
            notRun = statuses.count { it == UnitRunStatus.NOT_RUN },
            warnings = state.warnings.size,
            doNotReboot = doNotReboot(state),
        )
    }

    /** Status of the whole run as the panel headline shows it. */
    fun headline(state: RestoreRunState): RunHeadline = when {
        state.crashDetail != null -> RunHeadline.FAILED
        state.outcome == null -> if (state.cancelRequested) RunHeadline.CANCELLING else RunHeadline.RUNNING
        else -> when (state.outcome.status) {
            JobStatus.COMPLETED -> RunHeadline.COMPLETED
            JobStatus.COMPLETED_WITH_WARNINGS -> RunHeadline.COMPLETED_WITH_WARNINGS
            JobStatus.CANCELLED, JobStatus.INTERRUPTED -> RunHeadline.CANCELLED
            JobStatus.FAILED, JobStatus.RUNNING -> RunHeadline.FAILED
        }
    }
}

/** The one-word state of a run shown under the run panel's title. */
enum class RunHeadline { RUNNING, CANCELLING, COMPLETED, COMPLETED_WITH_WARNINGS, CANCELLED, FAILED }
