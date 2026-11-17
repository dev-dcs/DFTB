package ca.devdcs.backup.ui.restore

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import ca.devdcs.backup.ui.navigation.DftbDestination

/**
 * Registers the restore feature's routes (RestoreHome, RestoreBackup, TwrpImport) in the app's
 * NavHost. The shell calls this; restore screens navigate only through [DftbDestination] routes.
 * RestoreHome is top-level (shown in the shell's scaffold); the other two have an up button.
 */
fun NavGraphBuilder.restoreGraph(navController: NavHostController) {
    composable<DftbDestination.RestoreHome> {
        RestoreHomeScreen(
            onOpenBackup = { destinationId, jobDir ->
                navController.navigate(DftbDestination.RestoreBackup(destinationId = destinationId, jobDir = jobDir)) { launchSingleTop = true }
            },
            onOpenTwrpImport = { destinationId ->
                navController.navigate(DftbDestination.TwrpImport(destinationId = destinationId)) { launchSingleTop = true }
            },
            onAddDestination = {
                navController.navigate(DftbDestination.DestinationEditor()) { launchSingleTop = true }
            },
        )
    }
    composable<DftbDestination.RestoreBackup> {
        RestoreBackupScreen(onBack = { navController.popBackStack() })
    }
    composable<DftbDestination.TwrpImport> {
        TwrpImportScreen(onBack = { navController.popBackStack() })
    }
}
