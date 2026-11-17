package ca.devdcs.backup.core.discovery.files

import android.content.Context
import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.exec.ElevationTier

/**
 * Maps discovery enums and problems to localized text, keeping the engine types free of resources
 * so they stay pure and the UI has one place to look for wording.
 */
object FileScanText {

    @StringRes
    fun of(reason: BlockReason): Int = when (reason) {
        BlockReason.NEEDS_ALL_FILES_ACCESS -> R.string.file_scan_block_all_files
        BlockReason.NEEDS_MEDIA_PERMISSION -> R.string.file_scan_block_media_permission
        BlockReason.NEEDS_ALL_FILES_ACCESS_FOR_NON_MEDIA -> R.string.file_scan_block_non_media
        BlockReason.NEEDS_ELEVATION_FOR_APP_SANDBOX -> R.string.file_scan_block_app_sandbox
        BlockReason.NEEDS_ROOT -> R.string.file_scan_block_root
        BlockReason.NOT_READABLE -> R.string.file_scan_block_not_readable
        BlockReason.SAF_PERMISSION_MISSING -> R.string.file_scan_block_saf
    }

    @StringRes
    fun of(problem: RootProblem): Int = when (problem) {
        RootProblem.NOT_FOUND -> R.string.file_scan_root_not_found
        RootProblem.NOT_READABLE -> R.string.file_scan_root_not_readable
        RootProblem.NOT_A_DIRECTORY -> R.string.file_scan_root_not_directory
        RootProblem.TOOL_FAILED -> R.string.file_scan_root_tool_failed
    }

    @StringRes
    fun of(warning: ScanWarning): Int = when (warning) {
        ScanWarning.PARTIAL_MEDIA_ACCESS -> R.string.file_scan_warning_partial_media
        ScanWarning.FIND_EXIT_NONZERO -> R.string.file_scan_warning_find_exit
        ScanWarning.UNPARSEABLE_OUTPUT -> R.string.file_scan_warning_unparseable
        ScanWarning.FIND_UNSUPPORTED -> R.string.file_scan_warning_find_unsupported
    }

    @StringRes
    fun of(walker: WalkerKind): Int = when (walker) {
        WalkerKind.NIO -> R.string.file_scan_walker_nio
        WalkerKind.SAF -> R.string.file_scan_walker_saf
        WalkerKind.PRIVILEGED_FIND -> R.string.file_scan_walker_find
        WalkerKind.MEDIA_STORE -> R.string.file_scan_walker_media_store
    }

    @StringRes
    fun of(requirement: AccessRequirement): Int = when (requirement) {
        is AccessRequirement.AllFilesAccess -> R.string.file_scan_req_all_files
        is AccessRequirement.RuntimePermission -> R.string.file_scan_req_permission
        is AccessRequirement.ElevatedTier ->
            if (requirement.minimum == ElevationTier.ROOT) R.string.file_scan_req_root else R.string.file_scan_req_shizuku
        AccessRequirement.SafRegrant -> R.string.file_scan_req_saf
    }

    fun describe(context: Context, problem: FilterProblem): String = when (problem) {
        is FilterProblem.InvalidRegex -> context.getString(
            if (problem.field == FilterProblem.RegexField.INCLUDE) R.string.file_scan_problem_include_regex else R.string.file_scan_problem_exclude_regex,
            problem.message,
        )
        is FilterProblem.NegativeSize -> context.getString(
            if (problem.field == FilterProblem.SizeField.MIN) R.string.file_scan_problem_negative_min else R.string.file_scan_problem_negative_max,
        )
        is FilterProblem.SizeRangeInverted -> context.getString(R.string.file_scan_problem_size_inverted)
        is FilterProblem.DateRangeEmpty -> context.getString(R.string.file_scan_problem_date_empty)
        is FilterProblem.ExtensionConflict ->
            context.getString(R.string.file_scan_problem_extension_conflict, problem.extensions.sorted().joinToString(", "))
    }
}
