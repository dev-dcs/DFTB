package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.storage.StorageException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Raised (by the engine's destination guard stream) when writing to or reading from the
 * destination failed with a raw [IOException]. Exists so a destination fault can be told apart
 * from a source read error deep inside an archive writer, which only sees IOExceptions.
 */
class DestinationIOException(val retryable: Boolean, cause: IOException) :
    IOException(cause.message ?: cause.javaClass.simpleName, cause)

/**
 * Exponential backoff for transient destination failures. Exists as a pure value so the policy
 * (3 attempts, 2 s → 4 s, capped) is unit-tested and the engine loop stays trivial.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 2_000L,
    val multiplier: Double = 2.0,
    val maxDelayMillis: Long = 30_000L,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(initialDelayMillis >= 0 && maxDelayMillis >= 0) { "delays must be >= 0" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
    }

    /** Delay to wait before attempt number [nextAttempt] (2 = first retry). */
    fun delayBefore(nextAttempt: Int): Long {
        if (nextAttempt <= 1) return 0L
        var delay = initialDelayMillis.toDouble()
        repeat(nextAttempt - 2) { delay *= multiplier }
        return if (delay >= maxDelayMillis.toDouble()) maxDelayMillis else delay.toLong()
    }

    /** True when [failedAttempt] may be followed by another attempt for [error]. */
    fun shouldRetry(failedAttempt: Int, error: Throwable): Boolean = failedAttempt < maxAttempts && isRetryable(error)

    /** Single-shot variant used where retries happen at another level. */
    fun withoutRetries(): RetryPolicy = copy(maxAttempts = 1)

    companion object {
        val DEFAULT = RetryPolicy()

        /**
         * Transient destination failure anywhere in the cause chain: a retryable
         * [StorageException.Reason] or a [DestinationIOException] flagged retryable. Cancellation
         * is never retryable.
         */
        fun isRetryable(error: Throwable): Boolean {
            if (error is CancellationException) return false
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                when (current) {
                    is StorageException -> return current.reason.isRetryable
                    is DestinationIOException -> return current.retryable
                }
                current = current.cause
                depth++
            }
            return false
        }

        /** The destination-side failure in the chain, if any (so the job can abort cleanly). */
        fun destinationFailure(error: Throwable): Throwable? {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (current is StorageException || current is DestinationIOException) return current
                current = current.cause
                depth++
            }
            return null
        }

        private const val MAX_CAUSE_DEPTH = 16
    }
}
