package ca.devdcs.backup.ui.profiles

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.retention.RetentionHold
import ca.devdcs.backup.core.retention.RetentionReason
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SlotPolicy
import ca.devdcs.backup.domain.model.StructureMode
import ca.devdcs.backup.domain.model.TransferMode

/*
 * Localized labels for the profiles feature's own vocabulary (wizard steps, draft problems, option
 * blocks) and for domain/engine enums that have no resource mapping elsewhere yet. Shared enums
 * that already have one (ArchiveFormat, CompressionCodec, ChecksumAlgorithm, AppComponent,
 * FileCategory...) are mapped through ui/mapping and ui/discovery instead.
 */

@StringRes
internal fun WizardStep.titleRes(): Int = when (this) {
    WizardStep.NAME -> R.string.profiles_step_name
    WizardStep.TARGETS -> R.string.profiles_step_targets
    WizardStep.DESTINATION -> R.string.profiles_step_destination
    WizardStep.OPTIONS -> R.string.profiles_step_options
    WizardStep.SCHEDULE -> R.string.profiles_step_schedule
    WizardStep.REVIEW -> R.string.profiles_step_review
}

@StringRes
internal fun WizardStep.subtitleRes(): Int = when (this) {
    WizardStep.NAME -> R.string.profiles_step_name_sub
    WizardStep.TARGETS -> R.string.profiles_step_targets_sub
    WizardStep.DESTINATION -> R.string.profiles_step_destination_sub
    WizardStep.OPTIONS -> R.string.profiles_step_options_sub
    WizardStep.SCHEDULE -> R.string.profiles_step_schedule_sub
    WizardStep.REVIEW -> R.string.profiles_step_review_sub
}

@StringRes
internal fun DraftProblem.messageRes(): Int = when (this) {
    DraftProblem.NAME_BLANK -> R.string.profiles_problem_name_blank
    DraftProblem.NAME_TOO_LONG -> R.string.profiles_problem_name_too_long
    DraftProblem.NAME_DUPLICATE -> R.string.profiles_problem_name_duplicate
    DraftProblem.DESCRIPTION_TOO_LONG -> R.string.profiles_problem_description_too_long
    DraftProblem.NO_TARGETS -> R.string.profiles_problem_no_targets
    DraftProblem.APP_WITHOUT_COMPONENTS -> R.string.profiles_problem_app_no_components
    DraftProblem.PATH_INVALID -> R.string.profiles_problem_path_invalid
    DraftProblem.PATH_DUPLICATE -> R.string.profiles_problem_path_duplicate
    DraftProblem.FILTER_INVALID -> R.string.profiles_problem_filter_invalid
    DraftProblem.COMPONENTS_UNAVAILABLE -> R.string.profiles_problem_components_unavailable
    DraftProblem.PARTITIONS_NEED_ROOT -> R.string.profiles_problem_partitions_need_root
    DraftProblem.NO_DESTINATION -> R.string.profiles_problem_no_destination
    DraftProblem.DESTINATION_GONE -> R.string.profiles_problem_destination_gone
    DraftProblem.ENCRYPTION_UNSUPPORTED -> R.string.profiles_problem_encryption_unsupported
    DraftProblem.TRANSFER_UNSUPPORTED -> R.string.profiles_problem_transfer_unsupported
    DraftProblem.VAULT_KEY_MISSING -> R.string.profiles_problem_vault_key_missing
    DraftProblem.TWRP_NOT_LOCAL -> R.string.profiles_problem_twrp_not_local
    DraftProblem.TWRP_WITHOUT_PARTITIONS -> R.string.profiles_problem_twrp_no_partitions
    DraftProblem.INTERVAL_INVALID -> R.string.profiles_problem_interval_invalid
    DraftProblem.SCHEDULE_TIME_INVALID -> R.string.profiles_problem_schedule_time
    DraftProblem.SCHEDULE_NO_DAYS -> R.string.profiles_problem_schedule_no_days
    DraftProblem.SCHEDULE_INVALID_DAY -> R.string.profiles_problem_schedule_invalid_day
    DraftProblem.SCHEDULE_INTERVAL_TOO_SHORT -> R.string.profiles_problem_schedule_interval_short
    DraftProblem.SCHEDULE_BATTERY_INVALID -> R.string.profiles_problem_schedule_battery
    DraftProblem.KEEP_LAST_INVALID -> R.string.profiles_problem_keep_last
    DraftProblem.MAX_AGE_INVALID -> R.string.profiles_problem_max_age
    DraftProblem.PASSPHRASE_SCHEDULED -> R.string.profiles_problem_passphrase_scheduled
}

@StringRes
internal fun KeyChoice.labelRes(): Int = when (this) {
    KeyChoice.NONE -> R.string.profiles_key_none
    KeyChoice.PASSPHRASE -> R.string.profiles_key_passphrase
    KeyChoice.VAULT_KEY -> R.string.profiles_key_vault
    KeyChoice.STORED_SECRET -> R.string.profiles_key_stored
}

