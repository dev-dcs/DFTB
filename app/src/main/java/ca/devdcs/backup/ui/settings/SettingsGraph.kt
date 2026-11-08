package ca.devdcs.backup.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import ca.devdcs.backup.ui.navigation.DftbDestination

/**
 * Registers every settings route (see the contract in FeatureGraphs.kt): the root
 * [DftbDestination.Settings] (top-level, shown in the shell's bottom bar / rail) and
 * [DftbDestination.SettingsSection] for a single section by key. Other features deep-link with
 * `navController.navigate(SettingsSectionKey.MODE.destination)`.
 */
fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    val actions = SettingsActions(
        openSection = { section -> navController.navigate(section.destination) { launchSingleTop = true } },
        openDiscovery = { navController.navigate(DftbDestination.Discovery()) { launchSingleTop = true } },
        openRcloneSetup = { navController.navigate(DftbDestination.RcloneSetup()) { launchSingleTop = true } },
        openRcloneConfig = { navController.navigate(DftbDestination.RcloneConfigEditor) { launchSingleTop = true } },
        openLicenses = { navController.navigate(DftbDestination.Licenses) { launchSingleTop = true } },
        openSystemInfo = { navController.navigate(DftbDestination.SystemInfo) { launchSingleTop = true } },
    )

    composable<DftbDestination.Settings> {
        SettingsScreen(actions = actions)
    }
    composable<DftbDestination.SettingsSection> { entry ->
        SettingsSectionScreen(
            sectionKey = entry.toRoute<DftbDestination.SettingsSection>().section,
            actions = actions,
            onBack = { navController.popBackStack() },
        )
    }
}
