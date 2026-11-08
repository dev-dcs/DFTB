package ca.devdcs.backup.core.pipeline.archive

import androidx.annotation.StringRes
import ca.devdcs.backup.R

/** Localized, user-facing explanation for an [ArchiveErrorKind] (strings_archive.xml). */
@StringRes
fun ArchiveErrorKind.labelRes(): Int = when (this) {
    ArchiveErrorKind.UNSAFE_PATH -> R.string.archive_error_unsafe_path
    ArchiveErrorKind.SINGLE_ITEM_ONLY -> R.string.archive_error_single_item
    ArchiveErrorKind.SIZE_MISMATCH -> R.string.archive_error_size_mismatch
    ArchiveErrorKind.CODEC_UNAVAILABLE -> R.string.archive_error_codec_unavailable
    ArchiveErrorKind.UNSUPPORTED_FORMAT -> R.string.archive_error_unsupported_format
    ArchiveErrorKind.TRUNCATED -> R.string.archive_error_truncated
    ArchiveErrorKind.WRITER_STATE -> R.string.archive_error_writer_state
}
