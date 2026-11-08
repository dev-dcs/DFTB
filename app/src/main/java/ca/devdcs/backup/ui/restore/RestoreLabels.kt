package ca.devdcs.backup.ui.restore

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import ca.devdcs.backup.R
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.pipeline.crypto.labelRes
import ca.devdcs.backup.core.restore.ConflictPolicy
import ca.devdcs.backup.core.restore.apps.AppVersionPolicy
import ca.devdcs.backup.core.restore.catalog.CatalogException
import ca.devdcs.backup.core.restore.engine.RestoreErrorCode
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.core.restore.files.TwrpRestoreMode
import ca.devdcs.backup.core.restore.partition.SlotChoice
import ca.devdcs.backup.core.restore.twrp.TwrpCheckStatus
import ca.devdcs.backup.core.restore.twrp.TwrpFolderProblemKind
import ca.devdcs.backup.core.restore.twrp.TwrpPayloadFormat
import ca.devdcs.backup.core.restore.twrp.TwrpPayloadKind
import ca.devdcs.backup.core.storage.labelRes

/*
 * Localized labels for the restore screens. Engine enums carry no resource ids, so this file is
 * the single place where restore vocabulary becomes user-facing text (strings_restore_ui.xml,
 * plus the engine's own strings_restore.xml for failure codes).
 */

@StringRes
fun RestoreUnitKind.groupTitleRes(): Int = when (this) {
    RestoreUnitKind.APP -> R.string.restore_ui_group_apps
    RestoreUnitKind.PARTITION -> R.string.restore_ui_group_partitions
    RestoreUnitKind.FILE_TREE -> R.string.restore_ui_group_files
}

fun RestoreUnitKind.icon(): ImageVector = when (this) {
    RestoreUnitKind.APP -> Icons.Outlined.Apps
    RestoreUnitKind.PARTITION -> Icons.Outlined.Memory
    RestoreUnitKind.FILE_TREE -> Icons.Outlined.Folder
}

@StringRes
fun ConflictPolicy.labelRes(): Int = when (this) {
    ConflictPolicy.SKIP -> R.string.restore_ui_conflict_skip
    ConflictPolicy.OVERWRITE -> R.string.restore_ui_conflict_overwrite
    ConflictPolicy.KEEP_BOTH -> R.string.restore_ui_conflict_keep_both
}

@StringRes
fun ConflictPolicy.descriptionRes(): Int = when (this) {
    ConflictPolicy.SKIP -> R.string.restore_ui_conflict_skip_desc
    ConflictPolicy.OVERWRITE -> R.string.restore_ui_conflict_overwrite_desc
    ConflictPolicy.KEEP_BOTH -> R.string.restore_ui_conflict_keep_both_desc
}

@StringRes
fun FileTargetKind.labelRes(): Int = when (this) {
    FileTargetKind.ORIGINAL -> R.string.restore_ui_target_original
    FileTargetKind.FOLDER -> R.string.restore_ui_target_folder
    FileTargetKind.SAF_TREE -> R.string.restore_ui_target_saf
}

@StringRes
fun TwrpRestoreMode.labelRes(): Int = when (this) {
    TwrpRestoreMode.COPY_FOLDER -> R.string.restore_ui_twrp_copy
    TwrpRestoreMode.EXTRACT_VOLUMES -> R.string.restore_ui_twrp_extract
}

@StringRes
fun TwrpRestoreMode.descriptionRes(): Int = when (this) {
    TwrpRestoreMode.COPY_FOLDER -> R.string.restore_ui_twrp_copy_desc
    TwrpRestoreMode.EXTRACT_VOLUMES -> R.string.restore_ui_twrp_extract_desc
}

@StringRes
fun AppVersionPolicy.labelRes(): Int = when (this) {
    AppVersionPolicy.AUTO -> R.string.restore_ui_version_auto
    AppVersionPolicy.REINSTALL -> R.string.restore_ui_version_reinstall
    AppVersionPolicy.DATA_ONLY -> R.string.restore_ui_version_data_only
}

