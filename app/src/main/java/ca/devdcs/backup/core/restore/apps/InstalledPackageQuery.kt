package ca.devdcs.backup.core.restore.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.os.Build
import android.os.Process
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** What the device currently has of one package (package-level facts are shared by all users). */
data class InstalledPackage(
    val packageName: String,
    val versionCode: Long,
    val signers: List<String>,
    val pastSigners: List<String>,
    val requestedPermissions: Set<String>,
    /** Granted for DFTB's own user (empty when unknown / other user). */
    val grantedForCaller: Set<String>,
    /** appId (uid without the user part). */
    val appId: Int,
    val installedForCaller: Boolean,
    val isSystem: Boolean,
)

/**
 * Reads package state for restore decisions. PackageManager answers for DFTB's own user (with
 * MATCH_UNINSTALLED_PACKAGES it also sees packages installed only for other users); a shell tier
 * answers "installed for user N, with which uid" for other users. Exists so the restorer always
 * works from the CURRENT uid and signers, re-queried after an install.
 */
@Singleton
class InstalledPackageQuery @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    val callerUserId: Int get() = AppIds.userId(Process.myUid())

    suspend fun packageInfo(packageName: String): InstalledPackage? = withContext(io) {
        val info = load(packageName) ?: return@withContext null
        val app = info.applicationInfo ?: return@withContext null
        val signing = info.signingInfo
        val current = signing?.apkContentsSigners.orEmpty().map(::sha256)
        val past = if (signing != null && signing.hasPastSigningCertificates()) signing.signingCertificateHistory.orEmpty().map(::sha256) else emptyList()
        val requested = info.requestedPermissions.orEmpty()
        val flags = info.requestedPermissionsFlags
        val granted = requested.filterIndexed { i, _ -> flags != null && i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
        InstalledPackage(
            packageName = packageName,
            versionCode = info.longVersionCode,
            signers = current,
            pastSigners = past,
            requestedPermissions = requested.toSet(),
            grantedForCaller = granted.toSet(),
            appId = AppIds.appId(app.uid),
            installedForCaller = (app.flags and ApplicationInfo.FLAG_INSTALLED) != 0,
            isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }

    /**
     * uid of [packageName] for [userId], or null when it is not installed for that user. Uses the
     * shell when [executor] is privileged (authoritative for any user), PackageManager otherwise.
     */
    suspend fun uidForUser(packageName: String, userId: Int, executor: CommandExecutor): Int? = withContext(io) {
        if (executor.tier != ElevationTier.NONE) {
            val result = executor.execute(Command(listOf("sh", "-c", AppRestoreScripts.listPackageUid(packageName, userId)), timeoutMillis = SHELL_TIMEOUT_MILLIS))
            if (result.launched && result.exitCode == 0) return@withContext AppRestoreScripts.parsePackageUid(result.stdout, packageName, userId)
        }
        if (userId != callerUserId) return@withContext null
        val info = packageInfo(packageName) ?: return@withContext null
        if (!info.installedForCaller) null else AppIds.uid(userId, info.appId)
    }

    /** User ids that exist on the device (shell only); null when they cannot be listed. */
    suspend fun userIds(executor: CommandExecutor): Set<Int>? = withContext(io) {
        if (executor.tier == ElevationTier.NONE) return@withContext null
        val result = executor.execute(Command(listOf("sh", "-c", AppRestoreScripts.LIST_USERS), timeoutMillis = SHELL_TIMEOUT_MILLIS))
        if (!result.launched || result.exitCode != 0) null else AppRestoreScripts.parseUserIds(result.stdout).takeIf { it.isNotEmpty() }
    }

    /** How each permission can be granted on this device. */
    fun permissionKinds(names: Collection<String>): Map<String, PermissionKind> = names.associateWith { name ->
        try {
            val info = context.packageManager.getPermissionInfo(name, 0)
            val base = info.protection
            val flags = info.protectionFlags
            when {
                base == PermissionInfo.PROTECTION_DANGEROUS -> PermissionKind.RUNTIME
                (flags and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0 -> PermissionKind.DEVELOPMENT
                else -> PermissionKind.SYSTEM_MANAGED
            }
        } catch (e: PackageManager.NameNotFoundException) {
            PermissionKind.UNDEFINED
        } catch (e: RuntimeException) {
            DftbLog.w(TAG, "getPermissionInfo($name) failed", e)
            PermissionKind.UNDEFINED
        }
    }

    private fun load(packageName: String): PackageInfo? = try {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        DftbLog.w(TAG, "getPackageInfo($packageName) failed", e)
        null
    }

    private fun sha256(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "InstalledPackageQuery"
        const val SHELL_TIMEOUT_MILLIS = 30_000L
    }
}
