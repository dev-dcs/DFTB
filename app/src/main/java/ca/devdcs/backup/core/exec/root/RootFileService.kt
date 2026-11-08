package ca.devdcs.backup.core.exec.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import ca.devdcs.backup.core.exec.CommandException
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.shell.ShellInitializer
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.di.MainDispatcher
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The root-process half of the privileged file bridge. libsu launches this [RootService] in a
 * separate process running as uid 0 (via `app_process`); its [onBind] hands the app a
 * [FileSystemManager] binder so the main app process can read `/data/data`, write raw partition
 * images and stat block devices through a stable Binder IPC instead of parsing shell output.
 *
 * It must be declared as a `<service android:name> android:exported="false"` in the manifest (see
 * the build-stage request) so the system can resolve the component libsu starts; it otherwise has
 * no normal Android service lifecycle.
 */
class RootFileService : RootService() {

    /** Return the NIO file-system binder; libsu marshals it back to the app's [RootFileSystem]. */
    override fun onBind(intent: Intent): IBinder = FileSystemManager.getService()
}

/**
 * The app-process client for [RootFileService]. Exists so Phases 6–7 get a single, cached,
 * death-aware [FileSystemManager] without each caller re-implementing the bind handshake: it binds
 * the root service lazily on the main thread (libsu's `RootService.bind` requires it), caches the
 * resulting manager, and transparently rebinds if the root process dies. Reads/writes performed
 * through the returned [ExtendedFile]s run in the root process, so they bypass SELinux/app-sandbox
 * limits the way the device owner authorised.
 */
@Singleton
class RootFileSystem @Inject constructor(
    @ApplicationContext private val context: Context,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private class Binding(
        val connection: ServiceConnection,
        val binder: IBinder,
        val manager: FileSystemManager,
    )

    private val mutex = Mutex()
    @Volatile private var binding: Binding? = null

    /**
     * The live root [FileSystemManager], binding the root service on first use and rebinding if the
     * previous root process died. Suspends until the root service connects. Throws
     * [CommandException] if the bind cannot be established (root denied / timed out).
     */
    suspend fun fileSystemManager(): FileSystemManager = mutex.withLock {
        val current = binding
        if (current != null && current.binder.pingBinder()) {
            return current.manager
        }
        current?.let { unbindLocked(it) }
        bindLocked()
    }

    /** Resolve [path] to a root-process-backed [ExtendedFile] (binding the service if needed). */
    suspend fun getFile(path: String): ExtendedFile = fileSystemManager().getFile(path)

    /** Unbind the root service and drop the cached manager. Idempotent. */
    suspend fun close() = mutex.withLock {
        binding?.let { unbindLocked(it) }
        binding = null
    }

    private suspend fun bindLocked(): FileSystemManager {
        ShellInitializer.ensureInitialized()
        val connected = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                DftbLog.w(TAG, "Root file service disconnected; will rebind on next use")
                binding = null
            }

            override fun onBindingDied(name: ComponentName?) {
                DftbLog.w(TAG, "Root file service binding died; will rebind on next use")
                binding = null
            }
        }

        val intent = Intent(context, RootFileService::class.java)
        withContext(mainDispatcher) { RootService.bind(intent, connection) }

        val binder = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { connected.await() }
        if (binder == null) {
            withContext(mainDispatcher) { runCatching { RootService.unbind(connection) } }
            throw CommandException("Timed out binding root file service after ${BIND_TIMEOUT_MILLIS}ms")
        }

        val manager = try {
            withContext(ioDispatcher) { FileSystemManager.getRemote(binder) }
        } catch (error: Exception) {
            withContext(mainDispatcher) { runCatching { RootService.unbind(connection) } }
            throw CommandException("Failed to obtain root FileSystemManager", error)
        }

        DftbLog.i(TAG, "Root file service bound")
        binding = Binding(connection, binder, manager)
        return manager
    }

    private suspend fun unbindLocked(current: Binding) {
        withContext(mainDispatcher) { runCatching { RootService.unbind(current.connection) } }
        binding = null
    }

    companion object {
        private const val TAG = "RootFs"
        /** How long to wait for the root process to spawn and connect before giving up. */
        private const val BIND_TIMEOUT_MILLIS = 30_000L
    }
}
