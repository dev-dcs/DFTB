package ca.devdcs.backup.core.schedule

import ca.devdcs.backup.core.pipeline.engine.BackupErrorCode
import ca.devdcs.backup.core.pipeline.engine.BackupFailure
import ca.devdcs.backup.core.pipeline.engine.BackupOutcome
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.domain.model.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupResultMapperTest {

    private fun outcome(status: JobStatus, failure: BackupFailure? = null) = BackupOutcome(
        jobId = "j",
        profileId = "p",
        status = status,
        jobDir = null,
        manifestPath = null,
        artifacts = emptyList(),
        storedBytes = 0L,
        sourceBytes = 0L,
        itemCount = 0,
        startedAtMillis = 0L,
        finishedAtMillis = 1L,
        warnings = emptyList(),
        failure = failure,
    )

    @Test
    fun successesAndUserCancelAreFinal() {
        assertEquals(WorkDecision.SUCCESS, BackupResultMapper.decide(outcome(JobStatus.COMPLETED), 0))
        assertEquals(WorkDecision.SUCCESS, BackupResultMapper.decide(outcome(JobStatus.COMPLETED_WITH_WARNINGS), 0))
        assertEquals(WorkDecision.SUCCESS, BackupResultMapper.decide(outcome(JobStatus.CANCELLED), 0))
    }

    @Test
    fun transientStorageFailuresRetry() {
        val network = outcome(JobStatus.FAILED, BackupFailure(BackupErrorCode.STORAGE, storageReason = StorageException.Reason.NETWORK))
        assertEquals(WorkDecision.RETRY, BackupResultMapper.decide(network, 0))
        assertEquals(WorkDecision.RETRY, BackupResultMapper.decide(outcome(JobStatus.INTERRUPTED), 1))
    }

    @Test
    fun permanentFailuresDoNotRetry() {
        val auth = outcome(JobStatus.FAILED, BackupFailure(BackupErrorCode.STORAGE, storageReason = StorageException.Reason.AUTH))
        assertEquals(WorkDecision.FAILURE, BackupResultMapper.decide(auth, 0))
        val key = outcome(JobStatus.FAILED, BackupFailure(BackupErrorCode.ENCRYPTION_KEY_MISSING))
        assertEquals(WorkDecision.FAILURE, BackupResultMapper.decide(key, 0))
        assertTrue(BackupResultMapper.needsUserAction(key))
        assertFalse(BackupResultMapper.needsUserAction(auth))
    }

    @Test
    fun retriesStopAfterMaxAttempts() {
        val timeout = outcome(JobStatus.FAILED, BackupFailure(BackupErrorCode.STORAGE, storageReason = StorageException.Reason.TIMEOUT))
        assertEquals(WorkDecision.RETRY, BackupResultMapper.decide(timeout, 3, maxAttempts = 5))
        assertEquals(WorkDecision.FAILURE, BackupResultMapper.decide(timeout, 4, maxAttempts = 5))
    }
}
