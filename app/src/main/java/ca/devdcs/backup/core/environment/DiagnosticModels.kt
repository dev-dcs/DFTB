package ca.devdcs.backup.core.environment

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.mode.OperatingMode

/** Outcome of a single environment check. */
enum class CheckStatus(@StringRes val labelRes: Int) {
    PASS(R.string.diag_status_pass),
    WARN(R.string.diag_status_warn),
    FAIL(R.string.diag_status_fail),
    INFO(R.string.diag_status_info),
}

/**
 * Whole-mode verdict derived from the individual checks, so the onboarding wizard can decide
 * whether a mode may be selected without re-interpreting every check itself.
 */
enum class Readiness(@StringRes val labelRes: Int) {
    READY(R.string.diag_readiness_ready),
    DEGRADED(R.string.diag_readiness_degraded),
    UNAVAILABLE(R.string.diag_readiness_unavailable);

    companion object {
        /**
         * UNAVAILABLE if any required check failed; DEGRADED if anything warned or an optional
         * check failed; READY otherwise.
         */
        fun from(checks: List<DiagnosticCheck>): Readiness = when {
            checks.any { it.required && it.status == CheckStatus.FAIL } -> UNAVAILABLE
            checks.any { it.status == CheckStatus.WARN || it.status == CheckStatus.FAIL } -> DEGRADED
            else -> READY
        }
    }
}

/**
 * The remedy the UI can offer for a failed or warning check. The probe only names the remedy; the
 * screen owns the Intent/permission plumbing because it needs an Activity.
 */
enum class DiagnosticAction(@StringRes val labelRes: Int) {
    /** Call [ShizukuProbe.requestPermission]. */
    GRANT_SHIZUKU_PERMISSION(R.string.diag_action_grant_shizuku_permission),
    /** API 30+: launch `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` for this package. */
    OPEN_ALL_FILES_ACCESS(R.string.diag_action_open_all_files_access),
    /** API 29: request the legacy READ/WRITE_EXTERNAL_STORAGE runtime permissions. */
    REQUEST_LEGACY_STORAGE(R.string.diag_action_request_legacy_storage),
    /** API 33+: request POST_NOTIFICATIONS; otherwise open the app's notification settings. */
    REQUEST_NOTIFICATIONS(R.string.diag_action_request_notifications),
    /** Launch `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for this package. */
    OPEN_BATTERY_SETTINGS(R.string.diag_action_open_battery_settings),
    /** Open the Shizuku download page / store listing. */
    INSTALL_SHIZUKU(R.string.diag_action_install_shizuku),
    /** Launch the detected root manager (or explain that none is installed). */
    OPEN_ROOT_MANAGER(R.string.diag_action_open_root_manager),
    /** Navigate to the companion-module installer screen. */
    INSTALL_COMPANION_MODULE(R.string.diag_action_install_companion_module),
}

/**
 * One row of the diagnostics list. [detail] is raw, non-localized data (a version string, a path,
 * a uid line); every localized explanation goes through [hintRes].
 */
data class DiagnosticCheck(
    val id: String,
    @StringRes val titleRes: Int,
    val status: CheckStatus,
    val detail: String?,
    @StringRes val hintRes: Int?,
    val required: Boolean,
    val action: DiagnosticAction?,
)

/** Result of [EnvironmentProbe.diagnose] for one [OperatingMode]. */
data class DiagnosticReport(
    val mode: OperatingMode,
    val readiness: Readiness,
    val checks: List<DiagnosticCheck>,
    val durationMillis: Long,
) {
    /** Convenience for the UI: the first check that failed and still has a remedy. */
    val firstActionable: DiagnosticCheck?
        get() = checks.firstOrNull { it.status == CheckStatus.FAIL && it.action != null }
            ?: checks.firstOrNull { it.status == CheckStatus.WARN && it.action != null }
}

/**
 * Stable check identifiers. Stable because Settings and tests key on them, and because
 * [EnvironmentProbe] reads [ROOT_GRANTED] out of the root results to gate the module checks.
 */
object DiagnosticIds {
    const val PROBE = "probe"

    const val SDK_LEVEL = "sdk_level"
    const val ALL_FILES_ACCESS = "all_files_access"
    const val LEGACY_STORAGE = "legacy_storage"
    const val NOTIFICATIONS = "notifications"
    const val SAF = "saf"
    const val BATTERY = "battery"
    const val FREE_SPACE = "free_space"

    const val SHIZUKU_INSTALLED = "shizuku_installed"
    const val SHIZUKU_BINDER = "shizuku_binder"
    const val SHIZUKU_VERSION_SUPPORTED = "shizuku_version_supported"
    const val SHIZUKU_PERMISSION = "shizuku_permission"
    const val SHIZUKU_VERSION = "shizuku_version"
    const val SHIZUKU_UID = "shizuku_uid"
    const val SHIZUKU_SELINUX = "shizuku_selinux"

    const val SU_BINARY = "su_binary"
    const val ROOT_MANAGER = "root_manager"
    const val ROOT_GRANTED = "root_granted"
    const val SHELL_IDENTITY = "shell_identity"
    const val SELINUX = "selinux"
    const val ROOT_IMPLEMENTATION = "root_implementation"
    const val BUSYBOX = "busybox"
    const val NSENTER = "nsenter"

    const val MODULE_INSTALLED = "module_installed"
    const val MODULE_ENABLED = "module_enabled"
    const val MODULE_TOOL_RCLONE = "module_tool_rclone"
    const val MODULE_TOOL_BUSYBOX = "module_tool_busybox"
    const val MODULE_TOOL_NSENTER = "module_tool_nsenter"
    const val MODULE_TOOL_ZSTD = "module_tool_zstd"
    const val MODULE_TOOL_TAR = "module_tool_tar"
    const val MODULE_SERVICE = "module_service"
    const val MODULE_JOURNAL = "module_journal"
}
