package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.domain.model.Destination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.PacketType
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.SFTPClient
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [StorageSession] over the SFTP subsystem (used for both the SFTP and SSH destination types).
 *
 * Capacity: SFTP destinations use the `statvfs@openssh.com` extension when the server offers it;
 * SSH destinations ([spaceViaDf]) run `df -Pk` over an exec channel instead, which also works on
 * servers whose SFTP server lacks the extension. All file data still moves over SFTP.
 */
internal class SftpSession(
    override val destination: Destination,
    private val client: SSHClient,
    private val sftp: SFTPClient,
    /** Absolute base directory on the server. */
    private val basePath: String,
    private val spaceViaDf: Boolean,
    private val exec: SshExec,
    private val strings: StorageStrings,
    private val io: CoroutineDispatcher,
) : StorageSession {

    private val closed = AtomicBoolean(false)
    private val engine = sftp.getSFTPEngine()

    /**
     * OpenSSH (SFTPv3) overwrites only through posix-rename; v5+ servers honour the OVERWRITE flag.
     * sshj's lookup is `supportsServerExtension(name, domain)` -> "name@domain".
     */
    private val overwriteRenameSupported: Boolean =
        sftp.version() >= 5 || engine.supportsServerExtension("posix-rename", "openssh.com")

    private val statvfsSupported: Boolean = engine.supportsServerExtension("statvfs", "openssh.com")

    private fun abs(relative: String): String = RemotePaths.join(basePath, relative)

    override suspend fun spaceInfo(): SpaceInfo? = op("space") {
        if (spaceViaDf) {
            val result = exec.run(PosixShell.dfScript(abs("")), DF_TIMEOUT_MILLIS)
            if (result.exitCode != 0) {
                DftbLog.w(TAG, "df failed (exit ${result.exitCode}); capacity unknown")
                null
            } else {
                PosixShell.parseDf(result.stdout)
            }
        } else if (statvfsSupported) {
            statvfs(abs(""))
        } else {
            null
        }
    }

    /** `statvfs@openssh.com`: 11 uint64 fields; free = f_bavail*f_frsize, total = f_blocks*f_frsize. */
    private fun statvfs(path: String): SpaceInfo {
        val request = engine.newExtendedRequest("statvfs@openssh.com")
        request.putString(path)
        val response = engine.request(request).retrieve(engine.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        response.ensurePacketTypeIs(PacketType.EXTENDED_REPLY)
        response.readUInt64() // f_bsize
        val frsize = response.readUInt64()
        val blocks = response.readUInt64()
        response.readUInt64() // f_bfree (includes root-reserved blocks)
        val bavail = response.readUInt64()
        return statvfsToSpace(frsize, blocks, bavail)
    }

    override suspend fun list(path: String): List<RemoteEntry> = op("list") {
        val rel = RemotePaths.normalize(path)
        sftp.ls(abs(rel))
            .filter { it.name != "." && it.name != ".." }
            .map { info -> toEntry(RemotePaths.child(rel, info.name), info.name, info.attributes) }
    }

    override suspend fun stat(path: String): RemoteEntry? = op("stat") {
        val rel = RemotePaths.normalize(path)
        sftp.statExistence(abs(rel))?.let { toEntry(rel, RemotePaths.name(rel), it) }
    }

    override suspend fun mkdirs(path: String) = op("mkdirs") {
        mkdirsBlocking(RemotePaths.normalize(path))
    }

    private fun mkdirsBlocking(rel: String) {
        val target = abs(rel)
        val existing = sftp.statExistence(target)
        if (existing != null) {
            if (existing.type != FileMode.Type.DIRECTORY) {
                throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_net_not_a_directory, rel))
            }
            return
        }
        sftp.mkdirs(target)
    }

    override suspend fun openWrite(path: String, append: Boolean): OutputStream = op("write") {
        val rel = RemotePaths.normalize(path)
        val parent = RemotePaths.parent(rel)
        mkdirsBlocking(parent)
        val modes = if (append) {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.APPEND)
        } else {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        }
        val file = sftp.open(abs(rel), modes)
        try {
            val offset = if (append) file.length() else 0L
            val out = file.RemoteFileOutputStream(offset, MAX_UNCONFIRMED_WRITES)
            GuardedOutputStream(out, { e -> SshErrors.wrap(e, failed("write", e)) }) {
                try {
                    out.close() // flushes outstanding writes and waits for their acks
                } catch (e: Exception) {
                    closeQuietly(file)
                    throw e
                }
                file.close() // SSH_FXP_CLOSE status can carry a deferred write error: propagate it
            }
        } catch (e: Exception) {
            closeQuietly(file)
            throw e
        }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = op("read") {
        val file = sftp.open(abs(RemotePaths.normalize(path)), EnumSet.of(OpenMode.READ))
        try {
            val input = file.ReadAheadRemoteFileInputStream(MAX_UNCONFIRMED_READS, offset.coerceAtLeast(0L))
            GuardedInputStream(
                delegate = input,
                mapError = { e -> SshErrors.wrap(e, failed("read", e)) },
                closeAction = {
                    try {
                        input.close()
                    } finally {
                        closeQuietly(file)
                    }
                },
            )
        } catch (e: Exception) {
            closeQuietly(file)
            throw e
        }
    }

    override suspend fun delete(path: String) = op("delete") {
        val rel = RemotePaths.normalize(path)
        if (rel.isEmpty()) return@op // never remove the base itself
        val target = abs(rel)
        val attrs = sftp.statExistence(target) ?: return@op
        if (attrs.type != FileMode.Type.DIRECTORY) {
            sftp.rm(target)
            return@op
        }
        try {
            sftp.rmdir(target)
        } catch (e: SFTPException) {
            // OpenSSH reports a non-empty directory as generic FAILURE: not transient, never retry.
            if (e.statusCode == Response.StatusCode.FAILURE || e.statusCode == Response.StatusCode.DIR_NOT_EMPTY) {
                throw StorageException(StorageException.Reason.PERMISSION, failed("delete", e), e)
            }
            throw e
        }
    }

    override suspend fun rename(from: String, to: String) = op("rename") {
        val toRel = RemotePaths.normalize(to)
        mkdirsBlocking(RemotePaths.parent(toRel))
        val source = abs(from)
        val target = abs(toRel)
        if (overwriteRenameSupported) {
            sftp.rename(source, target, EnumSet.of(RenameFlags.OVERWRITE))
        } else {
            // Plain SFTPv3 RENAME fails if the target exists: remove it first (not atomic).
            if (sftp.statExistence(target) != null) sftp.rm(target)
            sftp.rename(source, target)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { sftp.close() }
        runCatching { client.disconnect() }.onFailure { DftbLog.w(TAG, "SSH disconnect failed", it) }
    }

    private fun toEntry(rel: String, name: String, attrs: FileAttributes): RemoteEntry {
        val isDir = attrs.type == FileMode.Type.DIRECTORY
        return RemoteEntry(
            path = rel,
            name = name,
            isDirectory = isDir,
            sizeBytes = if (isDir || !attrs.has(FileAttributes.Flag.SIZE)) null else attrs.size,
            modifiedAtMillis = if (attrs.has(FileAttributes.Flag.ACMODTIME)) attrs.mtime * 1000L else null,
        )
    }

    private fun closeQuietly(file: RemoteFile) {
        runCatching { file.close() }
    }

    private fun failed(op: String, e: Throwable): String = strings.get(R.string.storage_net_op_failed, op, e.message ?: e.javaClass.simpleName)

    /** Run a blocking SFTP operation on IO, mapping every failure to [StorageException]. */
    private suspend fun <T> op(name: String, block: suspend () -> T): T = withContext(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw SshErrors.wrap(e, failed(name, e))
        }
    }

    internal companion object {
        private const val TAG = "SftpSession"
        private const val MAX_UNCONFIRMED_WRITES = 16
        private const val MAX_UNCONFIRMED_READS = 16
        private const val DF_TIMEOUT_MILLIS = 60_000L

        /** Pure statvfs → [SpaceInfo] conversion (unit-tested). */
        fun statvfsToSpace(frsize: Long, blocks: Long, bavail: Long): SpaceInfo =
            SpaceInfo(freeBytes = bavail * frsize, totalBytes = blocks * frsize)
    }
}
