package ca.devdcs.backup.ui.shell

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.ui.navigation.DftbDestination
import kotlinx.serialization.KSerializer

/**
 * The five sections reachable from the bottom bar / navigation rail. Exists so the shell, the
 * selection logic and the tests agree on one ordered list; icons are mapped in the composable
 * layer ([ShellNavigationChrome]) so this type stays free of Compose and testable on the JVM.
 */
enum class TopLevelDestination(val route: DftbDestination, @StringRes val labelRes: Int) {
    DASHBOARD(DftbDestination.Dashboard, R.string.shell_nav_dashboard),
    PROFILES(DftbDestination.Profiles, R.string.shell_nav_profiles),
    RESTORE(DftbDestination.RestoreHome, R.string.shell_nav_restore),
    DESTINATIONS(DftbDestination.Destinations, R.string.shell_nav_destinations),
    SETTINGS(DftbDestination.Settings, R.string.shell_nav_settings),
}

/** Window width bucket, mirroring the Material window size classes (600 / 840 dp breakpoints). */
enum class ShellWidth {
    COMPACT,
    MEDIUM,
    EXPANDED;

    companion object {
        fun fromWidthDp(widthDp: Float): ShellWidth = when {
            widthDp < 600f -> COMPACT
            widthDp < 840f -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** Which top-level navigation chrome the shell draws around the current screen. */
enum class ShellNavChrome { NONE, BOTTOM_BAR, RAIL }

/** What tapping a navigation item should do (see [ShellRoutes.tapAction]). */
enum class TopLevelTapAction {
    /** Already on that section's root screen. */
    NONE,

    /** Inside that section: pop back to its root screen. */
    POP_TO_ROOT,

    /** Switch section, saving the current one's stack and restoring the target's. */
    SWITCH,
}

/**
 * Pure route-string logic behind the shell. Navigation exposes the current destination as a route
 * pattern (`<serialName>`, `<serialName>/{arg}` or `<serialName>?arg={arg}`), so matching on the
 * kotlinx.serialization serial name identifies a [DftbDestination] without Compose or Android,
 * which is what lets the selection and visibility rules be unit-tested.
 */
object ShellRoutes {

    private val topLevelNames: List<Pair<TopLevelDestination, String>> = listOf(
        TopLevelDestination.DASHBOARD to name(DftbDestination.Dashboard.serializer()),
        TopLevelDestination.PROFILES to name(DftbDestination.Profiles.serializer()),
        TopLevelDestination.RESTORE to name(DftbDestination.RestoreHome.serializer()),
        TopLevelDestination.DESTINATIONS to name(DftbDestination.Destinations.serializer()),
        TopLevelDestination.SETTINGS to name(DftbDestination.Settings.serializer()),
    )

    /** Secondary screens that conceptually belong to one section (highlighted on the rail). */
    private val ownedBy: List<Pair<String, TopLevelDestination>> = listOf(
        name(DftbDestination.ProfileWizard.serializer()) to TopLevelDestination.PROFILES,
        name(DftbDestination.Schedules.serializer()) to TopLevelDestination.PROFILES,
        name(DftbDestination.RestoreBackup.serializer()) to TopLevelDestination.RESTORE,
        name(DftbDestination.TwrpImport.serializer()) to TopLevelDestination.RESTORE,
        name(DftbDestination.DestinationEditor.serializer()) to TopLevelDestination.DESTINATIONS,
        name(DftbDestination.RcloneSetup.serializer()) to TopLevelDestination.DESTINATIONS,
        name(DftbDestination.RcloneConfigEditor.serializer()) to TopLevelDestination.DESTINATIONS,
        name(DftbDestination.SettingsSection.serializer()) to TopLevelDestination.SETTINGS,
    )

    private val onboardingName = name(DftbDestination.Onboarding.serializer())

    private fun name(serializer: KSerializer<*>): String = serializer.descriptor.serialName

    /** True when [route] (a NavDestination route pattern) is the destination with [serialName]. */
    fun matches(route: String?, serialName: String): Boolean {
        if (route == null) return false
        if (!route.startsWith(serialName)) return false
        if (route.length == serialName.length) return true
        val next = route[serialName.length]
        return next == '/' || next == '?'
    }

    /** The section whose root screen [route] is, or null for any other screen. */
    fun topLevelFor(route: String?): TopLevelDestination? =
        topLevelNames.firstOrNull { (_, serialName) -> matches(route, serialName) }?.first

    fun isOnboarding(route: String?): Boolean = matches(route, onboardingName)

    /**
     * The section to highlight for a back stack (bottom → top route patterns): the current screen's
     * own section, else the section that owns the current secondary screen, else the nearest
     * section root below it. Null only when the stack holds no section at all (onboarding).
     */
    fun selectedTopLevel(backStackRoutes: List<String?>): TopLevelDestination? {
        val current = backStackRoutes.lastOrNull { it != null } ?: return null
        topLevelFor(current)?.let { return it }
        ownedBy.firstOrNull { (serialName, _) -> matches(current, serialName) }?.let { return it.second }
        return backStackRoutes.asReversed().firstNotNullOfOrNull { topLevelFor(it) }
    }

    /**
     * Compact windows get a bottom bar on section roots only (secondary screens own the full
     * height); medium/expanded windows keep the rail on every post-onboarding screen. Nothing is
     * drawn before the graph has a destination or while onboarding runs.
     */
    fun chromeFor(width: ShellWidth, currentRoute: String?): ShellNavChrome = when {
        currentRoute == null || isOnboarding(currentRoute) -> ShellNavChrome.NONE
        width == ShellWidth.COMPACT -> if (topLevelFor(currentRoute) != null) ShellNavChrome.BOTTOM_BAR else ShellNavChrome.NONE
        else -> ShellNavChrome.RAIL
    }

    /** Behaviour of a tap on [item] given the current route and the highlighted section. */
    fun tapAction(item: TopLevelDestination, currentRoute: String?, selected: TopLevelDestination?): TopLevelTapAction = when {
        topLevelFor(currentRoute) == item -> TopLevelTapAction.NONE
        selected == item -> TopLevelTapAction.POP_TO_ROOT
        else -> TopLevelTapAction.SWITCH
    }
}
