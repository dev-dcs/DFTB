package ca.devdcs.backup.core.restore.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Final state of an install session. */
sealed interface InstallOutcome {
    data object Success : InstallOutcome
    /** [message] is the platform's (English, technical) reason. */
    data class Failed(val message: String) : InstallOutcome
    /** The device owner declined the system prompt. */
    data object Declined : InstallOutcome
    /** Nobody answered the system prompt in time. */
    data object TimedOut : InstallOutcome
}

/** Byte copy with cancellation checks and progress; shared by installers and data extraction. */
internal object RestoreCopy {
    private const val BUFFER = 64 * 1024

    /** Copies exactly [size] bytes (or to EOF when [size] < 0); returns the bytes copied. */
    fun copy(input: InputStream, output: OutputStream, size: Long, isCancelled: () -> Boolean, onBytes: (Long) -> Unit): Long {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (size < 0 || total < size) {
            if (isCancelled()) throw CancellationException("restore cancelled")
            val want = if (size < 0) buffer.size else minOf(buffer.size.toLong(), size - total).toInt()
            val n = input.read(buffer, 0, want)
            if (n < 0) {
                if (size >= 0) throw IOException("stream ended after $total of $size bytes")
                break
            }
            output.write(buffer, 0, n)
            total += n
            onBytes(n.toLong())
        }
        return total
    }
}

/**
 * One multi-APK install transaction. Splits are written in the order the archive yields them
 * (Android does not care), then committed atomically.
 */
interface ApkInstallSession {
    suspend fun write(apk: PlannedApk, size: Long, input: InputStream, isCancelled: () -> Boolean, onBytes: (Long) -> Unit)
    suspend fun commit(): InstallOutcome
    /** Idempotent, never throws. */
    fun abandon()
}

/**
 * Standard-tier installs through the platform [PackageInstaller] (needs REQUEST_INSTALL_PACKAGES
 * and the per-app "Install unknown apps" grant). Exists for devices without root or Shizuku: the
 * system shows its own confirmation, which is surfaced through [AppRestoreEvents] because only a
 * foreground Activity may launch it.
 */
@Singleton
class StandardApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: AppRestoreEvents,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Tells the UI where the device owner grants "Install unknown apps" to DFTB. */
    fun requestInstallPermission(packageName: String) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        events.publish(AppRestoreEvent.InstallPermissionRequired(packageName, intent))
    }

    suspend fun create(packageName: String, label: String, totalBytes: Long?): ApkInstallSession = withContext(io) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (totalBytes != null) setSize(totalBytes)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = installer.createSession(params)
        StandardSession(installer, installer.openSession(id), id, packageName, label)
    }

    private inner class StandardSession(
        private val installer: PackageInstaller,
        private val session: PackageInstaller.Session,
        private val sessionId: Int,
        private val packageName: String,
        private val label: String,
    ) : ApkInstallSession {
        @Volatile private var closed = false

        override suspend fun write(apk: PlannedApk, size: Long, input: InputStream, isCancelled: () -> Boolean, onBytes: (Long) -> Unit) = withContext(io) {
            session.openWrite(apk.sessionName, 0, size).use { out ->
                RestoreCopy.copy(input, out, size, isCancelled, onBytes)
                session.fsync(out)
            }
        }

        override suspend fun commit(): InstallOutcome = withContext(io) {
            val action = "${context.packageName}.restore.INSTALL_STATUS.${UUID.randomUUID()}"
            val statuses = Channel<Intent>(Channel.UNLIMITED)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    statuses.trySend(intent)
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            var prompted = false
            try {
                val callback = Intent(action).setPackage(context.packageName)
                // Mutable: the installer fills in the status extras.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pending.intentSender)
                closed = true
                val outcome = withTimeoutOrNull(USER_ACTION_TIMEOUT_MILLIS) {
                    var result: InstallOutcome? = null
                    while (result == null) {
                        val intent = statuses.receive()
                        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                val confirm = userActionIntent(intent)
                                if (confirm == null) {
                                    result = InstallOutcome.Failed("installer requested confirmation without an intent")
                                } else {
                                    prompted = true
                                    events.publish(AppRestoreEvent.InstallConfirmationRequired(packageName, label, sessionId, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                                }
                            }
                            PackageInstaller.STATUS_SUCCESS -> result = InstallOutcome.Success
                            PackageInstaller.STATUS_FAILURE_ABORTED -> result = InstallOutcome.Declined
                            else -> result = InstallOutcome.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status")
                        }
                    }
                    result
                }
                if (outcome == null) {
                    runCatching { installer.abandonSession(sessionId) }
                    InstallOutcome.TimedOut
                } else {
                    outcome
                }
            } catch (e: CancellationException) {
                runCatching { installer.abandonSession(sessionId) }
                throw e
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
                statuses.close()
                if (prompted) events.publish(AppRestoreEvent.InstallConfirmationResolved(packageName, sessionId))
                runCatching { session.close() }
            }
        }

        override fun abandon() {
            if (closed) return
            closed = true
            runCatching { session.abandon() }.onFailure { DftbLog.w(TAG, "abandon session $sessionId failed", it) }
            runCatching { session.close() }
        }

        private fun userActionIntent(status: Intent): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                status.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                status.getParcelableExtra(Intent.EXTRA_INTENT)
            }
    }

    private companion object {
        const val TAG = "StandardApkInstaller"
        /** The device owner may be away from the phone; an unanswered prompt eventually fails the unit. */
        const val USER_ACTION_TIMEOUT_MILLIS = 30L * 60 * 1000
    }
}

