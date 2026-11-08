package ca.devdcs.backup.core.discovery.partition

import androidx.annotation.StringRes
import ca.devdcs.backup.R

/*
 * String resources for partition-discovery enums. The models stay free of Android resource ids so
 * they remain pure/JVM-testable; this file is the single mapping to user-visible text
 * (res/values/strings_partitions.xml). Messages with %1$s take the warning detail or target name.
 */

@StringRes
fun PartitionView.labelRes(): Int = when (this) {
    PartitionView.STANDARD -> R.string.partition_view_standard
    PartitionView.ADVANCED -> R.string.partition_view_advanced
}

@StringRes
fun PartitionView.summaryRes(): Int = when (this) {
    PartitionView.STANDARD -> R.string.partition_view_standard_summary
    PartitionView.ADVANCED -> R.string.partition_view_advanced_summary
}

@StringRes
fun PartitionSlot.labelRes(): Int = when (this) {
    PartitionSlot.A -> R.string.partition_slot_a
    PartitionSlot.B -> R.string.partition_slot_b
    PartitionSlot.NONE -> R.string.partition_slot_none
}

@StringRes
fun PartitionCategory.labelRes(): Int = when (this) {
    PartitionCategory.BOOT_CHAIN -> R.string.partition_category_boot_chain
    PartitionCategory.FIRMWARE -> R.string.partition_category_firmware
    PartitionCategory.MODEM -> R.string.partition_category_modem
    PartitionCategory.USERDATA -> R.string.partition_category_userdata
    PartitionCategory.SYSTEM_LOGICAL -> R.string.partition_category_system_logical
    PartitionCategory.PERSIST_IDENTITY -> R.string.partition_category_persist_identity
    PartitionCategory.OTHER -> R.string.partition_category_other
}

@StringRes
fun PartitionRisk.labelRes(): Int = when (this) {
    PartitionRisk.SAFE_TO_IMAGE -> R.string.partition_risk_safe
    PartitionRisk.HUGE -> R.string.partition_risk_huge
    PartitionRisk.CRITICAL_IDENTITY -> R.string.partition_risk_critical
}

/** Longer explanation for risky partitions; null for [PartitionRisk.SAFE_TO_IMAGE]. */
@StringRes
fun PartitionRisk.summaryRes(): Int? = when (this) {
    PartitionRisk.SAFE_TO_IMAGE -> null
    PartitionRisk.HUGE -> R.string.partition_risk_huge_summary
    PartitionRisk.CRITICAL_IDENTITY -> R.string.partition_risk_critical_summary
}

@StringRes
fun PartitionUnavailableReason.messageRes(): Int = when (this) {
    PartitionUnavailableReason.ROOT_REQUIRED -> R.string.partition_unavailable_root_required
    PartitionUnavailableReason.ROOT_NOT_GRANTED -> R.string.partition_unavailable_root_not_granted
    PartitionUnavailableReason.SCAN_FAILED -> R.string.partition_unavailable_scan_failed
    PartitionUnavailableReason.NO_PARTITIONS_FOUND -> R.string.partition_unavailable_none_found
}

/** Message for a warning kind; kinds marked [takesDetail] expect the warning detail as %1$s. */
@StringRes
fun PartitionScanWarningKind.messageRes(): Int = when (this) {
    PartitionScanWarningKind.NO_BY_NAME_DIRECTORY -> R.string.partition_warning_no_by_name
    PartitionScanWarningKind.BY_NAME_CONFLICT -> R.string.partition_warning_by_name_conflict
    PartitionScanWarningKind.SIZES_INCOMPLETE -> R.string.partition_warning_sizes_incomplete
    PartitionScanWarningKind.ACTIVE_SLOT_UNKNOWN -> R.string.partition_warning_active_slot_unknown
    PartitionScanWarningKind.DM_NAMES_UNAVAILABLE -> R.string.partition_warning_dm_names_unavailable
    PartitionScanWarningKind.SCRIPT_PARTIAL -> R.string.partition_warning_script_partial
    PartitionScanWarningKind.UNRESOLVED_LINKS -> R.string.partition_warning_unresolved_links
}

/** True when [messageRes] contains a %1$s placeholder for [PartitionScanWarning.detail]. */
val PartitionScanWarningKind.takesDetail: Boolean
    get() = this == PartitionScanWarningKind.BY_NAME_CONFLICT ||
        this == PartitionScanWarningKind.SIZES_INCOMPLETE ||
        this == PartitionScanWarningKind.UNRESOLVED_LINKS

/** Message for a resolution failure; always formatted with the target name as %1$s. */
@StringRes
fun PartitionResolutionFailure.messageRes(): Int = when (this) {
    PartitionResolutionFailure.NOT_FOUND -> R.string.partition_resolution_not_found
    PartitionResolutionFailure.ACTIVE_SLOT_UNKNOWN -> R.string.partition_resolution_active_slot_unknown
    PartitionResolutionFailure.SLOT_MISSING -> R.string.partition_resolution_slot_missing
}
