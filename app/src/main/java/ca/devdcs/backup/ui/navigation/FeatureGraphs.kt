package ca.devdcs.backup.ui.navigation

/*
 * navigation contract (documentation only; no code here).
 *
 * Each feature package exposes exactly one extension that registers ALL of its routes, and the
 * app shell (ui/shell + DftbNavHost) calls them. Feature code navigates only with
 * navController.navigate(DftbDestination.X(...)) and never edits DftbNavHost.
 *
 * ui/profiles: fun NavGraphBuilder.profilesGraph(navController: NavHostController)
 * registers Profiles, ProfileWizard, Schedules
 * ui/restore: fun NavGraphBuilder.restoreGraph(navController: NavHostController)
 * registers RestoreHome, RestoreBackup, TwrpImport
 * ui/settings: fun NavGraphBuilder.settingsGraph(navController: NavHostController)
 * registers Settings, SettingsSection
 * ui/shell: registers Dashboard, JobMonitor and owns the top-level scaffold
 * (bottom bar / rail: Dashboard, Profiles, Restore, Destinations, Settings).
 *
 * Top-level screens are shown inside the shell's scaffold, so feature screens must NOT draw their
 * own bottom bar; they own their TopAppBar (with an up button only when not top-level).
 */