@StringRes
fun AppVersionPolicy.descriptionRes(): Int = when (this) {
    AppVersionPolicy.AUTO -> R.string.restore_ui_version_auto_desc
    AppVersionPolicy.REINSTALL -> R.string.restore_ui_version_reinstall_desc
    AppVersionPolicy.DATA_ONLY -> R.string.restore_ui_version_data_only_desc
}

@StringRes
fun SlotChoice.labelRes(): Int = when (this) {
    SlotChoice.SAME_ROLE -> R.string.restore_ui_slot_same_role
    SlotChoice.ACTIVE -> R.string.restore_ui_slot_active
    SlotChoice.INACTIVE -> R.string.restore_ui_slot_inactive
    SlotChoice.RECORDED -> R.string.restore_ui_slot_recorded
}

@StringRes
fun SlotChoice.descriptionRes(): Int = when (this) {
    SlotChoice.SAME_ROLE -> R.string.restore_ui_slot_same_role_desc
    SlotChoice.ACTIVE -> R.string.restore_ui_slot_active_desc
    SlotChoice.INACTIVE -> R.string.restore_ui_slot_inactive_desc
    SlotChoice.RECORDED -> R.string.restore_ui_slot_recorded_desc
}

@StringRes
fun RiskyOption.titleRes(): Int = when (this) {
    RiskyOption.IGNORE_SPACE -> R.string.restore_ui_risky_space
    RiskyOption.ALLOW_DOWNGRADE -> R.string.restore_ui_risky_downgrade
    RiskyOption.CROSS_SLOT -> R.string.restore_ui_risky_cross_slot
    RiskyOption.OTHER_DEVICE -> R.string.restore_ui_risky_other_device
    RiskyOption.OTHER_BUILD -> R.string.restore_ui_risky_other_build
    RiskyOption.SMALLER_IMAGE -> R.string.restore_ui_risky_smaller_image
    RiskyOption.CRITICAL_IDENTITY -> R.string.restore_ui_risky_identity
}

/** Short line under the switch. */
@StringRes
fun RiskyOption.hintRes(): Int = when (this) {
    RiskyOption.IGNORE_SPACE -> R.string.restore_ui_risky_space_hint
    RiskyOption.ALLOW_DOWNGRADE -> R.string.restore_ui_risky_downgrade_hint
    RiskyOption.CROSS_SLOT -> R.string.restore_ui_risky_cross_slot_hint
    RiskyOption.OTHER_DEVICE -> R.string.restore_ui_risky_other_device_hint
    RiskyOption.OTHER_BUILD -> R.string.restore_ui_risky_other_build_hint
    RiskyOption.SMALLER_IMAGE -> R.string.restore_ui_risky_smaller_image_hint
    RiskyOption.CRITICAL_IDENTITY -> R.string.restore_ui_risky_identity_hint
}

/** What can go wrong, shown in the confirmation dialog before the switch turns on. */
@StringRes
fun RiskyOption.consequenceRes(): Int = when (this) {
    RiskyOption.IGNORE_SPACE -> R.string.restore_ui_risky_space_consequence
    RiskyOption.ALLOW_DOWNGRADE -> R.string.restore_ui_risky_downgrade_consequence
    RiskyOption.CROSS_SLOT -> R.string.restore_ui_risky_cross_slot_consequence
    RiskyOption.OTHER_DEVICE -> R.string.restore_ui_risky_other_device_consequence
    RiskyOption.OTHER_BUILD -> R.string.restore_ui_risky_other_build_consequence
    RiskyOption.SMALLER_IMAGE -> R.string.restore_ui_risky_smaller_image_consequence
    RiskyOption.CRITICAL_IDENTITY -> R.string.restore_ui_risky_identity_consequence
}

