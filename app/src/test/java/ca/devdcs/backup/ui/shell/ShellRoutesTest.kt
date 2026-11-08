package ca.devdcs.backup.ui.shell

import ca.devdcs.backup.ui.navigation.DftbDestination
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellRoutesTest {

    private fun name(serializer: KSerializer<*>) = serializer.descriptor.serialName

    private val dashboard = name(DftbDestination.Dashboard.serializer())
    private val profiles = name(DftbDestination.Profiles.serializer())
    private val restoreHome = name(DftbDestination.RestoreHome.serializer())
    private val destinations = name(DftbDestination.Destinations.serializer())
    private val settings = name(DftbDestination.Settings.serializer())
    private val onboarding = name(DftbDestination.Onboarding.serializer())
    private val systemInfo = name(DftbDestination.SystemInfo.serializer())

    // Route patterns as Navigation generates them for routes with arguments.
    private val wizard = name(DftbDestination.ProfileWizard.serializer()) + "?profileId={profileId}&step={step}"
    private val jobMonitor = name(DftbDestination.JobMonitor.serializer()) + "?jobId={jobId}"
    private val restoreBackup = name(DftbDestination.RestoreBackup.serializer()) + "/{destinationId}/{jobDir}"
    private val settingsSection = name(DftbDestination.SettingsSection.serializer()) + "/{section}"
    private val editor = name(DftbDestination.DestinationEditor.serializer()) + "?destinationId={destinationId}"

    @Test
    fun matchesExactNameAndArgumentPatternsOnly() {
        assertTrue(ShellRoutes.matches(settings, settings))
        assertTrue(ShellRoutes.matches(settingsSection, name(DftbDestination.SettingsSection.serializer())))
        assertTrue(ShellRoutes.matches(jobMonitor, name(DftbDestination.JobMonitor.serializer())))
        // "Settings" must not match "SettingsSection".
        assertFalse(ShellRoutes.matches(settingsSection, settings))
        assertFalse(ShellRoutes.matches(null, settings))
    }

    @Test
    fun topLevelForRecognisesTheFiveSectionRoots() {
        assertEquals(TopLevelDestination.DASHBOARD, ShellRoutes.topLevelFor(dashboard))
        assertEquals(TopLevelDestination.PROFILES, ShellRoutes.topLevelFor(profiles))
        assertEquals(TopLevelDestination.RESTORE, ShellRoutes.topLevelFor(restoreHome))
        assertEquals(TopLevelDestination.DESTINATIONS, ShellRoutes.topLevelFor(destinations))
        assertEquals(TopLevelDestination.SETTINGS, ShellRoutes.topLevelFor(settings))
        assertNull(ShellRoutes.topLevelFor(settingsSection))
        assertNull(ShellRoutes.topLevelFor(wizard))
        assertNull(ShellRoutes.topLevelFor(onboarding))
    }

    @Test
    fun selectionFollowsCurrentRootThenOwnerThenNearestRoot() {
        assertEquals(TopLevelDestination.PROFILES, ShellRoutes.selectedTopLevel(listOf(null, dashboard, profiles)))
        // Secondary screens owned by a section highlight it even when pushed from the dashboard.
        assertEquals(TopLevelDestination.PROFILES, ShellRoutes.selectedTopLevel(listOf(null, dashboard, wizard)))
        assertEquals(TopLevelDestination.RESTORE, ShellRoutes.selectedTopLevel(listOf(null, dashboard, restoreHome, restoreBackup)))
        assertEquals(TopLevelDestination.SETTINGS, ShellRoutes.selectedTopLevel(listOf(null, dashboard, settings, settingsSection)))
        assertEquals(TopLevelDestination.DESTINATIONS, ShellRoutes.selectedTopLevel(listOf(null, dashboard, editor)))
        // Unowned secondary screens keep the nearest section below them.
        assertEquals(TopLevelDestination.DASHBOARD, ShellRoutes.selectedTopLevel(listOf(null, dashboard, jobMonitor)))
        assertEquals(TopLevelDestination.SETTINGS, ShellRoutes.selectedTopLevel(listOf(null, dashboard, settings, systemInfo)))
        assertNull(ShellRoutes.selectedTopLevel(listOf(null, onboarding)))
        assertNull(ShellRoutes.selectedTopLevel(emptyList()))
    }

    @Test
    fun compactShowsBarOnSectionRootsOnly() {
        assertEquals(ShellNavChrome.BOTTOM_BAR, ShellRoutes.chromeFor(ShellWidth.COMPACT, dashboard))
        assertEquals(ShellNavChrome.BOTTOM_BAR, ShellRoutes.chromeFor(ShellWidth.COMPACT, settings))
        assertEquals(ShellNavChrome.NONE, ShellRoutes.chromeFor(ShellWidth.COMPACT, jobMonitor))
        assertEquals(ShellNavChrome.NONE, ShellRoutes.chromeFor(ShellWidth.COMPACT, wizard))
    }

    @Test
    fun widerWindowsKeepTheRailExceptDuringOnboarding() {
        assertEquals(ShellNavChrome.RAIL, ShellRoutes.chromeFor(ShellWidth.MEDIUM, dashboard))
        assertEquals(ShellNavChrome.RAIL, ShellRoutes.chromeFor(ShellWidth.EXPANDED, jobMonitor))
        assertEquals(ShellNavChrome.NONE, ShellRoutes.chromeFor(ShellWidth.EXPANDED, onboarding))
        assertEquals(ShellNavChrome.NONE, ShellRoutes.chromeFor(ShellWidth.COMPACT, onboarding))
        assertEquals(ShellNavChrome.NONE, ShellRoutes.chromeFor(ShellWidth.MEDIUM, null))
    }

    @Test
    fun tapOnCurrentRootIsIgnoredInsideSectionPopsElsewhereSwitches() {
        assertEquals(TopLevelTapAction.NONE, ShellRoutes.tapAction(TopLevelDestination.PROFILES, profiles, TopLevelDestination.PROFILES))
        assertEquals(TopLevelTapAction.POP_TO_ROOT, ShellRoutes.tapAction(TopLevelDestination.PROFILES, wizard, TopLevelDestination.PROFILES))
        assertEquals(TopLevelTapAction.SWITCH, ShellRoutes.tapAction(TopLevelDestination.RESTORE, wizard, TopLevelDestination.PROFILES))
        assertEquals(TopLevelTapAction.SWITCH, ShellRoutes.tapAction(TopLevelDestination.DASHBOARD, settings, TopLevelDestination.SETTINGS))
    }

    @Test
    fun widthBucketsUseMaterialBreakpoints() {
        assertEquals(ShellWidth.COMPACT, ShellWidth.fromWidthDp(411f))
        assertEquals(ShellWidth.MEDIUM, ShellWidth.fromWidthDp(600f))
        assertEquals(ShellWidth.MEDIUM, ShellWidth.fromWidthDp(839f))
        assertEquals(ShellWidth.EXPANDED, ShellWidth.fromWidthDp(840f))
    }

    @Test
    fun barOrderIsDashboardProfilesRestoreDestinationsSettings() {
        assertEquals(
            listOf(
                DftbDestination.Dashboard,
                DftbDestination.Profiles,
                DftbDestination.RestoreHome,
                DftbDestination.Destinations,
                DftbDestination.Settings,
            ),
            TopLevelDestination.entries.map { it.route },
        )
    }
}
