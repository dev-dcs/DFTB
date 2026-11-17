package ca.devdcs.backup.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPromptQueueTest {

    private val q = InstallPromptQueue

    @Test
    fun launchesOnlyWhileResumedAndOneAtATime() {
        val pending = listOf(1, 2)
        var state = InstallPromptState()
        assertNull(q.nextToLaunch(pending, state, resumed = false))
        assertEquals(1, q.nextToLaunch(pending, state, resumed = true))

        state = q.onLaunched(state, 1)
        assertNull("one prompt at a time", q.nextToLaunch(pending, state, resumed = true))

        // The prompt covers DFTB, the user answers, DFTB resumes: the next one may go.
        state = q.onPaused(state)
        state = q.onPendingChanged(state, listOf(2))
        state = q.onResumed(state)
        assertEquals(2, q.nextToLaunch(listOf(2), state, resumed = true))
    }

    @Test
    fun unansweredPromptIsNotRelaunchedAutomatically() {
        var state = q.onLaunched(InstallPromptState(), 7)
        state = q.onPaused(state)
        state = q.onResumed(state)
        // Still pending (user backed out without deciding): offered, not looped.
        assertNull(q.nextToLaunch(listOf(7), state, resumed = true))
        assertEquals(listOf(7), q.awaiting(listOf(7), state))

        state = q.requestRelaunch(state, 7)
        assertEquals(7, q.nextToLaunch(listOf(7), state, resumed = true))
        assertTrue(q.awaiting(listOf(7), state).isEmpty())
        state = q.onLaunched(state, 7)
        assertNull(state.forced)
    }

    @Test
    fun resumeWithoutPauseDoesNotReleaseTheQueueButTimeoutDoes() {
        var state = q.onLaunched(InstallPromptState(), 3)
        state = q.onResumed(state)
        assertEquals(3, state.inFlight)
        state = q.onLaunchTimedOut(state, 3)
        assertNull(state.inFlight)
        assertEquals(listOf(3), q.awaiting(listOf(3), state))
    }

    @Test
    fun timeoutIgnoredOnceThePromptCoveredTheActivity() {
        var state = q.onLaunched(InstallPromptState(), 3)
        state = q.onPaused(state)
        state = q.onLaunchTimedOut(state, 3)
        assertEquals(3, state.inFlight)
    }

    @Test
    fun failedLaunchIsOfferedInsteadOfRetriedForever() {
        val state = q.onLaunchFailed(InstallPromptState(), 4)
        assertNull(state.inFlight)
        assertNull(q.nextToLaunch(listOf(4, 5), state, resumed = true)?.takeIf { it == 4 })
        assertEquals(5, q.nextToLaunch(listOf(4, 5), state, resumed = true))
        assertEquals(listOf(4), q.awaiting(listOf(4, 5), state))
    }

    @Test
    fun resolvedSessionsAreForgotten() {
        var state = q.onLaunched(InstallPromptState(), 1)
        state = q.requestRelaunch(q.onLaunchFailed(state, 2), 2)
        state = q.onPendingChanged(state, emptyList())
        assertEquals(InstallPromptState(), state)
    }
}
