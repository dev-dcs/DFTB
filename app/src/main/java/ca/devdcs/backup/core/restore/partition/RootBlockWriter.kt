package ca.devdcs.backup.core.restore.partition

import android.content.Context
import android.system.Os
import android.system.OsConstants
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.root.RootScriptBuilder
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.source.PrivilegedStreamScripts
import ca.devdcs.backup.core.storage.nfs.RootFifoTransfer
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A failed block write. [deviceTouched] is false only when the helper's guards refused before `dd`
 * ran, i.e. the partition is guaranteed unchanged.
 */
class BlockWriteException(
    val exitCode: Int,
    val detail: String,
    val deviceTouched: Boolean,
    cause: Throwable? = null,
) : IOException("block write failed (exit $exitCode): $detail", cause)

/** Pure script builders for [RootBlockWriter] (quoting runs as root, so it is unit-tested). */
object BlockWriteScripts {

    const val DD_BLOCK_SIZE = 4_194_304
    const val EXIT_BLOCKDEV_FAILED = 90
    const val EXIT_SIZE_MISMATCH = 91
    const val EXIT_NOT_BLOCK = 92
    const val EXIT_SETRW_FAILED = 93
    const val EXIT_READ_ONLY = 94
    const val EXIT_HELD = 95
    const val EXIT_MOUNTED = 96

    /** Exit codes that mean the guards stopped before `dd` opened the device. */
    val GUARD_EXITS: Set<Int> = setOf(EXIT_BLOCKDEV_FAILED, EXIT_SIZE_MISMATCH, EXIT_NOT_BLOCK, EXIT_SETRW_FAILED, EXIT_READ_ONLY, EXIT_HELD, EXIT_MOUNTED)

    private fun q(s: String) = RootScriptBuilder.shellQuote(s)

    /**
     * Re-checks, in the same root call that writes, that [device] is still a block device of exactly
     * [expectedSize] bytes, has no sysfs holder (dm device stacked on it), is not a mount or swap
     * source in this or init's namespace, and is writable ([setReadWrite] first runs
     * `blockdev --setrw`, needed for device-mapper nodes), then copies stdin to it with `dd ... conv=fsync` so the exit status is
     * only 0 once the data reached the device.
     */
    fun writeScript(device: String, expectedSize: Long, setReadWrite: Boolean): String {
        val d = q(device)
        return "[ -b $d ] || { echo 'not a block device' >&2; exit $EXIT_NOT_BLOCK; }; " +
            "__sz=\$(blockdev --getsize64 $d) || { echo 'blockdev --getsize64 failed' >&2; exit $EXIT_BLOCKDEV_FAILED; }; " +
            "if [ \"\$__sz\" != '$expectedSize' ]; then echo \"size changed: expected $expectedSize, device reports \$__sz\" >&2; exit $EXIT_SIZE_MISMATCH; fi; " +
            "__r=\$(readlink -f $d 2>/dev/null); [ -n \"\$__r\" ] || __r=$d; __n=\${__r##*/}; " +
            "for __h in /sys/class/block/\"\$__n\"/holders/*; do if [ -e \"\$__h\" ]; then echo \"in use by device-mapper holder \${__h##*/}\" >&2; exit $EXIT_HELD; fi; done; " +
            "for __s in \$( { cat /proc/mounts /proc/1/mounts /proc/swaps 2>/dev/null; } | tr '\\t' ' ' | cut -d' ' -f1 | sort -u ); do " +
            "case \"\$__s\" in /dev/*) if [ \"\$(readlink -f \"\$__s\" 2>/dev/null)\" = \"\$__r\" ]; then echo \"mounted or used as swap via \$__s\" >&2; exit $EXIT_MOUNTED; fi;; esac; done; " +
            (if (setReadWrite) "blockdev --setrw $d || { echo 'blockdev --setrw failed' >&2; exit $EXIT_SETRW_FAILED; }; " else "") +
            "if [ \"\$(blockdev --getro $d)\" != '0' ]; then echo 'device is read-only' >&2; exit $EXIT_READ_ONLY; fi; " +
            "dd of=$d bs=$DD_BLOCK_SIZE conv=fsync"
    }

