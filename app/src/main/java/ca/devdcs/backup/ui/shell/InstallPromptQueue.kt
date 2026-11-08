package ca.devdcs.backup.ui.shell

/**
 * Bookkeeping for the system install prompts the app restorer is waiting on (by PackageInstaller
 * session id). [launched] holds every session already shown once, [inFlight] the one currently on
 * screen, [leftApp] whether the Activity paused since that launch, and [forced] a session the user
 * asked to see again.
 */
data class InstallPromptState(
    val launched: Set<Int> = emptySet(),
    val inFlight: Int? = null,
    val leftApp: Boolean = false,
    val forced: Int? = null,
)

/**
 * Pure state machine that shows pending install confirmations one at a time.
 *
 * Exists because Android lets only a resumed Activity start the install prompt and the user may
 * leave it unanswered: each session is launched automatically at most once, the next one waits
 * until the user is back in DFTB, and a session that is still pending after it was shown is
 * surfaced as "waiting for you" instead of being relaunched in a loop.
 */
object InstallPromptQueue {

    /** The session to launch now, or null (not resumed, one already on screen, or nothing new). */
    fun nextToLaunch(pending: List<Int>, state: InstallPromptState, resumed: Boolean): Int? {
        if (!resumed || state.inFlight != null) return null
        state.forced?.let { if (it in pending) return it }
        return pending.firstOrNull { it !in state.launched }
    }

    /** Sessions already shown once and still unanswered; the UI offers to show them again. */
    fun awaiting(pending: List<Int>, state: InstallPromptState): List<Int> =
        pending.filter { it in state.launched && it != state.inFlight && it != state.forced }

    fun onLaunched(state: InstallPromptState, sessionId: Int): InstallPromptState =
        state.copy(launched = state.launched + sessionId, inFlight = sessionId, leftApp = false, forced = null)

    /** Starting the prompt threw: count it as shown so it lands in [awaiting] rather than looping. */
    fun onLaunchFailed(state: InstallPromptState, sessionId: Int): InstallPromptState =
        state.copy(launched = state.launched + sessionId, inFlight = null, leftApp = false, forced = null)

    fun onPaused(state: InstallPromptState): InstallPromptState =
        if (state.inFlight != null) state.copy(leftApp = true) else state

    /** Back in DFTB after the prompt covered it: the next session may go. */
    fun onResumed(state: InstallPromptState): InstallPromptState =
        if (state.inFlight != null && state.leftApp) state.copy(inFlight = null, leftApp = false) else state

    /** The prompt never covered the Activity (nothing paused): stop waiting for it. */
    fun onLaunchTimedOut(state: InstallPromptState, sessionId: Int): InstallPromptState =
        if (state.inFlight == sessionId && !state.leftApp) state.copy(inFlight = null) else state

    /** User tapped "review" on a waiting session. */
    fun requestRelaunch(state: InstallPromptState, sessionId: Int): InstallPromptState = state.copy(forced = sessionId)

    /** Forget sessions the restorer no longer waits on (answered, timed out, abandoned). */
    fun onPendingChanged(state: InstallPromptState, pending: List<Int>): InstallPromptState {
        val live = pending.toSet()
        return state.copy(
            launched = state.launched intersect live,
            inFlight = state.inFlight?.takeIf { it in live },
            leftApp = state.leftApp && state.inFlight in live,
            forced = state.forced?.takeIf { it in live },
        )
    }
}
