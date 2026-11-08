package ca.devdcs.backup.ui.rclone

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.storage.rclone.OAuthTokenValidator
import ca.devdcs.backup.core.storage.rclone.RcloneConfigBuilder
import ca.devdcs.backup.core.storage.rclone.RcloneIniIssue

/*
 * Localized text for the rclone layer's machine-readable outcomes. The core layer reports enums
 * and codes only; the wording lives here and in strings_destinations.xml.
 */

@StringRes
fun RcloneConfigBuilder.Problem.messageRes(): Int = when (this) {
    RcloneConfigBuilder.Problem.REQUIRED -> R.string.destinations_error_required
    RcloneConfigBuilder.Problem.INVALID_REMOTE_NAME -> R.string.destinations_error_invalid_remote_name
    RcloneConfigBuilder.Problem.NOT_A_NUMBER -> R.string.rclone_setup_error_not_number
    RcloneConfigBuilder.Problem.NOT_A_URL -> R.string.destinations_error_invalid_url
    RcloneConfigBuilder.Problem.NOT_AN_EMAIL -> R.string.destinations_error_invalid_email
    RcloneConfigBuilder.Problem.NOT_A_CHOICE -> R.string.destinations_error_invalid_choice
    RcloneConfigBuilder.Problem.LINE_BREAK -> R.string.destinations_error_invalid_characters
    RcloneConfigBuilder.Problem.TOKEN_INVALID -> R.string.rclone_setup_error_token
    RcloneConfigBuilder.Problem.NEED_ONE_OF -> R.string.rclone_setup_error_need_one_of
    RcloneConfigBuilder.Problem.UNKNOWN_FIELD -> R.string.rclone_setup_error_unknown_field
}

@StringRes
fun OAuthTokenValidator.Problem.messageRes(): Int = when (this) {
    OAuthTokenValidator.Problem.EMPTY -> R.string.rclone_token_empty
    OAuthTokenValidator.Problem.NOT_JSON -> R.string.rclone_token_not_json
    OAuthTokenValidator.Problem.MISSING_ACCESS_TOKEN -> R.string.rclone_token_missing_access
    OAuthTokenValidator.Problem.MISSING_TOKEN_TYPE -> R.string.rclone_token_missing_type
    OAuthTokenValidator.Problem.BAD_EXPIRY -> R.string.rclone_token_bad_expiry
}

/** Message for one field error; token errors name the precise token problem. */
@StringRes
fun RcloneConfigBuilder.FieldError.messageRes(): Int = tokenProblem?.messageRes() ?: problem.messageRes()

@StringRes
fun RcloneConfigBuilder.Warning.messageRes(): Int = when (this) {
    RcloneConfigBuilder.Warning.TOKEN_WITHOUT_REFRESH -> R.string.rclone_setup_warning_no_refresh
}

@StringRes
fun RcloneIniIssue.Code.messageRes(): Int = when (this) {
    RcloneIniIssue.Code.MALFORMED_LINE -> R.string.rclone_issue_malformed_line
    RcloneIniIssue.Code.KEY_OUTSIDE_SECTION -> R.string.rclone_issue_key_outside_section
    RcloneIniIssue.Code.INVALID_SECTION_NAME -> R.string.rclone_issue_invalid_section_name
    RcloneIniIssue.Code.DUPLICATE_SECTION -> R.string.rclone_issue_duplicate_section
    RcloneIniIssue.Code.DUPLICATE_KEY -> R.string.rclone_issue_duplicate_key
    RcloneIniIssue.Code.EMPTY_KEY -> R.string.rclone_issue_empty_key
    RcloneIniIssue.Code.MISSING_TYPE -> R.string.rclone_issue_missing_type
    RcloneIniIssue.Code.UNKNOWN_TYPE -> R.string.rclone_issue_unknown_type
}