    /**
     * Detached helper body: open the data FIFO for reading (fd 3), run [script] with stdin from it
     * and stderr captured, close it, then report `rc\nstderr` through the status FIFO.
     */
    fun fifoHelper(script: String, dataFifo: String, statusFifo: String): String =
        "exec 3<${q(dataFifo)} || exit 1; " +
            "__err=\$( { $script ; } <&3 2>&1 >/dev/null 3<&- ); __rc=\$?; exec 3<&-; " +
            "printf '%s\\n%s\\n' \"\$__rc\" \"\$(printf '%s' \"\$__err\" | tail -c ${PrivilegedStreamScripts.MAX_STDERR_CHARS})\" > ${q(statusFifo)}"

    /** Read-back of the first bytes of [device]; `--flushbufs` drops cached blocks so the media is read. */
    fun readBackScript(device: String): String {
        val d = q(device)
        return "blockdev --flushbufs $d >/dev/null 2>&1; dd if=$d bs=$DD_BLOCK_SIZE"
    }
}

/**
 * Streams bytes from the app into a block device through a detached root `dd`, the write-direction
 * twin of [ca.devdcs.backup.core.pipeline.source.RootByteStreams]: a data FIFO and a status FIFO
 * in the app cache dir, a `setsid` helper reading the data FIFO, and only the short launch/kill
 * commands on the shared root shell. Exists because [CommandExecutor] cannot carry gigabytes of
 * binary stdin and must not be blocked for the duration of a flash.
 */