/**
 * Root / Shizuku installs through `pm install-create` / `install-write` / `install-commit`, which
 * need no confirmation and can target any user, replace (`-r`) and, when allowed, downgrade
 * (`-d`). Each split is piped from the decoded archive into `pm install-write -S <size> ... -`
 * through a [PrivilegedSink]: the root FIFO helper streams it, the Shizuku tier stages it in
 * DFTB's shell-readable external dir first.
 */
@Singleton
class ShellApkInstaller @Inject constructor(
    private val sinks: PrivilegedSinkFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /** Returns the session or a failure message. */
    suspend fun create(executor: CommandExecutor, packageName: String, userId: Int, totalBytes: Long?, downgrade: Boolean, installer: String?): Pair<ApkInstallSession?, String?> = withContext(io) {
        val result = executor.execute(Command(listOf("sh", "-c", AppRestoreScripts.installCreate(packageName, userId, totalBytes, downgrade, installer)), redirectErrorStream = true, timeoutMillis = PM_TIMEOUT_MILLIS))
        val id = if (result.launched) AppRestoreScripts.parseSessionId(result.stdout) else null
        if (id == null) {
            null to (AppRestoreScripts.pmFailure(result.stdout) ?: result.stdoutText.ifBlank { result.stderrText.ifBlank { "exit ${result.exitCode}" } })
        } else {
            ShellSession(executor, id) to null
        }
    }

    /** `pm install-existing` for a package present for another user. Null = success, else the failure. */
    suspend fun installExisting(executor: CommandExecutor, packageName: String, userId: Int): String? = withContext(io) {
        val result = executor.execute(Command(listOf("sh", "-c", AppRestoreScripts.installExisting(packageName, userId)), redirectErrorStream = true, timeoutMillis = PM_TIMEOUT_MILLIS))
        val ok = result.launched && result.exitCode == 0 && result.stdout.none { it.contains("Failure") || it.contains("Error") || it.contains("not installed") }
        if (ok) null else (AppRestoreScripts.pmFailure(result.stdout) ?: result.stdoutText.ifBlank { result.stderrText.ifBlank { "exit ${result.exitCode}" } })
    }

    private inner class ShellSession(private val executor: CommandExecutor, private val sessionId: Int) : ApkInstallSession {
        @Volatile private var finished = false

        override suspend fun write(apk: PlannedApk, size: Long, input: InputStream, isCancelled: () -> Boolean, onBytes: (Long) -> Unit) {
            require(size >= 0) { "split size must be known" }
            val sink = sinks.open(
                executor,
                label = apk.sessionName,
                stdinScript = AppRestoreScripts.installWriteFromStdin(sessionId, apk.sessionName, size),
                fileScript = { path -> AppRestoreScripts.installWriteFromFile(sessionId, apk.sessionName, size, path) },
                expectedBytes = size,
            )
            try {
                withContext(io) { RestoreCopy.copy(input, sink.output, size, isCancelled, onBytes) }
            } catch (e: CancellationException) {
                sink.abort()
                throw e
            } catch (e: IOException) {
                // The consumer may have died first (EPIPE); its own report says why.
                val status = runCatching { sink.complete() }.getOrNull()
                throw IOException("pm install-write ${apk.sessionName}: ${status?.let { AppRestoreScripts.pmFailure(it.output) ?: it.text } ?: e.message}", e)
            }
            val status = sink.complete()
            if (!AppRestoreScripts.isPmSuccess(status.exitCode, status.output)) {
                throw IOException("pm install-write ${apk.sessionName}: ${AppRestoreScripts.pmFailure(status.output) ?: status.text.ifBlank { "exit ${status.exitCode}" }}")
            }
        }

        override suspend fun commit(): InstallOutcome = withContext(io) {
            finished = true
            val result = executor.execute(Command(listOf("sh", "-c", AppRestoreScripts.installCommit(sessionId)), redirectErrorStream = true, timeoutMillis = COMMIT_TIMEOUT_MILLIS))
            if (result.launched && AppRestoreScripts.isPmSuccess(result.exitCode, result.stdout)) {
                InstallOutcome.Success
            } else {
                abandonNow()
                InstallOutcome.Failed(AppRestoreScripts.pmFailure(result.stdout) ?: result.stdoutText.ifBlank { result.stderrText.ifBlank { "exit ${result.exitCode}" } })
            }
        }

        override fun abandon() {
            if (finished) return
            finished = true
            // Fire-and-forget: abandon must not suspend and must survive the caller's cancellation.
            appScope.launch(io + NonCancellable) { abandonNow() }
        }

        private suspend fun abandonNow() {
            val result = executor.execute(Command(listOf("sh", "-c", AppRestoreScripts.installAbandon(sessionId)), timeoutMillis = PM_TIMEOUT_MILLIS))
            if (!result.isSuccess) DftbLog.w(TAG, "pm install-abandon $sessionId: ${result.stderrText}")
        }
    }

    private companion object {
        const val TAG = "ShellApkInstaller"
        const val PM_TIMEOUT_MILLIS = 60_000L
        /** Commit runs verification and, on older releases, dexopt synchronously. */
        const val COMMIT_TIMEOUT_MILLIS = 10L * 60 * 1000
    }
}
