package ca.devdcs.backup.core.storage.nfs

import ca.devdcs.backup.R
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.ssh.PosixShell
import ca.devdcs.backup.core.storage.ssh.PosixShellSession
import ca.devdcs.backup.core.storage.ssh.RemotePaths
import ca.devdcs.backup.core.storage.ssh.StorageStrings
import ca.devdcs.backup.domain.model.Destination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A session on a root-mounted NFS export. Files under the mountpoint are not readable by the app
 * uid, so metadata operations are root shell commands ([PosixShellSession]) and file data moves
 * through [RootFifoTransfer]. Closing releases the mount (lazy unmount once no session uses it).
 */
internal class NfsSession(
    override val destination: Destination,
    private val executor: CommandExecutor,
    /** The mountpoint; nothing outside it may be reached, even through server-planted symlinks. */
    private val mountPoint: String,
    private val transfer: RootFifoTransfer,
    basePath: String,
    strings: StorageStrings,
    private val io: CoroutineDispatcher,
    private val onClose: () -> Unit,
) : PosixShellSession(basePath, strings) {

    private val closed = AtomicBoolean(false)

    override fun confine(absPath: String, includeFinal: Boolean): String =
        PosixShell.symlinkGuard(mountPoint, absPath, includeFinal)

    override suspend fun runScript(script: String, timeoutMillis: Long): ShellOutput {
        val result = executor.execute(Command(listOf("sh", "-c", script), timeoutMillis = timeoutMillis))
        return when {
            !result.launched -> throw StorageException(
                StorageException.Reason.UNSUPPORTED,
                strings.get(R.string.storage_nfs_root_unavailable),
            )
            result.exitCode == CommandResult.EXIT_TIMEOUT -> throw StorageException(
                StorageException.Reason.TIMEOUT,
                strings.get(R.string.storage_net_op_failed, "shell", "timeout after ${timeoutMillis}ms"),
            )
            else -> ShellOutput(result.exitCode, result.stdout, result.stderrText.trim())
        }
    }

    override suspend fun openWrite(path: String, append: Boolean): OutputStream = guarded("write") {
        val rel = RemotePaths.normalize(path)
        withContext(io) { transfer.openUpload(mountPoint, abs(rel), abs(RemotePaths.parent(rel)), append) }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = guarded("read") {
        val rel = RemotePaths.normalize(path)
        val entry = stat(rel)
        if (entry == null || entry.isDirectory) {
            throw StorageException(StorageException.Reason.NOT_FOUND, strings.get(R.string.storage_net_op_failed, "read", rel))
        }
        withContext(io) { transfer.openDownload(mountPoint, abs(rel), offset.coerceAtLeast(0L)) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }

    private suspend inline fun <T> guarded(op: String, crossinline block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException(
                StorageException.Reason.PROTOCOL,
                strings.get(R.string.storage_net_op_failed, op, e.message ?: e.javaClass.simpleName),
                e,
            )
        }
    }
}
