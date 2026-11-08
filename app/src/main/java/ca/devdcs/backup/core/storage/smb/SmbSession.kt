package ca.devdcs.backup.core.storage.smb

import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.core.storage.ssh.GuardedInputStream
import ca.devdcs.backup.core.storage.ssh.GuardedOutputStream
import ca.devdcs.backup.core.storage.ssh.RemotePaths
import ca.devdcs.backup.core.storage.ssh.StorageStrings
import ca.devdcs.backup.domain.model.Destination
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [StorageSession] on one SMB2/3 disk share (smbj). Writes open with FILE_OVERWRITE_IF (fresh
 * upload) or FILE_OPEN_IF + end-of-file offset (resume), reads seek to the requested offset before
 * the first request, and `.part` commits use SMB's native rename with replace-if-exists.
 */
internal class SmbSession(
    override val destination: Destination,
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    private val location: SmbLocation,
    private val strings: StorageStrings,
    private val io: CoroutineDispatcher,
) : StorageSession {

    private val closed = AtomicBoolean(false)

    override suspend fun spaceInfo(): SpaceInfo? = op("space") {
        val info = share.shareInformation
        SpaceInfo(freeBytes = info.callerFreeSpace, totalBytes = info.totalSpace)
    }

    override suspend fun list(path: String): List<RemoteEntry> = op("list") {
        val rel = smbRel(path)
        share.list(location.smbPath(rel))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                RemoteEntry(
                    path = RemotePaths.child(rel, info.fileName),
                    name = info.fileName,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) null else info.endOfFile,
                    modifiedAtMillis = info.lastWriteTime?.toEpochMillis(),
                )
            }
    }

    override suspend fun stat(path: String): RemoteEntry? = op("stat") { statBlocking(smbRel(path)) }

    private fun statBlocking(rel: String): RemoteEntry? {
        val info = try {
            share.getFileInformation(location.smbPath(rel))
        } catch (e: Exception) {
            if (SmbErrors.isNotFound(e)) return null
            throw e
        }
        val isDir = info.standardInformation.isDirectory
        return RemoteEntry(
            path = rel,
            name = RemotePaths.name(rel),
            isDirectory = isDir,
            sizeBytes = if (isDir) null else info.standardInformation.endOfFile,
            modifiedAtMillis = info.basicInformation.lastWriteTime?.toEpochMillis(),
        )
    }

    override suspend fun mkdirs(path: String) = op("mkdirs") { mkdirsBlocking(smbRel(path)) }

    /** Create every missing folder from the share root down to [rel] (including the base folders). */
    private fun mkdirsBlocking(rel: String) {
        val all = location.baseSegments + RemotePaths.segments(rel)
        var current = ""
        for (segment in all) {
            current = if (current.isEmpty()) segment else "$current\\$segment"
            if (share.folderExists(current)) continue
            try {
                share.mkdir(current)
            } catch (e: Exception) {
                // Another writer created it between the check and mkdir: fine.
                if (!SmbErrors.isNameCollision(e)) throw e
            }
        }
    }

    override suspend fun openWrite(path: String, append: Boolean): OutputStream = op("write") {
        val rel = smbRel(path)
        mkdirsBlocking(RemotePaths.parent(rel))
        val access = if (append) {
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.FILE_READ_ATTRIBUTES)
        } else {
            EnumSet.of(AccessMask.GENERIC_WRITE)
        }
        val disposition = if (append) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OVERWRITE_IF
        val file = share.openFile(
            location.smbPath(rel),
            access,
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            disposition,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        try {
            // append=true starts at the current end of file (queried once at open).
            val out = file.getOutputStream(append)
            GuardedOutputStream(out, { e -> wrap("write", e) }) {
                try {
                    out.close()
                    file.flush()
                } catch (e: Exception) {
                    file.closeSilently()
                    throw e
                }
                file.close() // propagate a failed SMB2 CLOSE on the write path
            }
        } catch (e: Exception) {
            file.closeSilently()
            throw e
        }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = op("read") {
        val file = share.openFile(
            location.smbPath(smbRel(path)),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        try {
            val input = file.inputStream
            // Before the first read smbj's stream only advances its offset on skip(): no data moves.
            if (offset > 0L) input.skip(offset)
            GuardedInputStream(
                delegate = input,
                mapError = { e -> wrap("read", e) },
                closeAction = {
                    try {
                        input.close()
                    } finally {
                        file.closeSilently()
                    }
                },
            )
        } catch (e: Exception) {
            file.closeSilently()
            throw e
        }
    }

    override suspend fun delete(path: String) = op("delete") {
        val rel = smbRel(path)
        if (rel.isEmpty()) return@op // never remove the base itself
        val entry = statBlocking(rel) ?: return@op
        val smbPath = location.smbPath(rel)
        try {
            if (entry.isDirectory) share.rmdir(smbPath, false) else share.rm(smbPath)
        } catch (e: Exception) {
            if (!SmbErrors.isNotFound(e)) throw e
        }
    }

    override suspend fun rename(from: String, to: String) = op("rename") {
        val toRel = smbRel(to)
        mkdirsBlocking(RemotePaths.parent(toRel))
        val entry = share.open(
            location.smbPath(from),
            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        try {
            // Rename target is share-relative; replaceIfExists gives `.part` -> final overwrite semantics.
            entry.rename(location.smbPath(toRel), true)
        } finally {
            entry.closeSilently()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }.onFailure { DftbLog.w(TAG, "SMB client close failed", it) }
    }

    /** SMB treats '\\' as a separator, so contract paths are normalised with it folded to '/'. */
    private fun smbRel(path: String): String = RemotePaths.normalize(path.replace('\\', '/'))

    private fun wrap(op: String, e: Exception): StorageException =
        e as? StorageException ?: StorageException(
            SmbErrors.reasonFor(e),
            strings.get(R.string.storage_net_op_failed, op, e.message ?: e.javaClass.simpleName),
            e,
        )

    /** Run a blocking smbj call on IO, mapping every failure to [StorageException]. */
    private suspend fun <T> op(name: String, block: suspend () -> T): T = withContext(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw wrap(name, e)
        }
    }

    private companion object {
        const val TAG = "SmbSession"
    }
}