/** The word to type for [RiskyOption.typedWord] options, else null. */
@StringRes
fun RiskyOption.confirmWordRes(): Int? = when (this) {
    RiskyOption.OTHER_DEVICE -> R.string.restore_ui_confirm_word_device
    RiskyOption.CRITICAL_IDENTITY -> R.string.restore_ui_confirm_word_identity
    else -> null
}

@StringRes
fun ElevationTier.requiredLabelRes(): Int = when (this) {
    ElevationTier.NONE -> R.string.restore_ui_tier_none
    ElevationTier.SHIZUKU -> R.string.restore_ui_tier_shizuku
    ElevationTier.ROOT -> R.string.restore_ui_tier_root
}

@StringRes
fun WizardProblem.messageRes(): Int = when (this) {
    WizardProblem.NOTHING_SELECTED -> R.string.restore_ui_problem_nothing_selected
    WizardProblem.FOLDER_REQUIRED -> R.string.restore_ui_problem_folder_required
    WizardProblem.FOLDER_NOT_ABSOLUTE -> R.string.restore_ui_problem_folder_absolute
    WizardProblem.FOLDER_TRAVERSAL -> R.string.restore_ui_problem_folder_traversal
    WizardProblem.FOLDER_PROTECTED -> R.string.restore_ui_problem_folder_protected
    WizardProblem.TREE_REQUIRED -> R.string.restore_ui_problem_tree_required
    WizardProblem.TWRP_EXTRACT_NEEDS_FOLDER -> R.string.restore_ui_problem_twrp_folder
    WizardProblem.APP_NOTHING_TO_RESTORE -> R.string.restore_ui_problem_app_nothing
    WizardProblem.DOWNGRADE_IGNORED -> R.string.restore_ui_problem_downgrade_ignored
    WizardProblem.FOLDER_IN_APP_DATA -> R.string.restore_ui_problem_folder_app_data
}

@StringRes
fun SpaceVerdict.labelRes(): Int = when (this) {
    SpaceVerdict.NOT_NEEDED -> R.string.restore_ui_space_not_needed
    SpaceVerdict.SUFFICIENT -> R.string.restore_ui_space_ok
    SpaceVerdict.UNKNOWN -> R.string.restore_ui_space_unknown
    SpaceVerdict.INSUFFICIENT -> R.string.restore_ui_space_insufficient
}

@StringRes
fun StartBlocker.messageRes(): Int = when (this) {
    StartBlocker.NOTHING_AVAILABLE -> R.string.restore_ui_blocker_nothing
    StartBlocker.INSUFFICIENT_SPACE -> R.string.restore_ui_blocker_space
}

@StringRes
fun RunHeadline.labelRes(): Int = when (this) {
    RunHeadline.RUNNING -> R.string.restore_ui_run_running
    RunHeadline.CANCELLING -> R.string.restore_ui_run_cancelling
    RunHeadline.COMPLETED -> R.string.restore_ui_run_completed
    RunHeadline.COMPLETED_WITH_WARNINGS -> R.string.restore_ui_run_completed_warnings
    RunHeadline.CANCELLED -> R.string.restore_ui_run_cancelled
    RunHeadline.FAILED -> R.string.restore_ui_run_failed
}

@StringRes
fun UnitRunStatus.labelRes(): Int = when (this) {
    UnitRunStatus.WAITING -> R.string.restore_ui_unit_waiting
    UnitRunStatus.RUNNING -> R.string.restore_ui_unit_running
    UnitRunStatus.RESTORED -> R.string.restore_ui_unit_restored
    UnitRunStatus.FAILED -> R.string.restore_ui_unit_failed
    UnitRunStatus.BLOCKED -> R.string.restore_ui_unit_blocked
    UnitRunStatus.NOT_RUN -> R.string.restore_ui_unit_not_run
}

