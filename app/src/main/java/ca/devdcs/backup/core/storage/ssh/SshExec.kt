package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.R
import ca.devdcs.backup.core.storage.StorageException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs remote commands over SSH "exec" channels. Exists for the SSH destination type: `df` for
 * capacity, and — when the server has no SFTP subsystem — every file operation through POSIX shell
 * commands. Requires the account's login shell to be POSIX-compatible (sh/bash/dash/zsh/ash).
 */
internal class SshExec(
    private val client: SSHClient,
    private val io: CoroutineDispatcher,
    private val strings: StorageStrings,
) {

    data class Result(val exitCode: Int, val stdout: List<String>, val stderr: String)

    /** Run [script] to completion, buffering stdout/stderr. Interruptible; bounded by [timeoutMillis]. */
    suspend fun run(script: String, timeoutMillis: Long): Result = withContext(io) {
        val session = client.startSession()
        try {
            withTimeoutOrNull(timeoutMillis) { runInterruptible { execBlocking(session, script) } }
                ?: throw StorageException(
                    StorageException.Reason.TIMEOUT,
                    strings.get(R.string.storage_net_op_failed, "exec", "timeout after ${timeoutMillis}ms"),
                )
        } finally {
            runCatching { session.close() }
        }
    }

    private fun execBlocking(session: Session, script: String): Result {
        val command = session.exec(script)
        val err = ByteArrayOutputStream()
        // stderr is drained concurrently so a chatty command cannot stall on a full channel window.
        val errDrain = thread(isDaemon = true, name = "dftb-ssh-stderr") { runCatching { command.errorStream.copyTo(err) } }
        val out = command.inputStream.readBytes()
        errDrain.join(DRAIN_JOIN_MILLIS)
        command.join(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
        val stdout = String(out, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }.dropLastWhile { it.isEmpty() }
        val stderr = synchronized(err) { String(err.toByteArray(), Charsets.UTF_8) }.trim()
        return Result(command.exitStatus ?: -1, stdout, stderr)
    }

    /**
     * Start [script] (e.g. `cat > file`) and return a stream feeding its stdin. Closing the stream
     * sends EOF, waits for the command and fails if it exited non-zero, so a write is only reported
     * complete once the remote side really has it. Blocking; call on the IO dispatcher.
     */
    fun openStdin(script: String, mapError: (Exception) -> StorageException): OutputStream {
        val session = client.startSession()
        try {
            val command = session.exec(script)
            val err = ByteArrayOutputStream()
            val errDrain = thread(isDaemon = true, name = "dftb-ssh-stderr") { runCatching { command.errorStream.copyTo(err) } }
            val outDrain = thread(isDaemon = true, name = "dftb-ssh-stdout") { runCatching { discard(command.inputStream) } }
            return GuardedOutputStream(command.outputStream, mapError) {
                try {
                    command.outputStream.close() // EOF to the remote command
                    command.join(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
                    errDrain.join(DRAIN_JOIN_MILLIS)
                    outDrain.join(DRAIN_JOIN_MILLIS)
                    val status = command.exitStatus
                    if (status != 0) {
                        val detail = synchronized(err) { String(err.toByteArray(), Charsets.UTF_8) }.trim()
                            .ifEmpty { describeExit(status, command.exitSignal?.name, command.exitErrorMessage) }
                        throw StorageException(PosixShell.classifyError(detail), strings.get(R.string.storage_net_op_failed, "write", detail))
                    }
                } finally {
                    runCatching { session.close() }
                }
            }
        } catch (e: Exception) {
            runCatching { session.close() }
            throw e
        }
    }

    /**
     * Start [script] (e.g. `tail -c +N file`) and return its stdout. At EOF the exit status is
     * checked so a failed remote read is reported instead of looking like a short file.
     */
    fun openStdout(script: String, mapError: (Exception) -> StorageException): InputStream {
        val session = client.startSession()
        try {
            val command = session.exec(script)
            val err = ByteArrayOutputStream()
            val errDrain = thread(isDaemon = true, name = "dftb-ssh-stderr") { runCatching { command.errorStream.copyTo(err) } }
            return GuardedInputStream(
                delegate = command.inputStream,
                mapError = mapError,
                onEof = {
                    command.join(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
                    errDrain.join(DRAIN_JOIN_MILLIS)
                    val status = command.exitStatus
                    // null = killed by a signal or no status sent: the data may be truncated, so fail.
                    if (status != 0) {
                        val detail = synchronized(err) { String(err.toByteArray(), Charsets.UTF_8) }.trim()
                            .ifEmpty { describeExit(status, command.exitSignal?.name, command.exitErrorMessage) }
                        throw StorageException(PosixShell.classifyError(detail), strings.get(R.string.storage_net_op_failed, "read", detail))
                    }
                },
                closeAction = { runCatching { session.close() } },
            )
        } catch (e: Exception) {
            runCatching { session.close() }
            throw e
        }
    }

    private fun describeExit(status: Int?, signal: String?, message: String?): String = buildString {
        append(if (status != null) "exit $status" else "no exit status")
        signal?.let { append(", signal ").append(it) }
        message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }

    private fun discard(input: InputStream) {
        val buffer = ByteArray(8192)
        while (input.read(buffer) >= 0) {
            // Output of `cat > file` is not interesting; reading keeps the channel window open.
        }
    }

    private companion object {
        const val DRAIN_JOIN_MILLIS = 5_000L
        const val EXIT_WAIT_SECONDS = 30L
        /** `cat` may still be flushing a large tail to disk after EOF. */
        const val CLOSE_WAIT_SECONDS = 600L
    }
}
