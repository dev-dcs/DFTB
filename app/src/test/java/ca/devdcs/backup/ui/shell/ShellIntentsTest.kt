package ca.devdcs.backup.ui.shell

import ca.devdcs.backup.core.schedule.ScheduleNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellIntentsTest {

    @Test
    fun profileAlertOpensTheProfileOrTheDashboard() {
        assertEquals(
            ShellDeepLink.OpenProfile("p1"),
            ShellIntents.parse(ScheduleNotifier.ACTION_OPEN_PROFILE, profileId = "p1", jobId = null, launchedFromHistory = false),
        )
        assertEquals(
            ShellDeepLink.OpenDashboard,
            ShellIntents.parse(ScheduleNotifier.ACTION_OPEN_PROFILE, profileId = " ", jobId = null, launchedFromHistory = false),
        )
    }

    @Test
    fun jobLinksAndServiceNotificationsOpenTheMonitor() {
        assertEquals(ShellDeepLink.OpenJob("j1"), ShellIntents.parse(ShellIntents.ACTION_OPEN_JOB, null, "j1", false))
        assertEquals(ShellDeepLink.OpenJob(null), ShellIntents.parse(ShellIntents.ACTION_OPEN_JOB, null, null, false))
        // The foreground service's notifications start MainActivity with no action.
        assertEquals(ShellDeepLink.OpenJob(null), ShellIntents.parse(null, null, null, false))
    }

    @Test
    fun launcherUsbAndRecentsAreNotDeepLinks() {
        assertNull(ShellIntents.parse("android.intent.action.MAIN", null, null, false))
        assertNull(ShellIntents.parse("android.hardware.usb.action.USB_DEVICE_ATTACHED", null, null, false))
        assertNull(ShellIntents.parse(ScheduleNotifier.ACTION_OPEN_PROFILE, "p1", null, launchedFromHistory = true))
    }

    @Test
    fun inboxKeepsTheNewestLinkAndConsumesOnlyThatOne() {
        val inbox = DeepLinkInbox()
        val first = ShellDeepLink.OpenProfile("a")
        val second = ShellDeepLink.OpenJob("j")
        inbox.post(first)
        inbox.post(second)
        inbox.consume(first)
        assertEquals(second, inbox.pending.value)
        inbox.consume(second)
        assertNull(inbox.pending.value)
    }
}