/** The engine's own failure texts (strings_restore.xml). */
@StringRes
fun RestoreErrorCode.messageRes(): Int = when (this) {
    RestoreErrorCode.DESTINATION_NOT_FOUND -> R.string.restore_engine_error_destination
    RestoreErrorCode.OPEN_FAILED -> R.string.restore_engine_error_open
    RestoreErrorCode.KEY_REQUIRED -> R.string.restore_engine_error_key_required
    RestoreErrorCode.WRONG_KEY -> R.string.restore_engine_error_wrong_key
    RestoreErrorCode.UNSUPPORTED_BACKUP -> R.string.restore_engine_error_unsupported
    RestoreErrorCode.NOTHING_TO_RESTORE -> R.string.restore_engine_error_nothing
    RestoreErrorCode.PREFLIGHT_BLOCKED -> R.string.restore_engine_error_blocked
    RestoreErrorCode.INSUFFICIENT_SPACE -> R.string.restore_engine_error_space
    RestoreErrorCode.VERIFY_FAILED -> R.string.restore_engine_error_verify
    RestoreErrorCode.ALL_UNITS_FAILED -> R.string.restore_engine_error_all_failed
    RestoreErrorCode.INTERNAL -> R.string.restore_engine_error_internal
}

@StringRes
fun CatalogException.Reason.labelRes(): Int = when (this) {
    CatalogException.Reason.NO_MANIFEST -> R.string.restore_ui_catalog_no_manifest
    CatalogException.Reason.KEY_REQUIRED -> R.string.restore_ui_catalog_key_required
    CatalogException.Reason.CORRUPT_MANIFEST -> R.string.restore_ui_catalog_corrupt_manifest
    CatalogException.Reason.UNSUPPORTED_SCHEMA -> R.string.restore_ui_catalog_unsupported_schema
    CatalogException.Reason.INCONSISTENT_RUNS -> R.string.restore_ui_catalog_inconsistent_runs
    CatalogException.Reason.UNSUPPORTED_ENCRYPTION -> R.string.restore_ui_catalog_unsupported_encryption
    CatalogException.Reason.MISSING_FILE -> R.string.restore_ui_catalog_missing_file
    CatalogException.Reason.SIZE_MISMATCH -> R.string.restore_ui_catalog_size_mismatch
    CatalogException.Reason.DIGEST_MISMATCH -> R.string.restore_ui_catalog_digest_mismatch
    CatalogException.Reason.ITEM_DIGEST_MISMATCH -> R.string.restore_ui_catalog_item_digest_mismatch
    CatalogException.Reason.SIDECAR_MISMATCH -> R.string.restore_ui_catalog_sidecar_mismatch
    CatalogException.Reason.NOT_A_SINGLE_STREAM -> R.string.restore_ui_catalog_not_single_stream
}

@StringRes
fun TwrpPayloadKind.labelRes(): Int = when (this) {
    TwrpPayloadKind.RAW_IMAGE -> R.string.restore_ui_twrp_kind_image
    TwrpPayloadKind.FILESYSTEM_ARCHIVE -> R.string.restore_ui_twrp_kind_archive
}

@StringRes
fun TwrpPayloadFormat.labelRes(): Int = when (this) {
    TwrpPayloadFormat.TAR -> R.string.restore_ui_twrp_format_tar
    TwrpPayloadFormat.GZIP -> R.string.restore_ui_twrp_format_gzip
    TwrpPayloadFormat.ENCRYPTED -> R.string.restore_ui_twrp_format_encrypted
    TwrpPayloadFormat.RAW -> R.string.restore_ui_twrp_format_raw
    TwrpPayloadFormat.UNKNOWN -> R.string.restore_ui_twrp_format_unknown
    TwrpPayloadFormat.EMPTY -> R.string.restore_ui_twrp_format_empty
}

