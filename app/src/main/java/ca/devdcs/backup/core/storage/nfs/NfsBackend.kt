package ca.devdcs.backup.core.storage.nfs

import android.content.Context
import ca.devdcs.backup.R
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.core.storage.ssh.NetCredentials
import ca.devdcs.backup.core.storage.ssh.PosixShell
import ca.devdcs.backup.core.storage.ssh.RemotePaths
import ca.devdcs.backup.core.storage.ssh.StorageStrings
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StorageBackend] for NFS exports. There is no usable pure-Java NFS client, and Android forbids
 * normal apps from mounting, so this backend only works in Root mode: it checks the kernel's NFS
 * support (`/proc/filesystems`), mounts `host:/export` with the kernel client under
 * `/data/local/tmp/dftb_mnt/<destinationId>`, and operates there through root shell commands.
 *
 * Config: `host`, `basePath` = export path (or `options["export"]` = export and `basePath` = folder
 * inside it), `options["mountOptions"]` merged over [NfsMountPlanner.DEFAULT_OPTIONS]. Sessions on
 * the same destination share one mount (reference-counted); the last close lazily unmounts it.
 */
@Singleton
class NfsBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: CommandExecutorRouter,
    private val strings: StorageStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) : StorageBackend {

    override val types: Set<DestinationType> = setOf(DestinationType.NFS)

    private val mountLock = Mutex()
    /** Open sessions per mountpoint (guarded by [mountLock]). */
    private val mountUsers = HashMap<String, Int>()

    /**
     * Deliberately not wrapped in `withContext`: once [acquireMount] has counted this session, the
     * session is returned without another suspension point, so a cancellation can never drop it
     * (and leak the mount reference) between counting and hand-off. Blocking DNS runs on [io].
     */
    override suspend fun connect(destination: Destination): StorageSession {
        val executor = requireRoot()
        val config = destination.config
        val host = NetCredentials.requireHost(destination, strings)
        val target = NfsMountPlanner.resolveTarget(config.basePath, config.options[OPTION_EXPORT])
            ?: throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_nfs_missing_export))
        val userOptions = config.options[OPTION_MOUNT_OPTIONS]?.trim()?.takeIf { it.isNotEmpty() }
        if (userOptions != null && (!NfsMountPlanner.optionsAreSafe(userOptions) || NfsMountPlanner.weakensSecurity(userOptions))) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_nfs_bad_options))
        }
        val address = withContext(io) { resolve(host) }
        val hardened = NfsMountPlanner.mergeOptions(NfsMountPlanner.DEFAULT_OPTIONS, userOptions, address, noSymFollow = true)
        val fallback = NfsMountPlanner.mergeOptions(NfsMountPlanner.DEFAULT_OPTIONS, userOptions, address, noSymFollow = false)
        if (!NfsMountPlanner.optionsAreSafe(hardened)) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_nfs_bad_options))
        }
        val mountPoint = NfsMountPlanner.mountPoint(destination.id)
        val source = NfsMountPlanner.source(address, target.export)

        val base = RemotePaths.join(mountPoint, target.subPath)
        acquireMount(executor, mountPoint, hardened, fallback, source)
        DftbLog.i(TAG, "NFS session open: $source at $mountPoint (base=$base)")
        return NfsSession(
            destination = destination,
            executor = executor,
            mountPoint = mountPoint,
            transfer = RootFifoTransfer(executor, context.cacheDir, strings),
            basePath = base,
            strings = strings,
            io = io,
            onClose = { appScope.launch(io) { releaseMount(executor, mountPoint) } },
        )
    }

    /** The ROOT executor when the device owner runs in Root mode and root is granted; else UNSUPPORTED. */
    private suspend fun requireRoot(): CommandExecutor {
        val executor = router.current()
        if (executor.tier != ElevationTier.ROOT) {
            throw StorageException(StorageException.Reason.UNSUPPORTED, strings.get(R.string.storage_nfs_requires_root))
        }
        if (!executor.isAvailable()) {
            throw StorageException(StorageException.Reason.UNSUPPORTED, strings.get(R.string.storage_nfs_root_unavailable))
        }
        return executor
    }

    private fun resolve(host: String): String = try {
        InetAddress.getByName(host.removePrefix("[").removeSuffix("]")).hostAddress
            ?: throw UnknownHostException(host)
    } catch (e: UnknownHostException) {
        throw StorageException(
            StorageException.Reason.NETWORK,
            strings.get(R.string.storage_net_connect_failed, host, e.message ?: "unknown host"),
            e,
        )
    }

    /**
     * Mount with [hardened] options (incl. nosymfollow); if the kernel/toybox rejects them for a
     * reason other than reachability/permissions, retry once with [fallback] (no nosymfollow; the
     * per-command symlink guards in [NfsSession] still confine every root operation).
     */
    private suspend fun acquireMount(executor: CommandExecutor, mountPoint: String, hardened: String, fallback: String, source: String) {
        mountLock.withLock {
            val users = mountUsers[mountPoint] ?: 0
            if (users == 0) {
                val filesystems = NfsMountPlanner.parseFilesystems(run(executor, "cat /proc/filesystems").stdout)
                val fsType = NfsMountPlanner.chooseFsType(filesystems, hardened)
                    ?: throw StorageException(StorageException.Reason.UNSUPPORTED, strings.get(R.string.storage_nfs_kernel_unsupported))
                // A mount left behind by a crash may carry stale options: replace it.
                if (NfsMountPlanner.isMounted(run(executor, "cat /proc/mounts").stdout, mountPoint)) {
                    run(executor, NfsMountPlanner.unmountScript(mountPoint))
                }
                var result = run(executor, NfsMountPlanner.mountScript(fsType, hardened, source, mountPoint), MOUNT_TIMEOUT_MILLIS)
                if (!result.isSuccess && failureReason(result) !in NON_OPTION_FAILURES) {
                    DftbLog.w(TAG, "Mount with ${NfsMountPlanner.NOSYMFOLLOW} refused; retrying without it")
                    run(executor, NfsMountPlanner.unmountScript(mountPoint))
                    result = run(executor, NfsMountPlanner.mountScript(fsType, fallback, source, mountPoint), MOUNT_TIMEOUT_MILLIS)
                }
                if (!result.isSuccess) {
                    run(executor, NfsMountPlanner.unmountScript(mountPoint))
                    throw StorageException(failureReason(result), strings.get(R.string.storage_nfs_mount_failed, source, failureDetail(result)))
                }
            }
            mountUsers[mountPoint] = users + 1
        }
    }

    private fun failureDetail(result: CommandResult): String =
        result.stderrText.ifBlank { result.stdoutText }.ifBlank { "exit ${result.exitCode}" }.trim()

    private fun failureReason(result: CommandResult): StorageException.Reason =
        if (result.exitCode == CommandResult.EXIT_TIMEOUT) StorageException.Reason.TIMEOUT else PosixShell.classifyError(failureDetail(result))

    private suspend fun releaseMount(executor: CommandExecutor, mountPoint: String) {
        mountLock.withLock {
            val remaining = (mountUsers[mountPoint] ?: 1) - 1
            if (remaining > 0) {
                mountUsers[mountPoint] = remaining
                return@withLock
            }
            mountUsers.remove(mountPoint)
            val result = run(executor, NfsMountPlanner.unmountScript(mountPoint))
            if (!result.isSuccess) DftbLog.w(TAG, "Unmount of $mountPoint reported: ${result.stderrText}")
        }
    }

    private suspend fun run(executor: CommandExecutor, script: String, timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS): CommandResult {
        val result = executor.execute(Command(listOf("sh", "-c", script), timeoutMillis = timeoutMillis))
        if (!result.launched) {
            throw StorageException(StorageException.Reason.UNSUPPORTED, strings.get(R.string.storage_nfs_root_unavailable))
        }
        return result
    }

    private companion object {
        const val TAG = "NfsBackend"
        const val OPTION_EXPORT = "export"
        const val OPTION_MOUNT_OPTIONS = "mountOptions"
        const val COMMAND_TIMEOUT_MILLIS = 30_000L
        const val MOUNT_TIMEOUT_MILLIS = 90_000L

        /** Mount failures that an option change cannot fix: no retry without nosymfollow. */
        val NON_OPTION_FAILURES = setOf(
            StorageException.Reason.TIMEOUT,
            StorageException.Reason.NETWORK,
            StorageException.Reason.PERMISSION,
            StorageException.Reason.NOT_FOUND,
        )
    }
}
