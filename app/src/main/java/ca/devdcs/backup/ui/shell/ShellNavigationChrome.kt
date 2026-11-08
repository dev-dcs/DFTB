package ca.devdcs.backup.ui.shell

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import ca.devdcs.backup.ui.navigation.DftbDestination

/** Icon of a section in its selected / unselected state (filled vs outlined). */
internal fun TopLevelDestination.icon(selected: Boolean): ImageVector = when (this) {
    TopLevelDestination.DASHBOARD -> if (selected) Icons.Filled.Dashboard else Icons.Outlined.Dashboard
    TopLevelDestination.PROFILES -> if (selected) Icons.Filled.Backup else Icons.Outlined.Backup
    TopLevelDestination.RESTORE -> if (selected) Icons.Filled.Restore else Icons.Outlined.Restore
    TopLevelDestination.DESTINATIONS -> if (selected) Icons.Filled.Cloud else Icons.Outlined.Cloud
    TopLevelDestination.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}

/** Current window width bucket from the Material window size class (configuration fallback). */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
internal fun rememberShellWidth(): ShellWidth {
    val activity = LocalActivity.current
    if (activity == null) return ShellWidth.fromWidthDp(LocalConfiguration.current.screenWidthDp.toFloat())
    return when (calculateWindowSizeClass(activity).widthSizeClass) {
        WindowWidthSizeClass.Compact -> ShellWidth.COMPACT
        WindowWidthSizeClass.Medium -> ShellWidth.MEDIUM
        else -> ShellWidth.EXPANDED
    }
}

@Composable
internal fun ShellBottomBar(selected: TopLevelDestination?, onSelect: (TopLevelDestination) -> Unit) {
    NavigationBar {
        TopLevelDestination.entries.forEach { item ->
            val isSelected = item == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon(isSelected), contentDescription = null) },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }
}

/** Rail on the start edge; it pads for the start/vertical safe area the content then skips. */
@Composable
internal fun ShellRail(selected: TopLevelDestination?, onSelect: (TopLevelDestination) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(
        modifier = modifier,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
    ) {
        TopLevelDestination.entries.forEach { item ->
            val isSelected = item == selected
            NavigationRailItem(
                selected = isSelected,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon(isSelected), contentDescription = null) },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }
}

/**
 * Switches section the Material way: everything above the dashboard (the permanent home) is
 * popped with its state saved, the target section's saved stack is restored, and a second tap on
 * an already-open section never stacks a duplicate. The dashboard, not the graph's start
 * destination, is the anchor because a first launch starts the graph at onboarding, which is
 * removed from the back stack once finished.
 */
fun NavHostController.navigateToTopLevel(item: TopLevelDestination) {
    navigate(item.route) {
        popUpTo<DftbDestination.Dashboard> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Handles a tap on a bar / rail item according to [ShellRoutes.tapAction]. */
internal fun NavHostController.onTopLevelSelected(item: TopLevelDestination, currentRoute: String?, selected: TopLevelDestination?) {
    when (ShellRoutes.tapAction(item, currentRoute, selected)) {
        TopLevelTapAction.NONE -> Unit
        TopLevelTapAction.POP_TO_ROOT -> if (!popBackStack(item.route, inclusive = false)) navigateToTopLevel(item)
        TopLevelTapAction.SWITCH -> navigateToTopLevel(item)
    }
}
