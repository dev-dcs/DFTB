package ca.devdcs.backup.ui.shell

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.logging.LogBuffer
import ca.devdcs.backup.core.logging.LogLevel
import ca.devdcs.backup.core.logging.LogLine
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.di.DefaultDispatcher
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.repository.HistoryRepository
import ca.devdcs.backup.ui.navigation.DftbDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One job as the monitor shows it: live from the bus, or the final state it published. */
data class MonitoredJob(
    val progress: JobProgress,
    val active: Boolean,
    val cancelRequested: Boolean,
)

data class JobMonitorUiState(
    /** The job this monitor was opened for; null = all jobs. */
    val focusJobId: String? = null,
    /** Active jobs first (bus order), then jobs that finished while the monitor was open. */
    val jobs: List<MonitoredJob> = emptyList(),
    /** History row of the focused job, for a job that finished before the monitor opened. */
    val focusRecord: HistoryRecord? = null,
    /** Job whose log lines are shown in the all-jobs view; null = every line. */
    val logJobId: String? = null,
    val lines: List<LogLine> = emptyList(),
    val minLevel: LogLevel = LogLevel.DEBUG,
    val loaded: Boolean = false,
) {
    val focusJob: MonitoredJob? get() = focusJobId?.let { id -> jobs.firstOrNull { it.progress.jobId == id } }
}

/**
 * Backs the job monitor: live progress for one or all jobs from [JobProgressBus], cancellation
 * through the bus's existing cancel path, and the [LogBuffer] tail scoped to a job by
 * [JobLogFilter]. Jobs that end while the screen is open keep their final state, and a job that
 * ended earlier (or before a process restart) falls back to its History row.
 */
