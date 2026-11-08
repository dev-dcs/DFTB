package ca.devdcs.backup.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import ca.devdcs.backup.R
import ca.devdcs.backup.core.environment.DiagnosticAction
import ca.devdcs.backup.core.environment.RootProbe
import ca.devdcs.backup.ui.licenses.ExternalLinks

/**
 * The Intent and permission plumbing behind every [DiagnosticAction] button. The probe layer only
 * names a remedy; this is where it becomes a Settings page, a permission dialog or a URL. Kept
 * out of the step composable so the same mapping can serve the Settings diagnostics page later.
 */
object DiagnosticIntents {

    const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/"
    const val MAGISK_URL = "https://github.com/topjohnwu/Magisk"

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

    /** API 30+: the per-app "All files access" page, falling back to the global list. */
    fun openAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context))
        if (ExternalLinks.launch(context, perApp)) return true
        return ExternalLinks.launch(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

    /** The app's notification settings page (used below API 33, where there is no runtime permission). */
    fun openNotificationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        return ExternalLinks.launch(context, intent)
    }

    /**
     * The app's system "App info" details page, where a permanently denied runtime permission can
     * be re-enabled by hand once the OS stops showing the request dialog.
     */
    fun openAppDetailsSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
        return ExternalLinks.launch(context, intent)
    }

    /** System dialog asking to exempt DFTB from Doze, falling back to the optimization list. */
    fun openBatteryExemption(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
        if (ExternalLinks.launch(context, direct)) return true
        return ExternalLinks.launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Launches the first installed root manager; opens the Magisk project page if none is installed. */
    fun openRootManager(context: Context): Boolean {
        val packageManager = context.packageManager
        for ((packageName, _) in RootProbe.ROOT_MANAGERS) {
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: continue
            if (ExternalLinks.launch(context, launch)) return true
        }
        return ExternalLinks.openUrl(context, MAGISK_URL)
    }

    fun openShizukuDownload(context: Context): Boolean = ExternalLinks.openUrl(context, SHIZUKU_DOWNLOAD_URL)
}

/**
 * Returns a handler that performs a [DiagnosticAction] from inside composition, where permission
 * launchers can be registered. The returned lambda is stable across recompositions; the callbacks
 * are read through [rememberUpdatedState] so the latest ones always win.
 *
 * @param onGrantShizuku invoked for [DiagnosticAction.GRANT_SHIZUKU_PERMISSION]; the ViewModel
 * drives the Shizuku request because it suspends.
 * @param onSystemStateChanged invoked after a permission dialog closes or a Settings page was
 * launched, so the caller can re-run diagnostics / refresh flags.
 * @param onCompanionModuleInfo invoked for [DiagnosticAction.INSTALL_COMPANION_MODULE]; the module
 * installer does not exist yet, so the caller shows an explanatory dialog.
 * @param onMessage invoked with a string resource when nothing on the device can handle the action.
 */
@Composable
fun rememberDiagnosticActionHandler(
    onGrantShizuku: () -> Unit,
    onSystemStateChanged: () -> Unit,
    onCompanionModuleInfo: () -> Unit,
    onMessage: (Int) -> Unit,
): (DiagnosticAction) -> Unit {
    val localContext = LocalContext.current
    val activity = LocalActivity.current
    val launchContext: Context = activity ?: localContext

    val grantShizuku by rememberUpdatedState(onGrantShizuku)
    val systemStateChanged by rememberUpdatedState(onSystemStateChanged)
    val companionModuleInfo by rememberUpdatedState(onCompanionModuleInfo)
    val message by rememberUpdatedState(onMessage)

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Once the user has permanently denied, the OS resolves the request instantly without a
        // dialog, so re-requesting looks like a dead button: route to the notification settings
        // page instead. A `false` rationale after a denial means the dialog will no longer appear.
        if (!granted && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            DiagnosticIntents.openNotificationSettings(launchContext)
        }
        systemStateChanged()
    }
    val legacyStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        // Same permanent-denial escape as notifications: if every still-denied permission can no
        // longer show its rationale dialog, send the user to the app details page to grant by hand.
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty() && activity != null &&
            denied.none { activity.shouldShowRequestPermissionRationale(it) }
        ) {
            DiagnosticIntents.openAppDetailsSettings(launchContext)
        }
        systemStateChanged()
    }

    return remember(launchContext) {
        { action: DiagnosticAction ->
            when (action) {
                DiagnosticAction.GRANT_SHIZUKU_PERMISSION -> grantShizuku()

                DiagnosticAction.OPEN_ALL_FILES_ACCESS -> {
                    // No immediate re-check: the Settings page pauses the Activity and the
                    // caller's ON_RESUME hook re-runs diagnostics when the user comes back.
                    if (!DiagnosticIntents.openAllFilesAccess(launchContext)) message(R.string.onboarding_no_app_for_action)
                }

                DiagnosticAction.REQUEST_LEGACY_STORAGE -> {
                    legacyStorageLauncher.launch(
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    )
                }

                DiagnosticAction.REQUEST_NOTIFICATIONS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (!DiagnosticIntents.openNotificationSettings(launchContext)) {
                        message(R.string.onboarding_no_app_for_action)
                    }
                }

                DiagnosticAction.OPEN_BATTERY_SETTINGS -> {
                    if (!DiagnosticIntents.openBatteryExemption(launchContext)) message(R.string.onboarding_no_app_for_action)
                }

                DiagnosticAction.INSTALL_SHIZUKU -> {
                    if (!DiagnosticIntents.openShizukuDownload(launchContext)) message(R.string.onboarding_no_app_for_link)
                }

                DiagnosticAction.OPEN_ROOT_MANAGER -> {
                    if (!DiagnosticIntents.openRootManager(launchContext)) message(R.string.onboarding_no_app_for_link)
                }

                DiagnosticAction.INSTALL_COMPANION_MODULE -> companionModuleInfo()
            }
        }
    }
}
