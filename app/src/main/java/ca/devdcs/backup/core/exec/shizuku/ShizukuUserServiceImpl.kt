package ca.devdcs.backup.core.exec.shizuku

import android.util.Log
import ca.devdcs.backup.IShellCallback
import ca.devdcs.backup.IShizukuUserService
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Runs inside the Shizuku user-service process (uid 2000 = adb, or 0 = root) and is the ONLY thing
 * that process does: spawn child processes for the app and relay their output. It is loaded from
 * this APK's dex by the Shizuku server via a no-argument constructor, so it must stay free of any
 * Android UI, Hilt, or app-singleton dependency — none of that graph exists in this process.
 *
 * Each [execute] launches a [ProcessBuilder] child, pumps stdout/stderr to the caller's
 * [IShellCallback] on dedicated reader threads, and registers the child under an integer token so a
 * later [cancel] (or [destroy]) can kill it. The child is started under `setsid` (when the device
 * has it) so it leads its own process group, and killing means SIGKILL to that whole group: a
 * `sh -c 'tar … | zstd …'` pipeline must die together, not leave reparented grandchildren running. Reader threads block inside the synchronous callback,
 * which propagates backpressure from a slow app all the way down to the child via the OS pipe.
 */
class ShizukuUserServiceImpl : IShizukuUserService.Stub() {

    // The implicit public no-argument constructor is what the Shizuku user-service loader
    // reflects on (`clazz.getConstructor().newInstance()`); no Context-taking constructor is needed.

    private val tokens = AtomicInteger(0)
    private val children = ConcurrentHashMap<Int, Child>()

    /** A launched child and whether it leads its own process group (started via `setsid`). */
    private class Child(val process: Process, val ownsProcessGroup: Boolean)

    override fun getProcessUid(): Int = android.os.Process.myUid()

    override fun execute(
        argv: Array<String>,
        environment: Array<String>,
        workingDirectory: String?,
        stdin: ByteArray?,
        redirectErrorStream: Boolean,
        timeoutMillis: Long,
        callback: IShellCallback,
    ): Int {
        val useSetsid = SETSID.canExecute()
        val launchArgv = if (useSetsid) listOf(SETSID.path) + argv.toList() else argv.toList()
        val process = try {
            ProcessBuilder(launchArgv).apply {
                redirectErrorStream(redirectErrorStream)
                workingDirectory?.let { directory(File(it)) }
                EnvironmentCodec.applyTo(environment(), environment)
            }.start()
        } catch (error: IOException) {
            Log.w(TAG, "Failed to launch ${argv.firstOrNull()}", error)
            return IShizukuUserService.LAUNCH_FAILED
        } catch (error: SecurityException) {
            Log.w(TAG, "Not permitted to launch ${argv.firstOrNull()}", error)
            return IShizukuUserService.LAUNCH_FAILED
        }

        val token = tokens.incrementAndGet()
        val child = Child(process, ownsProcessGroup = useSetsid)
        children[token] = child

        // Feed stdin on its own thread so a child that writes a lot before reading stdin cannot
        // dead-lock us against a full output pipe.
        thread(name = "dftb-shizuku-stdin-$token", isDaemon = true) {
            try {
                process.outputStream.use { out -> if (stdin != null) out.write(stdin) }
            } catch (_: IOException) {
                // Child closed stdin early (e.g. `head`); not an error.
            }
        }

        val stdoutPump = thread(name = "dftb-shizuku-out-$token", isDaemon = true) {
            pump(process.inputStream) { line -> callback.onStdout(line) }
        }
        val stderrPump = if (redirectErrorStream) {
            null
        } else {
            thread(name = "dftb-shizuku-err-$token", isDaemon = true) {
                pump(process.errorStream) { line -> callback.onStderr(line) }
            }
        }

        thread(name = "dftb-shizuku-wait-$token", isDaemon = true) {
            val exitCode = try {
                if (timeoutMillis > 0) {
                    if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        process.exitValue()
                    } else {
                        Log.w(TAG, "Killing token=$token after ${timeoutMillis}ms timeout")
                        kill(child)
                        IShizukuUserService.EXIT_TIMEOUT
                    }
                } else {
                    process.waitFor()
                }
            } catch (_: InterruptedException) {
                kill(child)
                IShizukuUserService.EXIT_TIMEOUT
            }
            // Drain ALL remaining output before signalling exit so onExit truly is the last event:
            // a slow app-side consumer can hold the pumps far beyond any fixed grace period, and
            // reporting exit early would silently truncate the stream. Leftover members of the
            // child's process group (which could hold the pipe open forever) are killed first, so
            // the pumps reach EOF; without a process group the wait is bounded and an incomplete
            // drain is reported as a failure, never as success.
            if (child.ownsProcessGroup) killGroupStragglers(child)
            val drainBudget = if (child.ownsProcessGroup) 0L else PUMP_JOIN_NO_GROUP_MS
            runCatching { stdoutPump.join(drainBudget) }
            runCatching { stderrPump?.join(drainBudget) }
            val drained = !stdoutPump.isAlive && stderrPump?.isAlive != true
            val reportedExit = if (drained) exitCode else {
                Log.w(TAG, "Output for token=$token not fully drained; reporting failure")
                kill(child)
                IShizukuUserService.EXIT_TIMEOUT
            }
            children.remove(token)
            try {
                callback.onExit(reportedExit)
            } catch (error: Exception) {
                // App-side binder died; nothing left to report to.
                Log.w(TAG, "onExit delivery failed for token=$token", error)
            }
        }

