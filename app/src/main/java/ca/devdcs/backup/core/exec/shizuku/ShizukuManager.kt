package ca.devdcs.backup.core.exec.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import ca.devdcs.backup.BuildConfig
import ca.devdcs.backup.IShizukuUserService
import ca.devdcs.backup.core.environment.ShizukuProbe
import ca.devdcs.backup.core.exec.CommandException
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.MainDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Owns the single Shizuku user-service binding for the whole app. WHY a dedicated singleton:
 * binding a Shizuku user service is asynchronous, must survive the service dying and be re-bound
 * lazily, and must never be attempted concurrently with itself (two binds race on one
 * [ServiceConnection]). Centralising it here means [ShizukuCommandExecutor] can treat "give me the
 * live remote" as one suspending call and callers never juggle [ServiceConnection] state.
 *
 * Permission and binder-liveness are delegated to [ShizukuProbe] (the owner of the permission flow);
 * this class only binds once those preconditions hold and raises a [CommandException] otherwise.
 */
@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext context: Context,
    private val probe: ShizukuProbe,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) {

    private val serviceArgs: Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShizukuUserServiceImpl::class.java.name))
            .daemon(false)
            .processNameSuffix(PROCESS_SUFFIX)
            .debuggable(BuildConfig.DEBUG)
            // Tie the running service to this build: a new APK version forces a fresh service load.
            .version(BuildConfig.VERSION_CODE)

    private val bindMutex = Mutex()

    @Volatile
    private var remote: IShizukuUserService? = null

    /** The connection currently registered with Shizuku, kept so it can be unbound later. */
    private var activeConnection: ServiceConnection? = null

    /** True when a live, responsive remote is cached (cheap binder ping; no bind attempt). */
    val isBound: Boolean
        get() = remote?.asBinder()?.pingBinder() == true

    /**
     * The live remote, binding lazily on first use and rebinding transparently after the service
     * died or disconnected. Suspends until the user service is connected or [BIND_TIMEOUT_MS]
     * elapses. Throws [CommandException] if Shizuku is down, permission is missing, or binding
     * fails/times out — the caller (executor) turns that into a non-launched [CommandResult].
     */
    suspend fun service(): IShizukuUserService {
        remote?.let { if (it.asBinder().pingBinder()) return it }
        return bindMutex.withLock {
            remote?.let { if (it.asBinder().pingBinder()) return@withLock it }
            bindLocked()
        }
    }

    /** uid the user service runs as (0 root / 2000 adb), for diagnostics. Binds if necessary. */
    suspend fun processUid(): Int = service().processUid

    /** Unbind without forcing removal; a non-daemon service is torn down by Shizuku regardless. */
    suspend fun unbind() = teardown(remove = false)

    /** Unbind AND ask Shizuku to remove the service, triggering its [IShizukuUserService.destroy]. */
    suspend fun destroy() = teardown(remove = true)

    private suspend fun bindLocked(): IShizukuUserService {
        if (!probe.binderAlive.first()) {
            throw CommandException("Shizuku service is not running")
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrNull() != PackageManager.PERMISSION_GRANTED) {
            throw CommandException("Shizuku permission not granted")
        }
        // Drop any stale connection before registering a fresh one.
        activeConnection?.let { stale ->
            withContext(mainDispatcher) { runCatching { Shizuku.unbindUserService(serviceArgs, stale, false) } }
            activeConnection = null
            remote = null
        }

        val bound = withContext(mainDispatcher) {
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine<IShizukuUserService?> { continuation ->
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (binder == null || !binder.pingBinder()) {
                                DftbLog.w(TAG, "onServiceConnected with dead binder")
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }
                            val service = IShizukuUserService.Stub.asInterface(binder)
                            remote = service
                            DftbLog.i(TAG, "Shizuku user service connected")
                            if (continuation.isActive) continuation.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            DftbLog.w(TAG, "Shizuku user service disconnected")
                            remote = null
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            DftbLog.w(TAG, "Shizuku user service returned a null binding")
                            remote = null
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                    activeConnection = connection
                    continuation.invokeOnCancellation {
                        runCatching { Shizuku.unbindUserService(serviceArgs, connection, false) }
                    }
                    try {
                        Shizuku.bindUserService(serviceArgs, connection)
                    } catch (error: RuntimeException) {
                        DftbLog.w(TAG, "bindUserService threw", error)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }

        return bound ?: run {
            // Timed out or connected with a dead/null binder: clean up so the next call rebinds.
            activeConnection?.let { conn ->
                withContext(mainDispatcher) { runCatching { Shizuku.unbindUserService(serviceArgs, conn, false) } }
            }
            activeConnection = null
            remote = null
            throw CommandException("Timed out binding the Shizuku user service")
        }
    }

    private suspend fun teardown(remove: Boolean) = bindMutex.withLock {
        val connection = activeConnection
        if (connection != null) {
            withContext(mainDispatcher) {
                runCatching { Shizuku.unbindUserService(serviceArgs, connection, remove) }
                    .onFailure { DftbLog.w(TAG, "unbindUserService failed", it) }
                // Shizuku 13.1.5's remove=true path returns before clearing its client-side
                // connection cache, so a later rebind would reuse it and stack this stale
                // ServiceConnection; a second, non-removing unbind clears that cache.
                if (remove) {
                    runCatching { Shizuku.unbindUserService(serviceArgs, connection, false) }
                        .onFailure { DftbLog.w(TAG, "unbindUserService cache clear failed", it) }
                }
            }
        }
        activeConnection = null
        remote = null
    }

    private companion object {
        const val TAG = "ShizukuManager"
        /** Process name becomes `<app>:shizuku_exec`, distinguishing it in `ps`/diagnostics. */
        const val PROCESS_SUFFIX = "shizuku_exec"
        const val BIND_TIMEOUT_MS = 15_000L
    }
}
