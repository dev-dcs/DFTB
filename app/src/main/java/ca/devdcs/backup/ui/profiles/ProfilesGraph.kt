package ca.devdcs.backup.ui.profiles

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ca.devdcs.backup.ui.navigation.DftbDestination

/**
 * Registers the profiles feature's routes ([DftbDestination.Profiles], [DftbDestination.ProfileWizard],
 * [DftbDestination.Schedules]) per the navigation contract (see FeatureGraphs.kt). Exists
 * so the app shell wires the whole feature with one call and never needs to know its screens.
 * Cross-feature links go only through [DftbDestination] routes: the destination editor (add a
 * destination from the wizard) and the job monitor (after "back up now").
 */
fun NavGraphBuilder.profilesGraph(navController: NavHostController) {
    composable<DftbDestination.Profiles> {
        ProfilesScreen(
            onCreate = { navController.navigate(DftbDestination.ProfileWizard()) },
            onEdit = { id, step -> navController.navigate(DftbDestination.ProfileWizard(profileId = id, step = step.ordinal)) },
            onOpenSchedules = { navController.navigate(DftbDestination.Schedules) { launchSingleTop = true } },
            onOpenJobMonitor = { jobId -> navController.navigate(DftbDestination.JobMonitor(jobId)) { launchSingleTop = true } },
        )
    }
    composable<DftbDestination.ProfileWizard> {
        ProfileWizardScreen(
            onExit = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
            onSavedAndRunning = { jobId ->
                navController.navigate(DftbDestination.JobMonitor(jobId)) {
                    popUpTo<DftbDestination.ProfileWizard> { inclusive = true }
                    launchSingleTop = true
                }
            },
            onAddDestination = { navController.navigate(DftbDestination.DestinationEditor()) },
        )
    }
    composable<DftbDestination.Schedules> {
        SchedulesScreen(
            onBack = { navController.popBackStack() },
            onEditSchedule = { id ->
                navController.navigate(DftbDestination.ProfileWizard(profileId = id, step = WizardStep.SCHEDULE.ordinal))
            },
            onOpenJobMonitor = { jobId -> navController.navigate(DftbDestination.JobMonitor(jobId)) { launchSingleTop = true } },
        )
    }
}
