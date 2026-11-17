package ca.devdcs.backup.core.storage

import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import java.io.InputStream
import java.io.OutputStream

/**
 * One storage protocol (local, SAF, USB OTG, FTP/FTPS, SFTP/SSH, SMB, WebDAV, NFS, rclone).
 *
 * Exists so the backup/restore pipelines (Phases 6–7) stream archives to and from any destination
 * through one interface and never branch on protocol. Implementations are Hilt-multibound into a
 * `Set<StorageBackend>` and looked up by [DestinationType] through [StorageRegistry].
 */
interface StorageBackend {
    /** The destination types this backend serves. Each type is served by exactly one backend. */
    val types: Set<DestinationType>

    /**
     * Open a session to [destination], resolving its credential from the vault by
     * [Destination.credentialRef]. Blocking network I/O runs on the IO dispatcher.
     * @throws StorageException on any failure (never a raw protocol exception).
     */
    suspend fun connect(destination: Destination): StorageSession
}

/**
 * An open connection to one destination. Paths are '/'-separated and relative to the destination's
 * configured base path (`""` or `"/"` is the base itself). Not thread-safe; one pipeline uses one
 * session. Every failure surfaces as [StorageException].
 */
interface StorageSession : AutoCloseable {
    val destination: Destination

    /** Free/total bytes at the base path, or null when the protocol cannot report it. */
    suspend fun spaceInfo(): SpaceInfo?

    /** Direct children of the directory at [path]. */
    suspend fun list(path: String): List<RemoteEntry>

    /** Metadata for [path], or null when it does not exist. */
    suspend fun stat(path: String): RemoteEntry?

    /** Create [path] and any missing parents; no-op if it already exists. */
    suspend fun mkdirs(path: String)

    /**
     * Open [path] for writing (creating parents). The caller writes on an IO thread and MUST close
     * the stream; the upload is complete only once close() returns without throwing.
     * [append] resumes an interrupted upload where the protocol supports it, else throws UNSUPPORTED.
     */
    suspend fun openWrite(path: String, append: Boolean = false): OutputStream

    /** Open [path] for reading from byte [offset] (resumable restore). Caller closes it. */
    suspend fun openRead(path: String, offset: Long = 0L): InputStream

    /** Delete a file, or an empty directory. Missing paths are not an error. */
    suspend fun delete(path: String)

    /** Atomic-where-possible rename used for `.part` → final commits. Overwrites [to]. */
    suspend fun rename(from: String, to: String)

    /** Close the connection. Idempotent; never throws. */
    override fun close()
}

/** A file or directory as reported by a [StorageSession]. */
data class RemoteEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
)

/** Capacity at a destination; either value may be unknown on protocols that only report one. */
data class SpaceInfo(val freeBytes: Long?, val totalBytes: Long?)

/** Uniform, user-explainable storage failure. [reason] drives the UI message and retry policy. */
class StorageException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Why the operation failed. [isRetryable] is true for transient causes. */
    enum class Reason(val isRetryable: Boolean) {
        AUTH(false),
        HOST_KEY(false),
        NETWORK(true),
        TIMEOUT(true),
        NOT_FOUND(false),
        PERMISSION(false),
        NO_SPACE(false),
        UNSUPPORTED(false),
        MISCONFIGURED(false),
        TOOL_MISSING(false),
        PROTOCOL(true),
    }
}

/** Resolves the [StorageBackend] for a destination type. */
interface StorageRegistry {
    fun backendFor(type: DestinationType): StorageBackend

    /** Connect and run [block], always closing the session afterwards. */
    suspend fun <T> withSession(destination: Destination, block: suspend (StorageSession) -> T): T
}

/** Outcome of [ConnectionTester.test]: reachable or not, plus whatever capacity was learned. */
data class ConnectionTestResult(
    val ok: Boolean,
    val failure: StorageException.Reason? = null,
    val detail: String? = null,
    val space: SpaceInfo? = null,
    val latencyMillis: Long,
)
