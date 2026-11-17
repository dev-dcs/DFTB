package ca.devdcs.backup.core.progress

import ca.devdcs.backup.core.logging.DftbLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide, in-memory channel between job engines and everything that displays or controls
 * them. Engines call [publish]; the foreground service, dashboard and notifications observe
 * [activeJobs] and [terminalEvents]; the UI calls [requestCancel] and engines poll
 * [isCancelRequested] between chunks.
 *
 * Durable checkpoints (surviving process death) are the Room journal's job, not this bus.
 */
@Singleton
class JobProgressBus @Inject constructor() {

    private val _activeJobs = MutableStateFlow<Map<String, JobProgress>>(emptyMap())
    /** Jobs that have not reached a terminal phase, keyed by job id, in insertion order. */
    val activeJobs: StateFlow<Map<String, JobProgress>> = _activeJobs.asStateFlow()

    private val _terminalEvents = MutableSharedFlow<JobProgress>(replay = 0, extraBufferCapacity = 32)
    /** Emitted once per job when it completes, fails or is cancelled. */
    val terminalEvents: SharedFlow<JobProgress> = _terminalEvents.asSharedFlow()

    private val _cancelRequests = MutableStateFlow<Set<String>>(emptySet())
    val cancelRequests: StateFlow<Set<String>> = _cancelRequests.asStateFlow()

    /** Record the latest state of a job. Terminal states remove it from [activeJobs]. */
    fun publish(progress: JobProgress) {
        if (progress.isTerminal) {
            _activeJobs.update { it - progress.jobId }
            _cancelRequests.update { it - progress.jobId }
            if (!_terminalEvents.tryEmit(progress)) {
                DftbLog.w(TAG, "Terminal event buffer full; dropped ${progress.jobId}")
            }
            DftbLog.i(TAG, "Job ${progress.jobId} ${progress.phase}: ${progress.message ?: progress.title}")
        } else {
            _activeJobs.update { it + (progress.jobId to progress) }
        }
    }

    fun current(jobId: String): JobProgress? = _activeJobs.value[jobId]

    fun requestCancel(jobId: String) {
        if (_activeJobs.value.containsKey(jobId)) {
            DftbLog.i(TAG, "Cancel requested for $jobId")
            _cancelRequests.update { it + jobId }
        }
    }

    fun isCancelRequested(jobId: String): Boolean = jobId in _cancelRequests.value

    private companion object {
        const val TAG = "JobBus"
    }
}
