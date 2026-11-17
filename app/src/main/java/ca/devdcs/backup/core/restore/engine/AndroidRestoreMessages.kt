package ca.devdcs.backup.core.restore.engine

import android.text.format.Formatter
import ca.devdcs.backup.R
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.restore.files.ExtractIssue
import ca.devdcs.backup.core.restore.files.ExtractIssueCode
import ca.devdcs.backup.core.restore.files.FileRestoreError
import javax.inject.Inject
import javax.inject.Singleton

/** [RestoreMessages] from `strings_restore.xml`, in the app's chosen language. */
@Singleton
class AndroidRestoreMessages @Inject constructor(
    private val localeManager: LocaleManager,
) : RestoreMessages {

    private val context get() = localeManager.localizedContext()
    private val res get() = context.resources

    private fun str(id: Int, vararg args: Any): String = if (args.isEmpty()) res.getString(id) else res.getString(id, *args)

    private fun withDetail(base: String, detail: String?): String = if (detail.isNullOrBlank()) base else "$base ($detail)"

    override fun opening(): String = str(R.string.restore_engine_opening)
    override fun preflight(): String = str(R.string.restore_engine_preflight)
    override fun verifying(): String = str(R.string.restore_engine_verifying)
    override fun restoring(name: String): String = str(R.string.restore_engine_restoring, name)

    override fun completed(unitCount: Int): String = res.getQuantityString(R.plurals.restore_engine_completed, unitCount, unitCount)

    override fun completedWithWarnings(unitCount: Int, warningCount: Int): String =
        completed(unitCount) + " " + res.getQuantityString(R.plurals.restore_engine_warning_count, warningCount, warningCount)

    override fun cancelled(): String = str(R.string.restore_engine_cancelled)

    override fun failure(failure: RestoreFailure): String {
        val base = when (failure.code) {
            RestoreErrorCode.DESTINATION_NOT_FOUND -> str(R.string.restore_engine_error_destination)
            RestoreErrorCode.OPEN_FAILED -> str(R.string.restore_engine_error_open)
            RestoreErrorCode.KEY_REQUIRED -> str(R.string.restore_engine_error_key_required)
            RestoreErrorCode.WRONG_KEY -> str(R.string.restore_engine_error_wrong_key)
            RestoreErrorCode.UNSUPPORTED_BACKUP -> str(R.string.restore_engine_error_unsupported)
            RestoreErrorCode.NOTHING_TO_RESTORE -> str(R.string.restore_engine_error_nothing)
            RestoreErrorCode.PREFLIGHT_BLOCKED -> str(R.string.restore_engine_error_blocked)
            RestoreErrorCode.INSUFFICIENT_SPACE -> str(R.string.restore_engine_error_space)
            RestoreErrorCode.VERIFY_FAILED -> str(R.string.restore_engine_error_verify)
            RestoreErrorCode.ALL_UNITS_FAILED -> str(R.string.restore_engine_error_all_failed)
            RestoreErrorCode.INTERNAL -> str(R.string.restore_engine_error_internal)
        }
        return withDetail(base, failure.detail)
    }

    override fun fileIssue(issue: ExtractIssue): String {
        val base = when (issue.code) {
            ExtractIssueCode.UNSAFE_PATH -> str(R.string.restore_issue_unsafe_path)
            ExtractIssueCode.THROUGH_SYMLINK -> str(R.string.restore_issue_through_symlink)
            ExtractIssueCode.PARENT_NOT_DIRECTORY -> str(R.string.restore_issue_parent_not_dir)
            ExtractIssueCode.DIRECTORY_IN_THE_WAY -> str(R.string.restore_issue_directory_in_the_way)
            ExtractIssueCode.SKIPPED_EXISTING -> str(R.string.restore_issue_skipped_existing)
            ExtractIssueCode.SYMLINK_UNSUPPORTED -> str(R.string.restore_issue_symlink_unsupported)
            ExtractIssueCode.HARDLINK_UNSUPPORTED -> str(R.string.restore_issue_hardlink_unsupported)
            ExtractIssueCode.HARDLINK_TARGET_MISSING -> str(R.string.restore_issue_hardlink_missing)
            ExtractIssueCode.SPECIAL_SKIPPED -> str(R.string.restore_issue_special_skipped)
            ExtractIssueCode.DIGEST_MISMATCH -> str(R.string.restore_issue_digest_mismatch)
            ExtractIssueCode.WRITE_FAILED -> str(R.string.restore_issue_write_failed)
            ExtractIssueCode.METADATA_FAILED -> str(R.string.restore_issue_metadata_failed)
        }
        return withDetail("${issue.path}: $base", issue.detail)
    }

    override fun fileError(error: FileRestoreError, detail: String?): String {
        val base = when (error) {
            FileRestoreError.ORIGINAL_LOCATION_UNKNOWN -> str(R.string.restore_file_error_original_unknown)
            FileRestoreError.TARGET_UNAVAILABLE -> str(R.string.restore_file_error_target)
            FileRestoreError.NO_PRIVILEGE -> str(R.string.restore_file_error_no_privilege)
            FileRestoreError.SAF_PERMISSION_MISSING -> str(R.string.restore_file_error_saf)
            FileRestoreError.TWRP_EXTRACT_NEEDS_FOLDER -> str(R.string.restore_file_error_twrp_folder)
            FileRestoreError.KEY_REQUIRED -> str(R.string.restore_engine_error_key_required)
            FileRestoreError.WRONG_KEY -> str(R.string.restore_engine_error_wrong_key)
            FileRestoreError.CORRUPT_ARTIFACT -> str(R.string.restore_file_error_corrupt)
            FileRestoreError.NO_SPACE -> str(R.string.restore_file_error_no_space)
            FileRestoreError.ARCHIVE_FAILED -> str(R.string.restore_file_error_archive)
            FileRestoreError.STORAGE -> str(R.string.restore_file_error_storage)
        }
        return withDetail(base, detail)
    }

    override fun moreIssues(count: Int): String = res.getQuantityString(R.plurals.restore_engine_more_issues, count, count)
    override fun unmapped(count: Int): String = res.getQuantityString(R.plurals.restore_engine_unmapped, count, count)
    override fun skippedExisting(count: Int): String = res.getQuantityString(R.plurals.restore_engine_kept_existing, count, count)

    override fun restorerMissing(kind: RestoreUnitKind): String = when (kind) {
        RestoreUnitKind.APP -> str(R.string.restore_engine_no_app_restorer)
        RestoreUnitKind.PARTITION -> str(R.string.restore_engine_no_partition_restorer)
        RestoreUnitKind.FILE_TREE -> str(R.string.restore_file_error_target)
    }

    override fun unitBlocked(name: String, reason: String): String = str(R.string.restore_engine_unit_blocked, name, reason)
    override fun spaceUnknown(name: String): String = str(R.string.restore_engine_space_unknown, name)

    override fun spaceOverridden(name: String, shortfallBytes: Long): String =
        str(R.string.restore_engine_space_overridden, name, Formatter.formatShortFileSize(context, shortfallBytes))

    override fun crossDevice(backupModel: String, thisModel: String): String = str(R.string.restore_engine_cross_device, backupModel, thisModel)
    override fun resumeSkipped(name: String): String = str(R.string.restore_engine_resume_skipped, name)
    override fun checkpointFailed(): String = str(R.string.restore_engine_checkpoint_failed)
    override fun partialBackup(): String = str(R.string.restore_engine_partial_backup)
}
