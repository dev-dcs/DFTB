package ca.devdcs.backup.core.environment

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** What a check body reports; the runner adds identity, timing and failure handling. */
data class CheckOutcome(
    val status: CheckStatus,
    val detail: String? = null,
    @StringRes val hintRes: Int? = null,
    val action: DiagnosticAction? = null,
)

/**
 * Executes one check body so that it can never throw and never hang the report.
 *
 * The body runs in its own coroutine on the IO dispatcher and the caller only awaits it under
 * [withTimeoutOrNull]. That matters for blocking calls such as `Shell.getShell()` waiting on a
 * superuser prompt: cancellation cannot interrupt them, but the await is cancellable, so the report
 * continues with a FAIL row while the orphaned thread finishes (or is answered) in the background.
 */
@Singleton
class CheckRunner @Inject constructor(
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun run(
        id: String,
        @StringRes titleRes: Int,
        required: Boolean,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        failureAction: DiagnosticAction? = null,
        body: suspend () -> CheckOutcome,
    ): DiagnosticCheck {
        val deferred = applicationScope.async(ioDispatcher) { runCatching { body() } }
        val result = try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
        } finally {
            if (!deferred.isCompleted) deferred.cancel()
        }
        if (result == null) {
            DftbLog.w(TAG, "Check '$id' timed out after $timeoutMillis ms")
            return DiagnosticCheck(
                id = id,
                titleRes = titleRes,
                status = CheckStatus.FAIL,
                detail = null,
                hintRes = R.string.diag_check_timed_out_hint,
                required = required,
                action = failureAction,
            )
        }
        return result.fold(
            onSuccess = { outcome ->
                DiagnosticCheck(
                    id = id,
                    titleRes = titleRes,
                    status = outcome.status,
                    detail = outcome.detail,
                    hintRes = outcome.hintRes,
                    required = required,
                    action = outcome.action,
                )
            },
            onFailure = { error ->
                DftbLog.w(TAG, "Check '$id' threw", error)
                DiagnosticCheck(
                    id = id,
                    titleRes = titleRes,
                    status = CheckStatus.FAIL,
                    detail = describe(error),
                    hintRes = R.string.diag_check_failed_hint,
                    required = required,
                    action = failureAction,
                )
            },
        )
    }

    private fun describe(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }

    companion object {
        private const val TAG = "Diagnostics"
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        /** Shell commands on an already-open shell; generous for slow eMMC under load. */
        const val SHELL_COMMAND_TIMEOUT_MILLIS = 10_000L
        /** Opening the root shell: the superuser prompt may wait for the user. */
        const val ROOT_SHELL_TIMEOUT_MILLIS = 20_000L
    }
}