@StringRes
internal fun KeyChoice.descriptionRes(): Int = when (this) {
    KeyChoice.NONE -> R.string.profiles_key_none_desc
    KeyChoice.PASSPHRASE -> R.string.profiles_key_passphrase_desc
    KeyChoice.VAULT_KEY -> R.string.profiles_key_vault_desc
    KeyChoice.STORED_SECRET -> R.string.profiles_key_stored_desc
}

@StringRes
internal fun OptionBlock.messageRes(): Int = when (this) {
    OptionBlock.TWRP_FORCES_PLAIN -> R.string.profiles_block_twrp
    OptionBlock.MASK_NEEDS_ENCRYPTION -> R.string.profiles_block_mask
    OptionBlock.REMOTE_COMPRESS_NEEDS_SSH -> R.string.profiles_block_remote_needs_ssh
    OptionBlock.REMOTE_COMPRESS_NOT_AVAILABLE -> R.string.profiles_block_remote_unavailable
}

@StringRes
internal fun LevelTier.labelRes(): Int = when (this) {
    LevelTier.STORE -> R.string.compression_level_store
    LevelTier.FAST -> R.string.compression_level_fast
    LevelTier.BALANCED -> R.string.compression_level_balanced
    LevelTier.MAXIMUM -> R.string.compression_level_maximum
    LevelTier.ULTRA -> R.string.compression_level_ultra
}

@StringRes
internal fun ScheduleFrequency.labelRes(): Int = when (this) {
    ScheduleFrequency.DAILY -> R.string.profiles_freq_daily
    ScheduleFrequency.WEEKLY -> R.string.profiles_freq_weekly
    ScheduleFrequency.INTERVAL -> R.string.profiles_freq_interval
}

@StringRes
internal fun IntervalUnit.labelRes(): Int = when (this) {
    IntervalUnit.MINUTES -> R.string.profiles_unit_minutes
    IntervalUnit.HOURS -> R.string.profiles_unit_hours
}

@StringRes
internal fun SlotPolicy.labelRes(): Int = when (this) {
    SlotPolicy.ACTIVE -> R.string.profiles_slot_active
    SlotPolicy.INACTIVE -> R.string.profiles_slot_inactive
    SlotPolicy.BOTH -> R.string.profiles_slot_both
    SlotPolicy.NONE -> R.string.profiles_slot_none
}

@StringRes
internal fun SlotPolicy.descriptionRes(): Int = when (this) {
    SlotPolicy.ACTIVE -> R.string.profiles_slot_active_desc
    SlotPolicy.INACTIVE -> R.string.profiles_slot_inactive_desc
    SlotPolicy.BOTH -> R.string.profiles_slot_both_desc
    SlotPolicy.NONE -> R.string.profiles_slot_none_desc
}

@StringRes
internal fun StructureMode.descriptionRes(): Int = when (this) {
    StructureMode.PRESERVE_HIERARCHY -> R.string.profiles_structure_preserve_desc
    StructureMode.CATEGORIZED -> R.string.profiles_structure_categorized_desc
}

@StringRes
internal fun TransferMode.descriptionRes(): Int = when (this) {
    TransferMode.LOCAL_STAGING -> R.string.profiles_transfer_staging_desc
    TransferMode.DIRECT_STREAM -> R.string.profiles_transfer_direct_desc
    TransferMode.REMOTE_COMPRESS -> R.string.profiles_transfer_remote_desc
}

@StringRes
internal fun JobStatus.profileLabelRes(): Int = when (this) {
    JobStatus.RUNNING -> R.string.profiles_status_running
    JobStatus.INTERRUPTED -> R.string.profiles_status_interrupted
    JobStatus.COMPLETED -> R.string.profiles_status_completed
    JobStatus.COMPLETED_WITH_WARNINGS -> R.string.profiles_status_warnings
    JobStatus.FAILED -> R.string.profiles_status_failed
    JobStatus.CANCELLED -> R.string.profiles_status_cancelled
}

@StringRes
internal fun RetentionReason.labelRes(): Int = when (this) {
    RetentionReason.BEYOND_KEEP_LAST -> R.string.profiles_retention_reason_keep_last
    RetentionReason.OLDER_THAN_MAX_AGE -> R.string.profiles_retention_reason_max_age
}

@StringRes
internal fun RetentionHold.labelRes(): Int = when (this) {
    RetentionHold.NEWEST_SUCCESSFUL -> R.string.profiles_retention_hold_newest
    RetentionHold.NEWEST_COMPLETE -> R.string.profiles_retention_hold_complete
    RetentionHold.ACTIVE_JOB -> R.string.profiles_retention_hold_active
    RetentionHold.UNSAFE_PATH -> R.string.profiles_retention_hold_unsafe
    RetentionHold.TWRP_FOLDER_UNKNOWN -> R.string.profiles_retention_hold_twrp
}
