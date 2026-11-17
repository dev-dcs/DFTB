package ca.devdcs.backup.core.storage.nfs

import android.system.Os
import android.system.OsConstants
import ca.devdcs.backup.R
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.ssh.GuardedInputStream
import ca.devdcs.backup.core.storage.ssh.GuardedOutputStream
import ca.devdcs.backup.core.storage.ssh.PosixShell
import ca.devdcs.backup.core.storage.ssh.StorageStrings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Binary-safe byte streaming between the app and files only root can open (the NFS mountpoint).
 *
 * Why FIFOs: [CommandExecutor] buffers stdin as a ByteArray and returns stdout as text lines, so it
 * cannot carry a multi-gigabyte binary archive. Instead the app creates two named pipes in its cache
 * dir — a data pipe and a status pipe — and launches a detached root helper that copies between the
 * data pipe and the target file, then writes `exit-code\nstderr` into the status pipe. The helper is
 * detached (`( … ) &`) so the shared root shell is free for other commands while a transfer is open.
 * Blocking FIFO opens are guarded by watchdogs that unblock them if the helper never shows up.
 */
internal class RootFifoTransfer(
    private val executor: CommandExecutor,
    private val cacheDir: File,
    private val strings: StorageStrings,
) {

    /**
     * Stream into [target] (created, or appended to when [append]), creating [parentDir] first.
     * [root] confines the helper: no symlink on the way to [target], and [target] is a regular file.
     */
    suspend fun openUpload(root: String, target: String, parentDir: String, append: Boolean): OutputStream {
        val pipes = Pipes.create(cacheDir)
        val redirect = if (append) ">>" else ">"
        val inner = "exec 3<${q(pipes.data)}; ${confinement(root, target, pipes, closeFd = "exec 3<&-")}" +
            "mkdir -p ${q(parentDir)} 2>/dev/null; " +
            "err=\$(cat <&3 2>&1 $redirect${q(target)}); rc=\$?; exec 3<&-; " +
            "printf '%s\\n%s\\n' \"\$rc\" \"\$err\" > ${q(pipes.statusPath)}"
        launchDetached(inner, pipes)
        val (stream, timedOut) = openWithWatchdog(OPEN_TIMEOUT_MILLIS, unblock = { unblockOpen(pipes.data, OsConstants.O_RDONLY) }) {
            FileOutputStream(pipes.data)
        }
        if (timedOut) {
            runCatching { stream.close() }
            pipes.delete()
            throw StorageException(StorageException.Reason.TIMEOUT, strings.get(R.string.storage_net_transfer_timeout, target))
        }
        return GuardedOutputStream(stream, { e -> pipeFailure("write", e, pipes) }) {
            try {
                stream.close() // EOF for the helper's cat
                checkStatus("write", readStatus(pipes))
            } finally {
                pipes.delete()
            }
        }
    }

    /** Stream [target] from byte [offset], confined to [root]. Exit status is checked at end of stream. */
    suspend fun openDownload(root: String, target: String, offset: Long): InputStream {
        val pipes = Pipes.create(cacheDir)
        val inner = "exec 3>${q(pipes.data)}; ${confinement(root, target, pipes, closeFd = "exec 3>&-")}err=\$(${PosixShell.readCommand(target, offset)} 2>&1 >&3); rc=\$?; " +
            "exec 3>&-; printf '%s\\n%s\\n' \"\$rc\" \"\$err\" > ${q(pipes.statusPath)}"
        launchDetached(inner, pipes)
        val (stream, timedOut) = openWithWatchdog(OPEN_TIMEOUT_MILLIS, unblock = { unblockOpen(pipes.data, OsConstants.O_WRONLY) }) {
            FileInputStream(pipes.data)
        }
        if (timedOut) {
            runCatching { stream.close() }
            pipes.delete()
            throw StorageException(StorageException.Reason.TIMEOUT, strings.get(R.string.storage_net_transfer_timeout, target))
        }
        return GuardedInputStream(
            delegate = stream,
            mapError = { e -> pipeFailure("read", e, pipes) },
            onEof = { checkStatus("read", readStatus(pipes)) },
            closeAction = {
                try {
                    stream.close() // an early close makes the helper's writer fail with EPIPE and exit
                    readStatus(pipes) // unblocks the helper; after an early close its status is irrelevant
                } finally {
                    pipes.delete()
                }
            },
        )
    }

    /**
     * Runs after the data pipe is open (so the app never waits on a helper that bailed out): if any
     * component of [target] is a symlink, or [target] exists but is not a regular file, report a
     * permission failure through the status pipe and stop.
     */
    private fun confinement(root: String, target: String, pipes: Pipes, closeFd: String): String {
        val t = q(target)
        val checks = PosixShell.symlinkGuard(root, target, includeFinal = true) +
            "if [ -e $t ] && [ ! -f $t ]; then echo 'Permission denied: not a regular file' >&2; exit 1; fi; true"
        return "__gerr=\$( { $checks; } 2>&1 ); if [ \$? -ne 0 ]; then $closeFd; " +
            "printf '%s\\n%s\\n' 1 \"\$__gerr\" > ${q(pipes.statusPath)}; exit 0; fi; "
    }

    private suspend fun launchDetached(inner: String, pipes: Pipes) {
        val script = "( ${inner} ) </dev/null >/dev/null 2>&1 &"
        val result = executor.execute(Command(listOf("sh", "-c", script), timeoutMillis = LAUNCH_TIMEOUT_MILLIS))
        if (!result.isSuccess) {
            pipes.delete()
            val detail = result.stderrText.ifBlank { "exit ${result.exitCode}" }
            throw StorageException(PosixShell.classifyError(detail), strings.get(R.string.storage_net_op_failed, "transfer", detail))
        }
    }

    /**
     * Helper's `(exitCode, stderr)`; (-1, reason) when it never reported within the timeout. The
     * status pipe is written exactly once, so the first result is cached for later callers.
     */
    private fun readStatus(pipes: Pipes): Pair<Int, String> = synchronized(pipes) {
        pipes.status?.let { return it }
        val result = readStatusOnce(pipes)
        pipes.status = result
        result
    }

    private fun readStatusOnce(pipes: Pipes): Pair<Int, String> {
        val (input, timedOut) = openWithWatchdog(STATUS_TIMEOUT_MILLIS, unblock = { unblockOpen(pipes.statusPath, OsConstants.O_WRONLY) }) {
            FileInputStream(pipes.statusPath)
        }
        val text = input.use { String(it.readBytes(), Charsets.UTF_8) }
        if (timedOut && text.isBlank()) return -1 to "root helper did not report a status"
        val code = text.substringBefore('\n').trim().toIntOrNull() ?: -1
        return code to text.substringAfter('\n', "").trim()
    }

    private fun checkStatus(op: String, status: Pair<Int, String>) {
        val (code, err) = status
        if (code == 0) return
        val detail = err.ifBlank { "exit $code" }
        throw StorageException(PosixShell.classifyError(detail), strings.get(R.string.storage_net_op_failed, op, detail))
    }

    /** A broken pipe means the helper died; its status explains why. */
    private fun pipeFailure(op: String, e: Exception, pipes: Pipes): StorageException {
        val status = runCatching { readStatus(pipes) }.getOrNull()
        val detail = status?.second?.takeIf { it.isNotBlank() } ?: e.message ?: e.javaClass.simpleName
        return StorageException(PosixShell.classifyError(detail), strings.get(R.string.storage_net_op_failed, op, detail), e)
    }

    private fun q(path: String) = PosixShell.quote(path)

    /** The data and status FIFOs for one transfer, private (0600) in the app cache dir. */
    private class Pipes(val data: String, val statusPath: String) {
        /** Cached helper status once read (guarded by this object's monitor). */
        var status: Pair<Int, String>? = null

        fun delete() {
            File(data).delete()
            File(statusPath).delete()
        }

        companion object {
            fun create(cacheDir: File): Pipes {
                val id = UUID.randomUUID().toString()
                val pipes = Pipes(File(cacheDir, "dftb-nfs-$id.data").absolutePath, File(cacheDir, "dftb-nfs-$id.status").absolutePath)
                try {
                    Os.mkfifo(pipes.data, FIFO_MODE)
                    Os.mkfifo(pipes.statusPath, FIFO_MODE)
                } catch (e: Exception) {
                    pipes.delete()
                    throw StorageException(StorageException.Reason.PROTOCOL, "Cannot create transfer pipe: ${e.message}", e)
                }
                return pipes
            }
        }
    }

    companion object {
        private const val TAG = "RootFifoTransfer"
        private const val FIFO_MODE = 384 // 0600
        private const val LAUNCH_TIMEOUT_MILLIS = 30_000L
        private const val OPEN_TIMEOUT_MILLIS = 30_000L
        /** Status arrives after the helper closes the target; NFS may still be flushing a large tail. */
        private const val STATUS_TIMEOUT_MILLIS = 600_000L

        /**
         * Run a blocking FIFO [open]; if it has not returned after [timeoutMillis], call [unblock]
         * (which opens the other end non-blockingly so the kernel releases the waiter). Returns the
         * opened stream and whether the watchdog fired.
         */
        fun <T> openWithWatchdog(timeoutMillis: Long, unblock: () -> Unit, open: () -> T): Pair<T, Boolean> {
            val done = AtomicBoolean(false)
            val fired = AtomicBoolean(false)
            val watchdog = thread(isDaemon = true, name = "dftb-fifo-watchdog") {
                try {
                    Thread.sleep(timeoutMillis)
                } catch (ignored: InterruptedException) {
                    return@thread
                }
                if (!done.get()) {
                    fired.set(true)
                    runCatching(unblock).onFailure { DftbLog.w(TAG, "FIFO watchdog unblock failed", it) }
                }
            }
            try {
                val result = open()
                return result to fired.get()
            } finally {
                done.set(true)
                watchdog.interrupt()
            }
        }

        /** Open and immediately close [path] with O_NONBLOCK so a blocked opposite-end open returns. */
        private fun unblockOpen(path: String, accessMode: Int) {
            try {
                val fd = Os.open(path, accessMode or OsConstants.O_NONBLOCK, 0)
                Os.close(fd)
            } catch (e: Exception) {
                throw IOException("unblock $path: ${e.message}", e)
            }
        }
    }
}
