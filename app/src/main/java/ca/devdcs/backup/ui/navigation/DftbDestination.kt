package ca.devdcs.backup.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes. Each phase adds the destinations whose screens it implements;
 * nothing is declared here before the screen exists.
 */
sealed interface DftbDestination {

    /**
     * First-launch wizard (language, overview, disclaimer, licenses, operating mode, baseline).
     * Start destination until `AppPreferences.onboardingCompleted` is true; popped off the back
     * stack when it finishes so Back never returns to it.
     */
    @Serializable
    data object Onboarding : DftbDestination

    /** Device, storage, native-library and preference overview; also hosts the live language switch. */
    @Serializable
    data object SystemInfo : DftbDestination

    /** Stand-alone open-source licenses and credits list (same content as onboarding step 4). */
    @Serializable
    data object Licenses : DftbDestination

    /** Destinations manager: list, test, delete; entry to add/edit and rclone setup. */
    @Serializable
    data object Destinations : DftbDestination

    /** Add (null [destinationId], starts at the type picker) or edit one destination. */
    @Serializable
    data class DestinationEditor(val destinationId: String? = null) : DftbDestination

    /** Guided rclone remote setup; [guideId] (a ProviderGuide id) skips the provider list. */
    @Serializable
    data class RcloneSetup(val guideId: String? = null) : DftbDestination

    /** Raw rclone.conf editor (FLAG_SECURE). */
    @Serializable
    data object RcloneConfigEditor : DftbDestination

    /** Read-only discovery hub (partitions, apps, files); [initialTab] is a DiscoveryTab ordinal. */
    @Serializable
    data class Discovery(val initialTab: Int = 0) : DftbDestination

    // ---- routes. Each feature package registers its own routes through a
    // `NavGraphBuilder.<feature>Graph(navController)` extension (see FeatureGraphs.kt);
    // only the app shell edits DftbNavHost. ----

    /** Home: recent/next backups, active jobs, destination health, quick actions (ui/shell). */
    @Serializable
    data object Dashboard : DftbDestination

    /** Live progress + log terminal; null jobId = all active jobs (ui/shell). */
    @Serializable
    data class JobMonitor(val jobId: String? = null) : DftbDestination

    /** Backup profiles list (ui/profiles). */
    @Serializable
    data object Profiles : DftbDestination

    /** Create (null id) or edit a backup profile; [step] opens a wizard step directly (ui/profiles). */
    @Serializable
    data class ProfileWizard(val profileId: String? = null, val step: Int = 0) : DftbDestination

    /** Schedules + retention overview across profiles (ui/profiles). */
    @Serializable
    data object Schedules : DftbDestination

    /** Restore browser: pick a destination and a backup (ui/restore). */
    @Serializable
    data object RestoreHome : DftbDestination

    /** One backup on a destination: verify, browse, restore (ui/restore). */
    @Serializable
    data class RestoreBackup(val destinationId: String, val jobDir: String) : DftbDestination

    /** Import a TWRP/OrangeFox backup folder from a destination (ui/restore). */
    @Serializable
    data class TwrpImport(val destinationId: String? = null) : DftbDestination

    /** Settings root (ui/settings). */
    @Serializable
    data object Settings : DftbDestination

    /** A settings sub-screen by stable key, e.g. "language", "mode", "defaults" (ui/settings). */
    @Serializable
    data class SettingsSection(val section: String) : DftbDestination
}
