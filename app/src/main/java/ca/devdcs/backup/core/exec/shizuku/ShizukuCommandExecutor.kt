package ca.devdcs.backup.core.exec.shizuku

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import ca.devdcs.backup.IShellCallback
import ca.devdcs.backup.IShizukuUserService
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandEvent
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.environment.ShizukuProbe
import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [ElevationTier.SHIZUKU] [CommandExecutor]: runs commands in the user-authorized Shizuku
 * process (adb or root privileges) by brokering them through [ShizukuManager]'s user service.
 *
 * WHY it exists: it is the Tier-2 half of the execution contract the backup/restore engines depend
 * on, letting them run privileged shell tools without the device being rooted. It owns only the
 * translation between the app's [Command]/[CommandResult]/[CommandEvent] types and the remote
 * [IShizukuUserService]; the binder lifecycle lives in [ShizukuManager], and the permission flow in
 * [ShizukuProbe].
 */
@Singleton
class ShizukuCommandExecutor @Inject constructor(
    private val manager: ShizukuManager,
    private val probe: ShizukuProbe,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CommandExecutor {

    override val tier: ElevationTier = ElevationTier.SHIZUKU

    /** Cheap readiness check: the Shizuku binder is alive and this app holds its permission. */
    override suspend fun isAvailable(): Boolean = withContext(ioDispatcher) {
        val alive = withTimeoutOrNull(AVAILABILITY_PROBE_TIMEOUT_MS) { probe.binderAlive.first() } ?: false
        if (!alive) return@withContext false
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    }

    override suspend fun execute(command: Command): CommandResult = withContext(ioDispatcher) {
        val startedAt = System.nanoTime()
        oversizedStdinMessage(command)?.let { message ->
            return@withContext CommandResult.notLaunched(message, elapsedMillis(startedAt))
        }
        val service = try {
            manager.service()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return@withContext CommandResult.notLaunched(
                "Shizuku unavailable: ${error.message}",
                elapsedMillis(startedAt),
            )
        }

        val stdout = Collections.synchronizedList(ArrayList<String>())
        val stderr = Collections.synchronizedList(ArrayList<String>())
        val exit = CompletableDeferred<Int>()
        val serviceDied = AtomicBoolean(false)
        val callback = object : IShellCallback.Stub() {
            override fun onStdout(line: String) { stdout.add(line) }
            override fun onStderr(line: String) { stderr.add(line) }
            override fun onExit(exitCode: Int) { exit.complete(exitCode) }
        }

        // If the privileged process dies mid-command, onExit never arrives; resolve the wait so the
        // caller gets a failed result instead of hanging forever.
        val binder = service.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            DftbLog.w(TAG, "Shizuku service died during ${command.displayString()}")
            serviceDied.set(true)
            exit.complete(CommandResult.EXIT_NOT_LAUNCHED)
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (error: RemoteException) {
            return@withContext CommandResult.notLaunched("Shizuku service already dead", elapsedMillis(startedAt))
        }

        val timeout = command.timeoutMillis ?: DEFAULT_EXECUTE_TIMEOUT_MS
        try {
            val token = try {
                service.execute(
                    command.argv.toTypedArray(),
                    EnvironmentCodec.encode(command.environment),
                    command.workingDirectory,
                    command.stdin,
                    command.redirectErrorStream,
                    timeout,
                    callback,
                )
            } catch (error: RemoteException) {
                return@withContext CommandResult.notLaunched(
                    "Shizuku service failure launching ${command.displayString()}: ${error.message}",
                    elapsedMillis(startedAt),
                )
            }

            if (token < 0) {
                DftbLog.w(TAG, "Shizuku refused to launch: ${command.displayString()}")
                return@withContext CommandResult.notLaunched(
                    "Shizuku could not launch ${command.displayString()}",
                    elapsedMillis(startedAt),
                )
            }

            val exitCode = try {
                exit.await()
            } catch (error: CancellationException) {
                runCatching { service.cancel(token) }
                throw error
            }
            // The service died before reporting an exit: the outcome is unknown, so report it as a
            // binder-death failure (launched=false per the CommandResult contract), not an exit code.
            val died = serviceDied.get()
            CommandResult(
                exitCode = exitCode,
                stdout = synchronized(stdout) { stdout.toList() },
                stderr = if (died) {
                    synchronized(stderr) { stderr.toList() } + SERVICE_DIED_MESSAGE
                } else {
                    synchronized(stderr) { stderr.toList() }
                },
                durationMillis = elapsedMillis(startedAt),
                launched = !died,
            )
        } finally {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
    }

    override fun stream(command: Command): Flow<CommandEvent> = callbackFlow {
        val startedAt = System.nanoTime()
        oversizedStdinMessage(command)?.let { message ->
            send(CommandEvent.Stderr(message))
            send(CommandEvent.Exit(CommandResult.EXIT_NOT_LAUNCHED, elapsedMillis(startedAt)))
            close()
            return@callbackFlow
        }
        val service = try {
            manager.service()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DftbLog.w(TAG, "Shizuku unavailable for stream: ${error.message}")
            trySend(CommandEvent.Exit(CommandResult.EXIT_NOT_LAUNCHED, elapsedMillis(startedAt)))
            close()
            return@callbackFlow
        }

        val callback = object : IShellCallback.Stub() {
            // Blocking sends give backpressure: a slow collector throttles the remote reader thread,
            // which throttles the child process through the pipe, rather than buffering unboundedly.
            override fun onStdout(line: String) { trySendBlocking(CommandEvent.Stdout(line)) }
            override fun onStderr(line: String) { trySendBlocking(CommandEvent.Stderr(line)) }
            override fun onExit(exitCode: Int) {
                // Blocking too: the Exit must never be dropped behind a full buffer of output lines.
                trySendBlocking(CommandEvent.Exit(exitCode, elapsedMillis(startedAt)))
                close()
            }
        }

        // End the stream if the privileged process dies mid-command rather than leaving the
        // collector blocked waiting for an exit event that can never come.
        val binder = service.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            DftbLog.w(TAG, "Shizuku service died while streaming ${command.displayString()}")
            trySend(CommandEvent.Exit(CommandResult.EXIT_NOT_LAUNCHED, elapsedMillis(startedAt)))
            close()
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (error: RemoteException) {
            trySend(CommandEvent.Exit(CommandResult.EXIT_NOT_LAUNCHED, elapsedMillis(startedAt)))
            close()
            return@callbackFlow
        }

        val timeout = command.timeoutMillis ?: STREAM_NO_TIMEOUT
        val token = try {
            service.execute(
                command.argv.toTypedArray(),
                EnvironmentCodec.encode(command.environment),
                command.workingDirectory,
                command.stdin,
                command.redirectErrorStream,
                timeout,
                callback,
            )
        } catch (error: RemoteException) {
            DftbLog.w(TAG, "Shizuku service failure streaming ${command.displayString()}: ${error.message}")
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            trySend(CommandEvent.Exit(CommandResult.EXIT_NOT_LAUNCHED, elapsedMillis(startedAt)))
            close()
            return@callbackFlow
        }

        if (token < 0) {
            DftbLog.w(TAG, "Shizuku refused to stream: ${command.displayString()}")
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            trySend(CommandEvent.Exit(CommandResult.EXIT_NOT_LAUNCHED, elapsedMillis(startedAt)))
            close()
            return@callbackFlow
        }

        // Collection cancelled (or completed) before exit: kill the remote child and detach.
        awaitClose {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            runCatching { service.cancel(token) }
        }
    }.flowOn(ioDispatcher)

    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    /**
     * stdin crosses to the Shizuku process inline as `byte[]` in ONE binder transaction, whose
     * buffer is ~1 MB shared per process; anything near that fails with TransactionTooLargeException.
     * Reject it up front with a clear reason instead of an opaque binder failure. Bulk data belongs
     * in a file the command reads, not in stdin.
     */
    private fun oversizedStdinMessage(command: Command): String? {
        val size = command.stdin?.size ?: return null
        if (size <= MAX_INLINE_STDIN_BYTES) return null
        DftbLog.w(TAG, "Refusing ${command.displayString()}: stdin is $size bytes (max $MAX_INLINE_STDIN_BYTES)")
        return "stdin too large for the Shizuku tier ($size bytes, max $MAX_INLINE_STDIN_BYTES); pass a file instead"
    }

    private companion object {
        const val TAG = "ShizukuExec"
        /** Buffered [execute] default when a [Command] sets no timeout. Streaming jobs opt out below. */
        const val DEFAULT_EXECUTE_TIMEOUT_MS = 60_000L
        /** Streaming commands (tar/dd/rclone) run until done unless the caller sets a timeout. */
        const val STREAM_NO_TIMEOUT = 0L
        const val AVAILABILITY_PROBE_TIMEOUT_MS = 750L
        /** Well under the ~1 MB binder transaction buffer, leaving room for argv/env and other calls. */
        const val MAX_INLINE_STDIN_BYTES = 256 * 1024
        const val SERVICE_DIED_MESSAGE = "Shizuku service died before the command finished"
    }
}
