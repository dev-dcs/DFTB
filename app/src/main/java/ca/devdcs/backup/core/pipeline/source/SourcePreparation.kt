package ca.devdcs.backup.core.pipeline.source

import android.content.Context
import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.pipeline.BackupSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why a whole source cannot run in the current environment (ordinary limits, never exceptions). */
enum class SourceUnavailableReason(@StringRes val messageRes: Int) {
    ROOT_REQUIRED(R.string.pipeline_source_unavailable_root_required),
    ROOT_NOT_GRANTED(R.string.pipeline_source_unavailable_root_not_granted),
    NOTHING_SELECTED(R.string.pipeline_source_unavailable_nothing_selected),
    INVALID_FILTER(R.string.pipeline_source_unavailable_invalid_filter),
    SCAN_FAILED(R.string.pipeline_source_unavailable_scan_failed),
}

/** Something a source will leave out or could only partly capture; shown next to the job result. */
enum class SourceWarningKind(@StringRes val messageRes: Int) {
    PARTITION_UNRESOLVED(R.string.pipeline_source_warn_partition_unresolved),
    PARTITION_SIZE_UNKNOWN(R.string.pipeline_source_warn_partition_size_unknown),
    APP_NOT_INSTALLED(R.string.pipeline_source_warn_app_not_installed),
    APP_INVALID_PACKAGE(R.string.pipeline_source_warn_app_invalid_package),
    APP_COMPONENT_UNAVAILABLE(R.string.pipeline_source_warn_app_component_unavailable),
    APP_APK_UNREADABLE(R.string.pipeline_source_warn_app_apk_unreadable),
    FILES_TRUNCATED(R.string.pipeline_source_warn_files_truncated),
    FILES_ROOT_BLOCKED(R.string.pipeline_source_warn_files_root_blocked),
    FILES_UNREADABLE_ENTRIES(R.string.pipeline_source_warn_files_unreadable_entries),
    NANDROID_PLAN(R.string.pipeline_source_warn_nandroid_plan),
    NANDROID_INVENTORY_PARTIAL(R.string.pipeline_source_warn_nandroid_inventory_partial),
    NANDROID_FILES_VANISHED(R.string.pipeline_source_warn_nandroid_files_vanished),
    SELINUX_TAR_UNSUPPORTED(R.string.pipeline_source_warn_selinux_tar_unsupported),
}

/** One warning; [subject] names the partition / package / root, [detail] is technical (not localized). */
data class SourceWarning(val kind: SourceWarningKind, val subject: String, val detail: String? = null)

/**
 * Outcome of preparing a source. Exists so engines learn "cannot run here, and why" as a value
 * before any byte is read, instead of a collected flow throwing for an environment limit.
 */
sealed interface SourcePreparation<out S : BackupSource> {
    data class Ready<S : BackupSource>(val source: S, val warnings: List<SourceWarning>) : SourcePreparation<S>
    data class Unavailable(val reason: SourceUnavailableReason, val message: String, val detail: String? = null) : SourcePreparation<Nothing>
}

/** Resolves this package's localized messages without a UI context. */
@Singleton
class PipelineSourceStrings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun get(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    fun unavailable(reason: SourceUnavailableReason, detail: String? = null): SourcePreparation.Unavailable =
        SourcePreparation.Unavailable(reason, get(reason.messageRes), detail)

    /** Localized one-line text for [warning]. */
    fun describe(warning: SourceWarning): String = get(warning.kind.messageRes, warning.subject)
}

/**
 * Deterministic short id for checkpoint keys: SHA-256 of the newline-joined [keys], first 16 hex
 * chars. Callers pass stable selection keys (locations, names), never counts or timestamps, so a
 * resumed job maps to the same source id.
 */
internal fun stableHash(keys: List<String>): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(keys.joinToString("\n").toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }
