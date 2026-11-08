package ca.devdcs.backup.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import ca.devdcs.backup.ui.destinations.DestinationEditorScreen
import ca.devdcs.backup.ui.destinations.DestinationsScreen
import ca.devdcs.backup.ui.discovery.DiscoveryScreen
import ca.devdcs.backup.ui.licenses.LicensesScreen
import ca.devdcs.backup.ui.onboarding.OnboardingScreen
import ca.devdcs.backup.ui.profiles.profilesGraph
import ca.devdcs.backup.ui.rclone.RcloneConfigEditorScreen
import ca.devdcs.backup.ui.rclone.RcloneSetupScreen
import ca.devdcs.backup.ui.restore.restoreGraph
import ca.devdcs.backup.ui.settings.settingsGraph
import ca.devdcs.backup.ui.shell.DashboardActions
import ca.devdcs.backup.ui.shell.DashboardScreen
import ca.devdcs.backup.ui.shell.JobMonitorScreen
import ca.devdcs.backup.ui.shell.TopLevelDestination
import ca.devdcs.backup.ui.shell.navigateToTopLevel
import ca.devdcs.backup.ui.system.SystemInfoScreen

/**
 * The navigation graph. [startDestination] is decided by the caller from the persisted
 * onboarding flag so the graph itself stays free of preference lookups. The shell
 * ([ca.devdcs.backup.ui.shell.DftbShell]) owns [navController] because its bar/rail reads the
 * back stack; feature packages contribute their routes through their graph extensions
 * (see FeatureGraphs.kt).
 */
@Composable
fun DftbNavHost(
    startDestination: DftbDestination,
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<DftbDestination.Onboarding> {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(DftbDestination.Dashboard) {
                        popUpTo<DftbDestination.Onboarding> { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<DftbDestination.Dashboard> {
            DashboardScreen(
                actions = DashboardActions(
                    openJob = { jobId -> navController.navigate(DftbDestination.JobMonitor(jobId)) { launchSingleTop = true } },
                    openProfile = { id -> navController.navigate(DftbDestination.ProfileWizard(profileId = id)) { launchSingleTop = true } },
                    newProfile = { navController.navigate(DftbDestination.ProfileWizard()) { launchSingleTop = true } },
                    openProfiles = { navController.navigateToTopLevel(TopLevelDestination.PROFILES) },
                    openSchedules = { navController.navigate(DftbDestination.Schedules) { launchSingleTop = true } },
                    openRestore = { navController.navigateToTopLevel(TopLevelDestination.RESTORE) },
                    openDestinations = { navController.navigateToTopLevel(TopLevelDestination.DESTINATIONS) },
                    addDestination = { navController.navigate(DftbDestination.DestinationEditor()) { launchSingleTop = true } },
                    openModeSettings = {
                        navController.navigate(DftbDestination.SettingsSection(MODE_SETTINGS_SECTION)) { launchSingleTop = true }
                    },
                    openSystemInfo = { navController.navigate(DftbDestination.SystemInfo) { launchSingleTop = true } },
                    openDiscovery = { navController.navigate(DftbDestination.Discovery()) { launchSingleTop = true } },
                    openLicenses = { navController.navigate(DftbDestination.Licenses) { launchSingleTop = true } },
                ),
            )
        }
        composable<DftbDestination.JobMonitor> {
            JobMonitorScreen(onBack = { navController.popBackStack() })
        }
        composable<DftbDestination.SystemInfo> {
            SystemInfoScreen(
                onBack = { navController.popBackStack() },
                onOpenLicenses = {
                    navController.navigate(DftbDestination.Licenses) { launchSingleTop = true }
                },
                onOpenDestinations = { navController.navigateToTopLevel(TopLevelDestination.DESTINATIONS) },
                onOpenDiscovery = {
                    navController.navigate(DftbDestination.Discovery()) { launchSingleTop = true }
                },
            )
        }
        composable<DftbDestination.Licenses> {
            LicensesScreen(onBack = { navController.popBackStack() })
        }
        composable<DftbDestination.Destinations> {
            DestinationsScreen(
                // Top-level tab (bar/rail): no up arrow.
                onBack = null,
                onAdd = { navController.navigate(DftbDestination.DestinationEditor()) },
                onEdit = { id -> navController.navigate(DftbDestination.DestinationEditor(destinationId = id)) },
                onOpenRcloneSetup = { navController.navigate(DftbDestination.RcloneSetup()) { launchSingleTop = true } },
                onOpenRcloneConfig = { navController.navigate(DftbDestination.RcloneConfigEditor) { launchSingleTop = true } },
            )
        }
        composable<DftbDestination.DestinationEditor> {
            DestinationEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onOpenRcloneSetup = { navController.navigate(DftbDestination.RcloneSetup()) { launchSingleTop = true } },
                onOpenRcloneConfig = { navController.navigate(DftbDestination.RcloneConfigEditor) { launchSingleTop = true } },
            )
        }
        composable<DftbDestination.RcloneSetup> {
            RcloneSetupScreen(
                onBack = { navController.popBackStack() },
                onOpenConfigEditor = { navController.navigate(DftbDestination.RcloneConfigEditor) { launchSingleTop = true } },
                onDestinationCreated = {
                    // Back to the Destinations list whether setup was opened from it or from an editor.
                    if (!navController.popBackStack<DftbDestination.Destinations>(inclusive = false)) {
                        navController.navigateToTopLevel(TopLevelDestination.DESTINATIONS)
                    }
                },
            )
        }
        composable<DftbDestination.RcloneConfigEditor> {
            RcloneConfigEditorScreen(onBack = { navController.popBackStack() })
        }
        composable<DftbDestination.Discovery> { entry ->
            DiscoveryScreen(
                onBack = { navController.popBackStack() },
                initialTab = entry.toRoute<DftbDestination.Discovery>().initialTab,
            )
        }

        profilesGraph(navController)
        restoreGraph(navController)
        settingsGraph(navController)
    }
}

/** [DftbDestination.SettingsSection] key of the operating-mode page (ui/settings). */
private const val MODE_SETTINGS_SECTION = "mode"