@HiltViewModel
class JobMonitorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bus: JobProgressBus,
    logBuffer: LogBuffer,
    historyRepository: HistoryRepository,
    private val logExporter: LogExporter,
    private val clock: EngineClock,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val focusJobId: String? = savedStateHandle.toRoute<DftbDestination.JobMonitor>().jobId

    /** Final states published while this ViewModel lived, keyed by job id. */
    private val finished = MutableStateFlow<Map<String, JobProgress>>(emptyMap())

    /** Last progress seen per job id, so a job that leaves the bus keeps its window. */
    private val seen = MutableStateFlow<Map<String, JobProgress>>(emptyMap())

    private val logJobId: StateFlow<String?> = savedStateHandle.getStateFlow(KEY_LOG_JOB, focusJobId)
    private val minLevelName: StateFlow<String> = savedStateHandle.getStateFlow(KEY_MIN_LEVEL, LogLevel.DEBUG.name)
    private val handle = savedStateHandle

    private val focusRecord: Flow<HistoryRecord?> = (focusJobId?.let { historyRepository.observe(it) } ?: flowOf(null))
        .catch { e ->
            if (e is CancellationException) throw e
            DftbLog.w("JobMonitor", "History observe failed for job $focusJobId", e)
            emit(null)
        }

    /**
     * Log tail, at most a few renders per second however chatty the engine is: the StateFlow is
     * conflated, so pausing the collector skips straight to the newest buffer.
     */
    private val throttledLines: Flow<List<LogLine>> = logBuffer.lines
        .transform { lines ->
            emit(lines)
            delay(LOG_THROTTLE_MILLIS)
        }

    init {
        viewModelScope.launch {
            bus.terminalEvents.collect { done -> finished.update { it + (done.jobId to done) } }
        }
        viewModelScope.launch {
            bus.activeJobs.collect { active -> if (active.isNotEmpty()) seen.update { it + active } }
        }
    }

    private val jobs: Flow<List<MonitoredJob>> = combine(bus.activeJobs, bus.cancelRequests, finished) { active, cancels, done ->
        val live = active.values.map { MonitoredJob(it, active = true, cancelRequested = it.jobId in cancels) }
        val ended = done.values
            .filter { it.jobId !in active }
            .sortedByDescending { it.updatedAtMillis }
            .map { MonitoredJob(it, active = false, cancelRequested = false) }
        live + ended
    }

    /** Jobs plus the log windows derived from them; recomputed whenever `seen` or `finished` change. */
    private val jobsAndWindows: Flow<Pair<List<MonitoredJob>, List<JobWindow>>> = combine(jobs, seen, finished) { jobList, seenMap, done ->
        val known = seenMap + jobList.associate { it.progress.jobId to it.progress }
        jobList to known.values.map { window(it, done[it.jobId]) }
    }

    val uiState: StateFlow<JobMonitorUiState> = combine(
        jobsAndWindows,
        focusRecord,
        throttledLines,
        logJobId,
        minLevelName,
    ) { jobsWindows, record, lines, selectedLogJob, levelName ->
        val (jobList, windows) = jobsWindows
        val level = LogLevel.entries.firstOrNull { it.name == levelName } ?: LogLevel.DEBUG
        val targetId = selectedLogJob
        val visible = when {
            targetId == null -> JobLogFilter.filter(lines, null, windows, level)
            else -> {
                val target = windows.firstOrNull { it.jobId == targetId } ?: record?.takeIf { it.id == targetId }?.let(::window)
                if (target == null) {
                    JobLogFilter.mentioning(lines, targetId, level)
                } else {
                    JobLogFilter.filter(lines, target, windows.filter { it.jobId != targetId }, level)
                }
            }
        }
        JobMonitorUiState(
            focusJobId = focusJobId,
            jobs = jobList,
            focusRecord = record,
            logJobId = selectedLogJob,
            lines = visible,
            minLevel = level,
            loaded = true,
        )
    }
        .flowOn(defaultDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), JobMonitorUiState(focusJobId = focusJobId))

    /** Routes through the bus exactly like the notification's cancel button; the engine records CANCELLED. */
    fun cancel(jobId: String) = bus.requestCancel(jobId)

    /** All-jobs view: show only [jobId]'s lines (null = every line). */
    fun selectLogJob(jobId: String?) {
        handle[KEY_LOG_JOB] = jobId
    }

    fun setMinLevel(level: LogLevel) {
        handle[KEY_MIN_LEVEL] = level.name
    }

    /** Bounded text of the visible lines, for the clipboard. */
    fun clipboardText(header: String): String =
        JobLogFilter.export(header, uiState.value.lines, clock.zone(), JobLogFilter.MAX_EXPORT_CHARS)

    /** ACTION_SEND for the visible lines (file via FileProvider, text fallback). */
    suspend fun shareIntent(header: String, subject: String): Intent {
        val lines = uiState.value.lines
        val zone = clock.zone()
        val full = JobLogFilter.export(header, lines, zone, Int.MAX_VALUE)
        val fallback = JobLogFilter.export(header, lines, zone, JobLogFilter.MAX_EXPORT_CHARS)
        val name = "dftb-log-${focusJobId ?: uiState.value.logJobId ?: "all"}-${clock.nowMillis()}.txt"
        return logExporter.shareIntent(name, subject, full, fallback)
    }

    private fun window(progress: JobProgress, terminal: JobProgress?): JobWindow = JobWindow(
        jobId = progress.jobId,
        title = progress.title,
        startedAtMillis = progress.startedAtMillis,
        endedAtMillis = terminal?.updatedAtMillis ?: if (progress.isTerminal) progress.updatedAtMillis else null,
    )

    private fun window(record: HistoryRecord): JobWindow = JobWindow(
        jobId = record.id,
        title = null,
        startedAtMillis = record.startedAt,
        endedAtMillis = record.finishedAt,
    )

    private companion object {
        const val KEY_LOG_JOB = "logJobId"
        const val KEY_MIN_LEVEL = "minLevel"
        const val LOG_THROTTLE_MILLIS = 250L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