@StringRes
fun TwrpCheckStatus.labelRes(): Int = when (this) {
    TwrpCheckStatus.OK -> R.string.restore_ui_twrp_check_ok
    TwrpCheckStatus.MISMATCH -> R.string.restore_ui_twrp_check_mismatch
    TwrpCheckStatus.NO_SIDECAR -> R.string.restore_ui_twrp_check_no_sidecar
    TwrpCheckStatus.SIDECAR_UNREADABLE -> R.string.restore_ui_twrp_check_sidecar_unreadable
    TwrpCheckStatus.READ_FAILED -> R.string.restore_ui_twrp_check_read_failed
}

@StringRes
fun TwrpFolderProblemKind.labelRes(): Int = when (this) {
    TwrpFolderProblemKind.SPLIT_GAP -> R.string.restore_ui_twrp_problem_split_gap
    TwrpFolderProblemKind.SPLIT_AND_SINGLE -> R.string.restore_ui_twrp_problem_split_and_single
    TwrpFolderProblemKind.ORPHAN_SIDECAR -> R.string.restore_ui_twrp_problem_orphan_sidecar
    TwrpFolderProblemKind.DUPLICATE_SIDECAR -> R.string.restore_ui_twrp_problem_duplicate_sidecar
}

@StringRes
fun TwrpDraftProblem.messageRes(): Int = when (this) {
    TwrpDraftProblem.NOT_RAW_IMAGE -> R.string.restore_ui_twrp_draft_not_image
    TwrpDraftProblem.SPLIT_IMAGE -> R.string.restore_ui_twrp_draft_split
    TwrpDraftProblem.NOT_ARCHIVE -> R.string.restore_ui_twrp_draft_not_archive
    TwrpDraftProblem.ENCRYPTED -> R.string.restore_ui_twrp_draft_encrypted
    TwrpDraftProblem.UNKNOWN_FORMAT -> R.string.restore_ui_twrp_draft_unknown_format
    TwrpDraftProblem.INVALID_PARTITION_NAME -> R.string.restore_ui_twrp_draft_partition_name
    TwrpDraftProblem.TARGET_DESTINATION_REQUIRED -> R.string.restore_ui_twrp_draft_destination
    TwrpDraftProblem.FOLDER_TRAVERSAL -> R.string.restore_ui_twrp_draft_traversal
}

@StringRes
fun TwrpAction.labelRes(): Int = when (this) {
    TwrpAction.RESTORE_IMAGE -> R.string.restore_ui_twrp_action_restore
    TwrpAction.EXTRACT_FILES -> R.string.restore_ui_twrp_action_extract
}

/** Localized explanation of an open/unlock failure, with the technical detail appended when useful. */
@Composable
fun OpenFailure.text(): String = when (this) {
    is OpenFailure.Catalog -> withDetail(stringResource(reason.labelRes()), detail.takeIf { reason == CatalogException.Reason.CORRUPT_MANIFEST })
    is OpenFailure.Cipher -> stringResource(reason.labelRes())
    is OpenFailure.KeyUnavailable -> stringResource(reason.labelRes())
    is OpenFailure.Storage -> withDetail(stringResource(reason.labelRes()), detail)
    OpenFailure.DestinationMissing -> stringResource(R.string.restore_engine_error_destination)
    is OpenFailure.Other -> withDetail(stringResource(R.string.restore_engine_error_open), detail)
}

/** Localized verification problem. */
@Composable
fun ArtifactProblem.text(): String {
    val base = when (this) {
        is ArtifactProblem.Catalog -> withDetail(stringResource(reason.labelRes()), detail)
        is ArtifactProblem.Cipher -> stringResource(reason.labelRes())
        is ArtifactProblem.Io -> withDetail(stringResource(R.string.restore_ui_problem_io), detail)
        is ArtifactProblem.Unknown -> text
    }
    return if (more > 0) stringResource(R.string.restore_ui_problem_more, base, more) else base
}

fun withDetail(base: String, detail: String?): String = if (detail.isNullOrBlank()) base else "$base ($detail)"
