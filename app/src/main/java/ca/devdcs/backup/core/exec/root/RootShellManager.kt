package ca.devdcs.backup.core.exec.root

import android.content.Context
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandEvent
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.shell.ShellInitializer
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of the libsu superuser shell for the Root tier.
 *
 * Exists so every privileged command in the app funnels through one place that: opens the `su`
 * shell lazily and only ever off the main thread (libsu's `Shell.getShell()` blocks on the
 * superuser prompt), translates the app's tier-agnostic [Command] into a shell line that honours
 * its environment / working directory / stderr and mount-namespace requirements, and enforces the
 * per-command timeout by really killing the command rather than leaking a stuck `dd`/`tar`. Both
 * [RootCommandExecutor] (buffered) and the streaming pipeline engines (Phases 6–7) build on it.
 *
 * libsu runs every job through ONE persistent `su` process and its `Future.cancel` does not stop a
 * running job, so a command that is merely "cancelled" keeps the shared shell busy and blocks every
 * later root command. To make timeouts and cancellation real, each command is launched as a
 * supervised background child of the shell (see [RootScriptBuilder.buildSupervised]) that records
 * its PID to a private file; aborting it means killing that PID's process group from a separate,
 * short-lived root shell, after which the shared shell's `wait` returns and it becomes idle again.
 *
 * The default libsu builder is installed once by [ShellInitializer] with `FLAG_MOUNT_MASTER` (so the
 * shell already sees a global mount view) and `FLAG_REDIRECT_STDERR` (so a command's stderr is
 * folded into stdout at the shell level). This manager never reconfigures that builder; it only
 * calls [ShellInitializer.ensureInitialized] defensively before touching a shell.
 */