@Singleton
class RootBlockWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /**
     * Open a stream into [device]. Nothing reaches the device until the guards of
     * [BlockWriteScripts.writeScript] pass. The caller writes and then MUST call
     * [BlockWriteStream.close], which returns only after `dd` exited 0 (fsync done) and throws
     * [BlockWriteException] otherwise.
     */
    suspend fun open(executor: CommandExecutor, device: String, expectedSize: Long, setReadWrite: Boolean, label: String): BlockWriteStream = withContext(io) {
        val pipes = Pipes.create(context.cacheDir)
        val helper = BlockWriteScripts.fifoHelper(BlockWriteScripts.writeScript(device, expectedSize, setReadWrite), pipes.data, pipes.status)
        val launch = try {
            executor.execute(Command(listOf("sh", "-c", PrivilegedStreamScripts.detachedLaunch(helper)), timeoutMillis = LAUNCH_TIMEOUT_MILLIS))
        } catch (e: CancellationException) {
            pipes.delete()
            throw e
        }
        val pid = if (launch.isSuccess) PrivilegedStreamScripts.parsePid(launch.stdout) else null
        if (pid == null) {
            pipes.delete()
            throw BlockWriteException(launch.exitCode, "$label: could not start the root helper (${launch.stderrText.ifBlank { "exit ${launch.exitCode}" }})", deviceTouched = false)
        }
        val (stream, timedOut) = try {
            runInterruptible {
                RootFifoTransfer.openWithWatchdog(OPEN_TIMEOUT_MILLIS, unblock = { unblockOpen(pipes.data, OsConstants.O_RDONLY) }) {
                    FileOutputStream(pipes.data)
                }
            }
        } catch (e: Throwable) {
            kill(executor, pid, pipes)
            throw if (e is CancellationException) e else BlockWriteException(-1, "$label: cannot open the data pipe: ${e.message}", deviceTouched = false, cause = e)
        }
        if (timedOut) {
            runCatching { stream.close() }
            kill(executor, pid, pipes)
            throw BlockWriteException(-1, "$label: the root helper did not start in time", deviceTouched = false)
        }
        BlockWriteStream(stream, pipes, label) { kill(executor, pid, pipes) }
    }

    /** Kill the helper's process group off the caller's thread, then remove the FIFOs. */
    private fun kill(executor: CommandExecutor, pid: Int, pipes: Pipes) {
        appScope.launch(io + NonCancellable) {
            val result = executor.execute(Command(listOf("sh", "-c", PrivilegedStreamScripts.killScript(pid)), timeoutMillis = KILL_TIMEOUT_MILLIS))
            if (!result.isSuccess) DftbLog.w(TAG, "kill of block writer $pid failed: ${result.stderrText}")
            drainNonBlocking(pipes.status)
            pipes.delete()
        }
    }

    /**
     * The open write end. [bytesWritten] counts bytes accepted by the pipe. A write failure (EPIPE
     * when the helper refused or dd died) is turned into a [BlockWriteException] carrying the
     * helper's own status.
     */
    inner class BlockWriteStream internal constructor(
        private val delegate: FileOutputStream,
        private val pipes: Pipes,
        private val label: String,
        private val abortHelper: () -> Unit,
    ) : OutputStream() {
        private val closed = AtomicBoolean(false)

        @Volatile
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            guard { delegate.write(b) }
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            guard { delegate.write(b, off, len) }
            bytesWritten += len
        }

        private inline fun guard(block: () -> Unit) {
            try {
                block()
            } catch (e: IOException) {
                closed.set(true)
                runCatching { delegate.close() }
                val (code, err) = readStatus(pipes)
                pipes.delete()
                throw failure(code, err.ifBlank { e.message ?: "broken pipe" }, e)
            }
        }

        /** EOF for dd, then wait for its exit (after fsync). Throws [BlockWriteException] unless it exited 0. */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                delegate.close()
            } catch (e: IOException) {
                val (code, err) = readStatus(pipes)
                pipes.delete()
                throw failure(code, err.ifBlank { e.message ?: "close failed" }, e)
            }
            val (code, err) = readStatus(pipes)
            pipes.delete()
            if (code != 0) throw failure(code, err.ifBlank { "no error output" }, null)
        }

        /** Abandon the write: kill dd. The device may hold a partial image afterwards. */
        fun abort() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { delegate.close() }
            abortHelper()
        }

        private fun failure(code: Int, detail: String, cause: Throwable?): BlockWriteException {
            val guarded = code in BlockWriteScripts.GUARD_EXITS
            return BlockWriteException(code, "$label: $detail", deviceTouched = !guarded, cause = cause)
        }
    }

    private fun readStatus(pipes: Pipes): Pair<Int, String> {
        val (input, timedOut) = RootFifoTransfer.openWithWatchdog(STATUS_TIMEOUT_MILLIS, unblock = { unblockOpen(pipes.status, OsConstants.O_WRONLY) }) {
            java.io.FileInputStream(pipes.status)
        }
        val text = input.use { String(it.readBytes(), Charsets.UTF_8) }
        if (timedOut && text.isBlank()) return -1 to "root helper did not report a status"
        return PrivilegedStreamScripts.parseStatus(text)
    }

    /** The data and status FIFOs of one write, private (0600) in the app cache dir. */
    internal class Pipes(val data: String, val status: String) {
        fun delete() {
            File(data).delete()
            File(status).delete()
        }

        companion object {
            fun create(cacheDir: File): Pipes {
                val id = UUID.randomUUID().toString()
                val pipes = Pipes(File(cacheDir, "dftb-blk-$id.data").absolutePath, File(cacheDir, "dftb-blk-$id.status").absolutePath)
                try {
                    Os.mkfifo(pipes.data, FIFO_MODE)
                    Os.mkfifo(pipes.status, FIFO_MODE)
                } catch (e: Exception) {
                    pipes.delete()
                    throw BlockWriteException(-1, "cannot create the write pipe: ${e.message}", deviceTouched = false, cause = e)
                }
                return pipes
            }
        }
    }

    private companion object {
        const val TAG = "RootBlockWriter"
        const val FIFO_MODE = 384 // 0600
        const val LAUNCH_TIMEOUT_MILLIS = 30_000L
        const val KILL_TIMEOUT_MILLIS = 15_000L
        const val OPEN_TIMEOUT_MILLIS = 30_000L
        /** dd's final fsync of a multi-GiB partition can take minutes on slow eMMC. */
        const val STATUS_TIMEOUT_MILLIS = 900_000L

        fun unblockOpen(path: String, accessMode: Int) {
            try {
                Os.close(Os.open(path, accessMode or OsConstants.O_NONBLOCK, 0))
            } catch (e: Exception) {
                throw IOException("unblock $path: ${e.message}", e)
            }
        }

        fun drainNonBlocking(path: String) {
            runCatching {
                val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
                try {
                    val buffer = ByteArray(PrivilegedStreamScripts.MAX_STDERR_CHARS + 64)
                    runCatching { Os.read(fd, buffer, 0, buffer.size) }
                } finally {
                    Os.close(fd)
                }
            }
        }
    }
}
