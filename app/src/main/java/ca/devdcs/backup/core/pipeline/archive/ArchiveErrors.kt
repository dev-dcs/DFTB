package ca.devdcs.backup.core.pipeline.archive

import java.io.IOException

/**
 * Why an archive operation was refused or failed. Exists so the orchestrator and UI can show a
 * localized explanation ([labelRes]) instead of a raw exception message, while the message keeps
 * the technical detail for logs.
 */
enum class ArchiveErrorKind {
    /** Archive path is absolute, contains '..' / NUL, or is empty. */
    UNSAFE_PATH,
    /** RAW_COPY / DD_IMAGE got zero, several, or non-byte items. */
    SINGLE_ITEM_ONLY,
    /** A source produced fewer or more bytes than it declared. */
    SIZE_MISMATCH,
    /** The native zstd library could not be loaded on this device/ABI. */
    CODEC_UNAVAILABLE,
    /** The requested format cannot be read back (non-archive formats). */
    UNSUPPORTED_FORMAT,
    /** An archive or compressed stream ended before its end marker (incomplete download/backup). */
    TRUNCATED,
    /** The writer was used after finish()/close() or finish() was called twice. */
    WRITER_STATE,
}

/** Common view over the archive exceptions so callers can branch on [kind] regardless of base type. */
interface ArchiveError {
    val kind: ArchiveErrorKind
}

/**
 * Programming/input errors (bad path, wrong item count). An [IllegalArgumentException] so callers
 * that validate input generically still catch it.
 */
class ArchiveArgumentException(override val kind: ArchiveErrorKind, message: String) :
    IllegalArgumentException(message), ArchiveError

/** I/O-level archive failures (size mismatch, codec unavailable, corrupt or unsafe input archive). */
class ArchiveIoException(override val kind: ArchiveErrorKind, message: String, cause: Throwable? = null) :
    IOException(message, cause), ArchiveError

/** Misuse of a writer's lifecycle (write after finish, double finish). */
class ArchiveStateException(message: String) : IllegalStateException(message), ArchiveError {
    override val kind: ArchiveErrorKind get() = ArchiveErrorKind.WRITER_STATE
}
