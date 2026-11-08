package ca.devdcs.backup.core.environment

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.app.NotificationManagerCompat
import ca.devdcs.backup.R
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.nativebridge.DftbNative
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks every mode needs regardless of elevation tier: storage permissions, notifications,
 * the document picker, Doze exemption and free space. Shared storage access is only *required*
 * for the non-root tiers; a root shell reads everything anyway, so there it merely warns.
 */
@Singleton
class StandardModeProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManagerCompat,
    private val native: DftbNative,
    private val runner: CheckRunner,
) {

    suspend fun checks(mode: OperatingMode): List<DiagnosticCheck> = listOf(
        sdkLevel(),
        storageAccess(requiredForMode = !mode.requiresRoot),
        notifications(),
        documentPicker(),
        batteryOptimization(),
        freeSpace(),
    )

    private suspend fun sdkLevel() = runner.run(DiagnosticIds.SDK_LEVEL, R.string.diag_sdk_level_title, required = false) {
        CheckOutcome(CheckStatus.INFO, detail = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    private suspend fun storageAccess(requiredForMode: Boolean): DiagnosticCheck =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runner.run(
                DiagnosticIds.ALL_FILES_ACCESS,
                R.string.diag_all_files_access_title,
                required = requiredForMode,
                failureAction = DiagnosticAction.OPEN_ALL_FILES_ACCESS,
            ) {
                when {
                    Environment.isExternalStorageManager() -> CheckOutcome(CheckStatus.PASS)
                    requiredForMode -> CheckOutcome(
                        CheckStatus.FAIL,
                        hintRes = R.string.diag_all_files_access_hint,
                        action = DiagnosticAction.OPEN_ALL_FILES_ACCESS,
                    )
                    else -> CheckOutcome(
                        CheckStatus.WARN,
                        hintRes = R.string.diag_all_files_access_optional_hint,
                        action = DiagnosticAction.OPEN_ALL_FILES_ACCESS,
                    )
                }
            }
        } else {
            runner.run(
                DiagnosticIds.LEGACY_STORAGE,
                R.string.diag_legacy_storage_title,
                required = requiredForMode,
                failureAction = DiagnosticAction.REQUEST_LEGACY_STORAGE,
            ) {
                val read = isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
                val write = isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                val detail = "READ_EXTERNAL_STORAGE=$read WRITE_EXTERNAL_STORAGE=$write"
                when {
                    read && write -> CheckOutcome(CheckStatus.PASS)
                    requiredForMode -> CheckOutcome(
                        CheckStatus.FAIL,
                        detail = detail,
                        hintRes = R.string.diag_legacy_storage_hint,
                        action = DiagnosticAction.REQUEST_LEGACY_STORAGE,
                    )
                    else -> CheckOutcome(
                        CheckStatus.WARN,
                        detail = detail,
                        hintRes = R.string.diag_legacy_storage_optional_hint,
                        action = DiagnosticAction.REQUEST_LEGACY_STORAGE,
                    )
                }
            }
        }

    private suspend fun notifications() = runner.run(
        DiagnosticIds.NOTIFICATIONS,
        R.string.diag_notifications_title,
        required = false,
        failureAction = DiagnosticAction.REQUEST_NOTIFICATIONS,
    ) {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        val enabled = notificationManager.areNotificationsEnabled()
        if (runtimeGranted && enabled) {
            CheckOutcome(CheckStatus.PASS)
        } else {
            CheckOutcome(
                CheckStatus.WARN,
                detail = "granted=$runtimeGranted enabled=$enabled",
                hintRes = R.string.diag_notifications_hint,
                action = DiagnosticAction.REQUEST_NOTIFICATIONS,
            )
        }
    }

    private suspend fun documentPicker() = runner.run(DiagnosticIds.SAF, R.string.diag_saf_title, required = true) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        val packageManager = context.packageManager
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
        if (resolved != null) {
            CheckOutcome(CheckStatus.PASS, detail = resolved.activityInfo?.packageName)
        } else {
            CheckOutcome(CheckStatus.FAIL, hintRes = R.string.diag_saf_hint)
        }
    }

    private suspend fun batteryOptimization() = runner.run(
        DiagnosticIds.BATTERY,
        R.string.diag_battery_title,
        required = false,
        failureAction = DiagnosticAction.OPEN_BATTERY_SETTINGS,
    ) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            CheckOutcome(CheckStatus.PASS)
        } else {
            CheckOutcome(
                CheckStatus.WARN,
                hintRes = R.string.diag_battery_hint,
                action = DiagnosticAction.OPEN_BATTERY_SETTINGS,
            )
        }
    }

    private suspend fun freeSpace() = runner.run(DiagnosticIds.FREE_SPACE, R.string.diag_free_space_title, required = false) {
        val path = context.dataDir.absolutePath
        val (available, total) = native.filesystemStats(path)?.let { it.availableBytes to it.totalBytes }
            ?: StatFs(path).let { it.availableBytes to it.totalBytes }
        val detail = "${Formatter.formatFileSize(context, available)} / ${Formatter.formatFileSize(context, total)}"
        if (available < LOW_SPACE_BYTES) {
            CheckOutcome(CheckStatus.WARN, detail = detail, hintRes = R.string.diag_free_space_hint)
        } else {
            CheckOutcome(CheckStatus.INFO, detail = detail)
        }
    }

    private fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val LOW_SPACE_BYTES = 500L * 1024 * 1024
    }
}
