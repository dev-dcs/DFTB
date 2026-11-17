package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class RetryPolicyTest {

    private val network = StorageException(StorageException.Reason.NETWORK, "reset")
    private val auth = StorageException(StorageException.Reason.AUTH, "denied")

    @Test
    fun `backoff is exponential and capped`() {
        val policy = RetryPolicy(maxAttempts = 6, initialDelayMillis = 2_000, multiplier = 2.0, maxDelayMillis = 10_000)
        assertEquals(0L, policy.delayBefore(1))
        assertEquals(2_000L, policy.delayBefore(2))
        assertEquals(4_000L, policy.delayBefore(3))
        assertEquals(8_000L, policy.delayBefore(4))
        assertEquals(10_000L, policy.delayBefore(5))
        assertEquals(10_000L, policy.delayBefore(40))
    }

    @Test
    fun `default allows three attempts for transient failures only`() {
        val policy = RetryPolicy.DEFAULT
        assertTrue(policy.shouldRetry(1, network))
        assertTrue(policy.shouldRetry(2, network))
        assertFalse(policy.shouldRetry(3, network))
        assertFalse(policy.shouldRetry(1, auth))
        assertFalse(policy.withoutRetries().shouldRetry(1, network))
    }

    @Test
    fun `retryability is found in the cause chain`() {
        assertTrue(RetryPolicy.isRetryable(IOException("wrapped", network)))
        assertTrue(RetryPolicy.isRetryable(DestinationIOException(retryable = true, IOException("broken pipe"))))
        assertFalse(RetryPolicy.isRetryable(DestinationIOException(retryable = false, IOException("disk"))))
        assertFalse(RetryPolicy.isRetryable(IOException("source read error")))
        assertFalse(RetryPolicy.isRetryable(CancellationException("stop")))
    }

    @Test
    fun `destination failure is located for job-level aborts`() {
        val wrapped = IOException("tar failed", auth)
        assertSame(auth, RetryPolicy.destinationFailure(wrapped))
        assertNull(RetryPolicy.destinationFailure(IOException("source")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero attempts is rejected`() {
        RetryPolicy(maxAttempts = 0)
    }
}