@Singleton
class RootShellManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    /**
     * Whether a root shell is currently usable: the cached/opened libsu shell reports root, or the
     * superuser manager says this app is granted root. Opens the shell on IO; never throws.
     */
    suspend fun isRootAvailable(): Boolean = withContext(ioDispatcher) {
        ShellInitializer.ensureInitialized()
        runCatching {
            val shell = Shell.getShell()
            shell.isRoot || Shell.isAppGrantedRoot() == true
        }.getOrElse { error ->
            DftbLog.w(TAG, "Root availability probe failed", error)
            false
        }
    }

    /**
     * Run [command] to completion on the root shell and buffer its output. Honours the per-command
     * [Command.timeoutMillis] by killing the command's process group when it elapses (and on
     * coroutine cancellation), and feeds [Command.stdin] (when present) via a short-lived private
     * file the root shell reads back. Never throws: a launch failure, binder death or timeout
     * becomes a non-success [CommandResult].
     */
    suspend fun exec(command: Command): CommandResult = withContext(ioDispatcher) {
        val startNanos = System.nanoTime()
        ShellInitializer.ensureInitialized()
        val shell = acquireRootShell()
            ?: return@withContext CommandResult.notLaunched(SHELL_UNAVAILABLE, elapsedMillis(startNanos))

        var stdinFile: File? = null
        val pidFile = newPidFile()
        var running = false
        try {
            stdinFile = command.stdin?.let { writeStdinFile(it) }
            val script = RootScriptBuilder.buildSupervised(command, stdinFile?.absolutePath, pidFile.absolutePath)
            DftbLog.d(TAG, "exec: ${command.displayString()} (timeout=${command.timeoutMillis ?: "default"}ms)")

            // libsu's reader threads append while a timed-out caller snapshots, so both lists are
            // synchronized and only ever copied under their own monitor.
            val stdout: MutableList<String> = Collections.synchronizedList(ArrayList())
            val stderr: MutableList<String> = Collections.synchronizedList(ArrayList())
            val job = shell.newJob().add(script)
            if (command.redirectErrorStream) job.to(stdout) else job.to(stdout, stderr)
            val future = job.enqueue()
            running = true

            val result = awaitFuture(future, command.timeoutMillis)
            if (result == null) {
                DftbLog.w(TAG, "exec timed out after ${command.timeoutMillis}ms: ${command.displayString()}")
                killSupervised(pidFile)
                running = false
                // Once the child is dead the shell's `wait` returns quickly; let the gobblers drain.
                runCatching { future.get(KILL_DRAIN_MILLIS, TimeUnit.MILLISECONDS) }
                return@withContext CommandResult(
                    exitCode = CommandResult.EXIT_TIMEOUT,
                    stdout = snapshot(stdout),
                    stderr = if (command.redirectErrorStream) emptyList() else snapshot(stderr),
                    durationMillis = elapsedMillis(startNanos),
                    launched = true,
                )
            }
            running = false
            val out = result.out ?: snapshot(stdout)
            // With redirectErrorStream (or libsu's legacy shell-level stderr redirect) libsu hands
            // back the stdout list as `err` too; stderr was folded into stdout, so report none.
            val err = when {
                command.redirectErrorStream -> emptyList()
                result.err === result.out -> emptyList()
                else -> result.err ?: snapshot(stderr)
            }
            CommandResult(
                exitCode = result.code,
                stdout = out,
                stderr = err,
                durationMillis = elapsedMillis(startNanos),
                launched = true,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            DftbLog.e(TAG, "exec failed: ${command.displayString()}", error)
            CommandResult.notLaunched(error.message ?: error.javaClass.simpleName, elapsedMillis(startNanos))
        } finally {
            // Caller cancellation: the child is still running in the shared shell, so kill it from
            // app scope (this coroutine is already cancelled and must not block on the su spawn).
            if (running) applicationScope.launch(ioDispatcher) { killSupervised(pidFile) }
            stdinFile?.let { file -> if (!file.delete()) file.deleteOnExit() }
        }
    }

    /**
     * Run [command] on the root shell, emitting every stdout/stderr line as libsu reads it and a
     * terminal [CommandEvent.Exit]. The flow is cold: collecting launches the job, and cancelling
     * the collector kills the command's process group so a multi-hour `tar`/`dd` really stops and
     * the shared root shell is freed. Lines and the Exit are delivered with blocking sends, so a
     * slow collector applies backpressure (through the pipe to the child) instead of losing output.
     */
    fun stream(command: Command): Flow<CommandEvent> = callbackFlow {
        val startNanos = System.nanoTime()
        val pidFile = newPidFile()
        val finished = AtomicBoolean(false)

        val worker = launch(ioDispatcher) {
            ShellInitializer.ensureInitialized()
            val shell = acquireRootShell()
            if (shell == null) {
                finished.set(true)
                send(CommandEvent.Stderr(SHELL_UNAVAILABLE))
                send(CommandEvent.Exit(CommandResult.EXIT_NOT_LAUNCHED, elapsedMillis(startNanos)))
                close()
                return@launch
            }

            var stdinFile: File? = null
            try {
                stdinFile = command.stdin?.let { writeStdinFile(it) }
                val script = RootScriptBuilder.buildSupervised(command, stdinFile?.absolutePath, pidFile.absolutePath)
                DftbLog.d(TAG, "stream: ${command.displayString()}")

                val stdoutList = lineCallback { line -> trySendBlocking(CommandEvent.Stdout(line)) }
                val job = shell.newJob().add(script)
                if (command.redirectErrorStream) {
                    job.to(stdoutList)
                } else {
                    val stderrList = lineCallback { line -> trySendBlocking(CommandEvent.Stderr(line)) }
                    job.to(stdoutList, stderrList)
                }
                val future = job.enqueue()

                val result = awaitFuture(future, command.timeoutMillis)
                val exitCode = if (result == null) {
                    killSupervised(pidFile)
                    runCatching { future.get(KILL_DRAIN_MILLIS, TimeUnit.MILLISECONDS) }
                    CommandResult.EXIT_TIMEOUT
                } else {
                    result.code
                }
                finished.set(true)
                send(CommandEvent.Exit(exitCode, elapsedMillis(startNanos)))
                close()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                DftbLog.e(TAG, "stream failed: ${command.displayString()}", error)
                if (!finished.get()) killSupervised(pidFile)
                finished.set(true)
                trySend(CommandEvent.Stderr(error.message ?: error.javaClass.simpleName))
                trySend(CommandEvent.Exit(CommandResult.EXIT_NOT_LAUNCHED, elapsedMillis(startNanos)))
                close(error)
            } finally {
                stdinFile?.let { file -> if (!file.delete()) file.deleteOnExit() }
            }
        }

        awaitClose {
            // Collector walked away before the command ended: kill it so the shared shell frees up.
            if (finished.compareAndSet(false, true)) {
                applicationScope.launch(ioDispatcher) { killSupervised(pidFile) }
            }
            worker.cancel()
        }
    }

    /**
     * The shared libsu shell, but only if it is alive AND actually root. When `su` is denied (or the
     * prompt times out) libsu silently falls back to an unprivileged `sh`; running ROOT-tier commands
     * there would execute them as the app uid and report them as launched. In that case the cached
     * non-root shell is closed, so the next call retries `su` once the user grants root, and null
     * is returned. Call only off the main thread.
     */
    private fun acquireRootShell(): Shell? {
        val shell = runCatching { Shell.getShell() }.getOrNull() ?: return null
        if (!shell.isAlive) return null
        if (!shell.isRoot) {
            DftbLog.w(TAG, "libsu fell back to a non-root shell; refusing to run ROOT-tier commands")
            runCatching { shell.close() }
            return null
        }
        return shell
    }

    /**
     * Await the libsu [future], interruptibly so coroutine cancellation (including a surrounding
     * timeout) interrupts the blocked wait. Returns null when [timeoutMillis] elapses first; the
     * caller is responsible for killing the supervised command in that case.
     */
    private suspend fun awaitFuture(future: Future<Shell.Result>, timeoutMillis: Long?): Shell.Result? =
        if (timeoutMillis != null && timeoutMillis > 0) {
            withTimeoutOrNull(timeoutMillis) { runInterruptible { future.get() } }
        } else {
            runInterruptible { future.get() }
        }

    /**
     * Kill the supervised command whose PID was recorded in [pidFile]: its whole process group
     * first (it was started under `setsid`, so a `tar | zstd` pipeline dies together), then the PID
     * and its direct children as a fallback for devices without `setsid`. Runs on a separate,
     * short-lived root shell because the shared one is blocked in `wait` on that very command.
     * Blocking; never throws.
     */
    private fun killSupervised(pidFile: File) {
        val path = RootScriptBuilder.shellQuote(pidFile.absolutePath)
        val script = "p=\$(cat $path 2>/dev/null); " +
            "if [ -n \"\$p\" ]; then kill -9 -- -\"\$p\" 2>/dev/null; pkill -9 -P \"\$p\" 2>/dev/null; " +
            "kill -9 \"\$p\" 2>/dev/null; fi; rm -f $path"
        runCatching {
            val killer = Shell.Builder.create().setTimeout(KILLER_SHELL_TIMEOUT_SECONDS).build()
            try {
                killer.newJob().add(script).exec()
            } finally {
                killer.close()
            }
        }.onFailure { error -> DftbLog.e(TAG, "Failed to kill supervised root command", error) }
    }

    /** A fresh, not-yet-existing private path the supervised command writes its PID to. */
    private fun newPidFile(): File = File(context.cacheDir, "dftb-pid-${UUID.randomUUID()}")

    /**
     * Persist [bytes] to a private, app-owned file so the root shell can redirect a command's stdin
     * from it. Binary-safe and self-cleaning (the caller deletes it in a `finally`). Used instead of
     * piping through the persistent shell's own fd, which has no per-command EOF.
     */
    private fun writeStdinFile(bytes: ByteArray): File {
        val file = File.createTempFile("dftb-stdin-", ".bin", context.cacheDir)
        file.outputStream().use { it.write(bytes) }
        return file
    }

    /** Copy a synchronized list under its own monitor (required for safe iteration). */
    private fun snapshot(list: MutableList<String>): List<String> = synchronized(list) { list.toList() }

    /**
     * A [CallbackList] that forwards each appended line to [onLine] directly on libsu's reader
     * thread (the default constructor would hop to the main thread, where a blocking send could
     * freeze the UI).
     */
    private inline fun lineCallback(crossinline onLine: (String) -> Unit): CallbackList<String> =
        object : CallbackList<String>(Executor { task -> task.run() }) {
            override fun onAddElement(element: String) {
                onLine(element)
            }
        }

    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    companion object {
        private const val TAG = "RootShell"
        private const val SHELL_UNAVAILABLE = "root shell unavailable (root not granted)"
        private const val KILL_DRAIN_MILLIS = 5_000L
        private const val KILLER_SHELL_TIMEOUT_SECONDS = 10L
    }
}

