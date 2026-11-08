package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.domain.model.Destination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSH destination session for servers that refuse the SFTP subsystem (minimal or locked-down
 * hosts): every operation is a POSIX shell command over an exec channel. Uploads stream into
 * `cat > file` (or `>>` to resume), downloads come from `tail -c +N`, capacity from `df -Pk`.
 */
internal class SshExecSession(
    override val destination: Destination,
    private val client: SSHClient,
    private val exec: SshExec,
    basePath: String,
    strings: StorageStrings,
    private val io: CoroutineDispatcher,
) : PosixShellSession(basePath, strings) {

    private val closed = AtomicBoolean(false)

    override suspend fun runScript(script: String, timeoutMillis: Long): ShellOutput = guarded("exec") {
        val result = exec.run(script, timeoutMillis)
        ShellOutput(result.exitCode, result.stdout, result.stderr)
    }

    override suspend fun openWrite(path: String, append: Boolean): OutputStream = guarded("write") {
        val rel = RemotePaths.normalize(path)
        val target = PosixShell.quote(abs(rel))
        val parent = PosixShell.quote(abs(RemotePaths.parent(rel)))
        val redirect = if (append) ">>" else ">"
        withContext(io) {
            exec.openStdin("mkdir -p $parent && cat $redirect $target") { e -> SshErrors.wrap(e, opFailed("write", e)) }
        }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = guarded("read") {
        val rel = RemotePaths.normalize(path)
        val entry = stat(rel) ?: throw StorageException(StorageException.Reason.NOT_FOUND, opFailed("read", rel))
        if (entry.isDirectory) throw StorageException(StorageException.Reason.NOT_FOUND, strings.get(R.string.storage_net_op_failed, "read", rel))
        withContext(io) {
            exec.openStdout(PosixShell.readCommand(abs(rel), offset)) { e -> SshErrors.wrap(e, opFailed("read", e)) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client.disconnect() }.onFailure { DftbLog.w(TAG, "SSH disconnect failed", it) }
    }

    private fun opFailed(op: String, detail: Any?): String =
        strings.get(R.string.storage_net_op_failed, op, (detail as? Throwable)?.message ?: detail.toString())

    private suspend inline fun <T> guarded(op: String, crossinline block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw SshErrors.wrap(e, opFailed(op, e))
        }
    }

    private companion object {
        const val TAG = "SshExecSession"
    }
}
