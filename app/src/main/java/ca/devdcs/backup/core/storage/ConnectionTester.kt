package ca.devdcs.backup.core.storage

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.repository.DestinationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Test connection" for the destination editor and the dashboard's health check.
 *
 * Exists so every protocol is probed the same way — connect, list the base, read capacity — and
 * the UI gets one [ConnectionTestResult] with a [StorageException.Reason] it can explain, instead
 * of each screen catching protocol exceptions. A successful probe refreshes the destination's
 * cached capacity via [DestinationRepository.recordVerification].
 *
 * The [timeoutMillis] bound applies at suspension points; backends must still set their own
 * socket timeouts so a blocking call cannot outlive it by much.
 */
@Singleton
class ConnectionTester internal constructor(
    private val registry: StorageRegistry,
    private val repository: DestinationRepository,
    private val timeoutMillis: Long,
    private val clock: () -> Long,
    private val nanoTime: () -> Long,
) {

    @Inject
    constructor(registry: StorageRegistry, repository: DestinationRepository) :
        this(registry, repository, DEFAULT_TIMEOUT_MILLIS, System::currentTimeMillis, System::nanoTime)

    suspend fun test(destination: Destination): ConnectionTestResult {
        val started = nanoTime()
        fun elapsed() = ((nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

        val outcome: Result<SpaceInfo?>? = withTimeoutOrNull(timeoutMillis) {
            try {
                Result.success<SpaceInfo?>(
                    registry.withSession(destination) { session ->
                        session.list("")
                        session.spaceInfo()
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                Result.failure<SpaceInfo?>(e)
            } catch (e: Exception) {
                // A backend leaked a raw exception; still report it rather than crash the UI.
                Result.failure<SpaceInfo?>(StorageException(StorageException.Reason.PROTOCOL, e.message ?: e.javaClass.simpleName, e))
            }
        }
        val latency = elapsed()

        if (outcome == null) {
            DftbLog.w(TAG, "Connection test timed out for ${destination.id} after ${latency}ms")
            return ConnectionTestResult(
                ok = false,
                failure = StorageException.Reason.TIMEOUT,
                detail = "No response within ${timeoutMillis / 1000}s",
                latencyMillis = latency,
            )
        }
        val error = outcome.exceptionOrNull() as StorageException?
        if (error != null) {
            DftbLog.w(TAG, "Connection test failed for ${destination.id}: ${error.reason}")
            return ConnectionTestResult(ok = false, failure = error.reason, detail = error.message, latencyMillis = latency)
        }
        val space = outcome.getOrNull()
        try {
            repository.recordVerification(destination.id, clock(), space?.freeBytes, space?.totalBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Could not record verification for ${destination.id}", e)
        }
        return ConnectionTestResult(ok = true, space = space, latencyMillis = latency)
    }

    companion object {
        private const val TAG = "ConnectionTester"
        const val DEFAULT_TIMEOUT_MILLIS: Long = 20_000L
    }
}

/**
 * User-facing explanation for a [StorageException.Reason] (strings_storage.xml). Lives next to
 * [ConnectionTester] because its result is the main place reasons reach the UI.
 */
@StringRes
fun StorageException.Reason.labelRes(): Int = when (this) {
    StorageException.Reason.AUTH -> R.string.storage_reason_auth
    StorageException.Reason.HOST_KEY -> R.string.storage_reason_host_key
    StorageException.Reason.NETWORK -> R.string.storage_reason_network
    StorageException.Reason.TIMEOUT -> R.string.storage_reason_timeout
    StorageException.Reason.NOT_FOUND -> R.string.storage_reason_not_found
    StorageException.Reason.PERMISSION -> R.string.storage_reason_permission
    StorageException.Reason.NO_SPACE -> R.string.storage_reason_no_space
    StorageException.Reason.UNSUPPORTED -> R.string.storage_reason_unsupported
    StorageException.Reason.MISCONFIGURED -> R.string.storage_reason_misconfigured
    StorageException.Reason.TOOL_MISSING -> R.string.storage_reason_tool_missing
    StorageException.Reason.PROTOCOL -> R.string.storage_reason_protocol
}
