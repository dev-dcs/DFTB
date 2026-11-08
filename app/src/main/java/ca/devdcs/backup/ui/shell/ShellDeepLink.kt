package ca.devdcs.backup.ui.shell

import ca.devdcs.backup.core.schedule.ScheduleNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where a notification (or another app-level intent) asks the UI to go. */
sealed interface ShellDeepLink {
    /** A profile alert (passphrase needed, run failed) or a scheduled run's foreground notification. */
    data class OpenProfile(val profileId: String) : ShellDeepLink

    /** A job notification; null [jobId] = whichever jobs are running. */
    data class OpenJob(val jobId: String?) : ShellDeepLink

    /** An alert about no particular profile (e.g. an interrupted restore): the dashboard shows it. */
    data object OpenDashboard : ShellDeepLink
}

/**
 * Turns MainActivity intents into [ShellDeepLink]s. Pure (strings in, link out) so the rules are
 * unit-tested without Android:
 * - [ScheduleNotifier.ACTION_OPEN_PROFILE] with a profile id opens that profile, without one the dashboard;
 * - [ACTION_OPEN_JOB] opens the job monitor;
 * - an intent with no action can only come from the job foreground-service notifications (the
 * launcher always sends ACTION_MAIN), so it opens the job monitor;
 * - relaunches from Recents replay the earlier build intent and are ignored.
 */
object ShellIntents {

    /** Explicit job deep link for notification builders (see the report's `requests`). */
    const val ACTION_OPEN_JOB = "ca.devdcs.backup.action.OPEN_JOB"
    const val EXTRA_JOB_ID = "ca.devdcs.backup.extra.JOB_ID"

    fun parse(
        action: String?,
        profileId: String?,
        jobId: String?,
        launchedFromHistory: Boolean,
    ): ShellDeepLink? {
        if (launchedFromHistory) return null
        return when (action) {
            ScheduleNotifier.ACTION_OPEN_PROFILE ->
                profileId?.takeIf { it.isNotBlank() }?.let { ShellDeepLink.OpenProfile(it) } ?: ShellDeepLink.OpenDashboard
            ACTION_OPEN_JOB -> ShellDeepLink.OpenJob(jobId?.takeIf { it.isNotBlank() })
            null -> ShellDeepLink.OpenJob(jobId?.takeIf { it.isNotBlank() })
            else -> null
        }
    }
}

/**
 * Hand-off point between MainActivity (which receives intents before any composable exists) and
 * the shell (which can only navigate once the graph is up and onboarding is done). Holds at most
 * one pending link; a newer intent replaces an unhandled older one.
 */
@Singleton
class DeepLinkInbox @Inject constructor() {

    private val _pending = MutableStateFlow<ShellDeepLink?>(null)
    val pending: StateFlow<ShellDeepLink?> = _pending.asStateFlow()

    fun post(link: ShellDeepLink) {
        _pending.value = link
    }

    /** Clears [link] if it is still the pending one (a newer post wins). */
    fun consume(link: ShellDeepLink) {
        _pending.compareAndSet(link, null)
    }
}