        return token
    }

    override fun cancel(token: Int) {
        children.remove(token)?.let(::kill)
    }

    override fun destroy() {
        Log.i(TAG, "destroy(): killing ${children.size} child process(es) and exiting")
        children.values.forEach { child -> runCatching { kill(child) } }
        children.clear()
        // Shizuku convention: a non-daemon user service terminates its own process on teardown.
        System.exit(0)
    }

    /**
     * SIGKILL [child]'s whole process group (when it leads one), then the child itself. The group
     * kill goes through toybox `kill`, since the framework's process-group API is hidden; the pid
     * comes from [pidOf]. Never throws.
     */
    /**
     * After the group leader has exited, SIGKILL any remaining members of its process group
     * (orphaned grandchildren that still hold the output pipe). Never throws.
     */
    private fun killGroupStragglers(child: Child) {
        val pid = pidOf(child.process)
        if (pid <= 0) return
        runCatching {
            ProcessBuilder("kill", "-9", "--", "-$pid").redirectErrorStream(true).start()
                .waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun kill(child: Child) {
        val pid = if (child.ownsProcessGroup) pidOf(child.process) else -1
        if (pid > 0) {
            runCatching {
                ProcessBuilder("kill", "-9", "--", "-$pid").redirectErrorStream(true).start()
                    .waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
            }.onFailure { Log.w(TAG, "Process-group kill failed for pid=$pid", it) }
        }
        runCatching { child.process.destroyForcibly() }
    }

    /**
     * The child's pid. `Process.pid()` is not part of the Android SDK surface, so this reads it
     * reflectively: the public `pid()` method where libcore exposes it, otherwise the private `pid`
     * field of libcore's `ProcessImpl`/`UNIXProcess` (present on every supported release). Returns
     * -1 if neither is available, in which case only the direct child is killed.
     */
    private fun pidOf(process: Process): Int {
        runCatching {
            val value = process.javaClass.getMethod("pid").invoke(process)
            if (value is Number && value.toInt() > 0) return value.toInt()
        }
        return runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        }.getOrDefault(-1)
    }

    /**
     * Read [stream] line by line, handing each line to [onLine] (a synchronous binder callback).
     * A binder/IO failure means the app or child is gone, so we stop quietly; the waiter thread
     * will still report an exit.
     */
    private fun pump(stream: InputStream, onLine: (String) -> Unit) {
        try {
            BufferedReader(InputStreamReader(stream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            }
        } catch (_: IOException) {
            // Stream closed (process ended or killed); normal termination of the pump.
        } catch (error: Exception) {
            // RemoteException: the app-side callback binder died mid-stream.
            Log.w(TAG, "Output pump stopped: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "DFTB/ShizukuUserSvc"
        /**
         * Without a process group, stragglers can't be killed as a unit, so the drain wait is
         * bounded; an incomplete drain is reported as a failure.
         */
        const val PUMP_JOIN_NO_GROUP_MS = 60_000L
        const val KILL_WAIT_MS = 2_000L
        /** toybox `setsid` (present on every supported release, API 29+). */
        val SETSID = File("/system/bin/setsid")
    }
}