/**
 * Pure (Android-free) translator from a tier-agnostic [Command] to a single POSIX `sh` line for the
 * libsu root shell. Kept separate from [RootShellManager] so the escaping and prefix ordering —
 * which is security-sensitive (a mis-quoted argument on a root shell is a command-injection) — is
 * unit-testable on the JVM without a device.
 */
internal object RootScriptBuilder {

    /** Single-quote [arg] so the root shell treats it as one literal token, even with spaces/`$`. */
    fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    /**
     * Build the shell line for [command]. Ordering: optional `nsenter` into the init (global) mount
     * namespace, then an `env` prefix for extra environment entries, then the quoted argv, then an
     * optional stdin redirect from [stdinRedirectPath], then `2>&1` when stderr must fold into
     * stdout; all wrapped in `cd <dir> && …` when a working directory is set.
     *
     * [FLAG_MOUNT_MASTER][com.topjohnwu.superuser.Shell.FLAG_MOUNT_MASTER] already gives the shell a
     * global mount view, so most callers need nothing extra; the explicit `nsenter -t 1 -m` is for
     * strict, consistent partition/`/data` snapshots that must run in PID 1's exact namespace.
     */
    fun build(command: Command, stdinRedirectPath: String? = null): String {
        val tokens = ArrayList<String>()
        if (command.requireGlobalMountNamespace) {
            tokens += listOf("nsenter", "--target", "1", "--mount")
        }
        if (command.environment.isNotEmpty()) {
            tokens += "env"
            command.environment.forEach { (key, value) -> tokens += shellQuote("$key=$value") }
        }
        command.argv.forEach { tokens += shellQuote(it) }

        var line = tokens.joinToString(" ")
        if (stdinRedirectPath != null) {
            line += " < " + shellQuote(stdinRedirectPath)
        }
        if (command.redirectErrorStream) {
            line += " 2>&1"
        }
        return command.workingDirectory?.let { dir -> "cd " + shellQuote(dir) + " && " + line } ?: line
    }

    /**
     * Wrap [build]'s line so it runs as a killable, supervised background child of the persistent
     * libsu shell: started under `setsid` (own process group, when the device has it) via `sh -c`,
     * with stdin from [stdinRedirectPath] or `/dev/null` (so it can never swallow libsu's own command
     * stream), its PID written to [pidFilePath], then `wait`ed on. The final `(exit $rc)` makes the
     * child's exit status the job's status without exiting the shared shell, and the PID file is
     * removed once the child ends.
     */
    fun buildSupervised(command: Command, stdinRedirectPath: String?, pidFilePath: String): String {
        val inner = shellQuote(build(command, stdinRedirectPath))
        val input = if (stdinRedirectPath == null) " < /dev/null" else ""
        val pid = shellQuote(pidFilePath)
        return "if command -v setsid >/dev/null 2>&1; then setsid sh -c $inner$input & " +
            "else sh -c $inner$input & fi; " +
            "__dftb_pid=\$!; echo \"\$__dftb_pid\" > $pid; wait \"\$__dftb_pid\"; __dftb_rc=\$?; " +
            "rm -f $pid; (exit \"\$__dftb_rc\")"
    }
}
