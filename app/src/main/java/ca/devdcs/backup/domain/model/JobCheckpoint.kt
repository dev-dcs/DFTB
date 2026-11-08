package ca.devdcs.backup.domain.model

import kotlinx.serialization.Serializable

/**
 * Where a job was when it last persisted progress. Engines write it after every completed item or
 * part so a process death, reboot or OOM kill resumes instead of restarting. [extras] carries
 * engine-specific state (upload session id, multipart ETags...) without schema changes.
 */
@Serializable
data class CheckpointState(
    val completedItems: List<String> = emptyList(),
    val currentItem: String? = null,
    val currentOffsetBytes: Long = 0L,
    val partIndex: Int = 0,
    val extras: Map<String, String> = emptyMap(),
) {
    companion object {
        val INITIAL = CheckpointState()
    }
}

/**
 * Durable journal entry for a running or interrupted job. One row per job id; terminal rows are
 * kept only until the owner clears them so the dashboard can offer "resume" after a crash.
 */
data class JobCheckpoint(
    val jobId: String,
    val profileId: String? = null,
    val type: JobType,
    val status: JobStatus,
    val attempt: Int = 1,
    val state: CheckpointState = CheckpointState.INITIAL,
    val createdAt: Long,
    val updatedAt: Long,
    val resumeAfterReboot: Boolean = true,
) {
    /** Resumable means not finished: still running when the process died, or explicitly interrupted. */
    val isResumable: Boolean get() = !status.isTerminal
}
