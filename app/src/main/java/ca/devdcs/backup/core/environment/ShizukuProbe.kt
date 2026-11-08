package ca.devdcs.backup.core.environment

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import rikka.sui.Sui
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Tier 2 diagnostics plus the two Shizuku interactions the UI needs: a permission request that
 * suspends until the user answers, and a binder liveness flow so screens can react when the
 * Shizuku service starts or dies. Every static `Shizuku.*` call that needs a live binder is
 * guarded by [Shizuku.pingBinder] because the API throws otherwise.
 */
@Singleton
class ShizukuProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: CheckRunner,
) {

    private val permissionMutex = Mutex()

    /**
     * True while the Shizuku binder is alive. Emits the current state on collection (sticky), then
     * every transition. Safe to collect from several places; listeners are removed on cancel.
     */
    val binderAlive: Flow<Boolean> = callbackFlow {
        val received = Shizuku.OnBinderReceivedListener { trySend(true) }
        val dead = Shizuku.OnBinderDeadListener { trySend(false) }
        trySend(safePing())
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        awaitClose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
        }
    }.distinctUntilChanged()

    suspend fun checks(): List<DiagnosticCheck> = listOf(
        managerInstalled(),
        binderAliveCheck(),
        apiVersionSupported(),
        permissionGranted(),
        serverVersion(),
        serverUid(),
        selinuxContext(),
    )

    /**
     * Asks Shizuku to authorize this app. Returns true if already or newly granted; false if the
     * service is down, pre-v11, permanently denied ("don't ask again") or the user refused.
     * Serialized so two screens cannot race on the same request code.
     */
    suspend fun requestPermission(): Boolean = permissionMutex.withLock {
        if (!safePing()) {
            DftbLog.w(TAG, "requestPermission: Shizuku binder not alive")
            return false
        }
        val preV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(true)
        if (preV11) {
            DftbLog.w(TAG, "requestPermission: Shizuku pre-v11 is unsupported")
            return false
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrNull() == PackageManager.PERMISSION_GRANTED) return true
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            // Shizuku semantics: true means the user previously denied with "don't ask again";
            // the prompt would be suppressed, so the only remedy is the Shizuku app itself.
            DftbLog.w(TAG, "requestPermission: permanently denied by the user in Shizuku")
            return false
        }
        suspendCancellableCoroutine { continuation ->
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            lateinit var deadListener: Shizuku.OnBinderDeadListener
            fun cleanup() {
                Shizuku.removeRequestPermissionResultListener(listener)
                Shizuku.removeBinderDeadListener(deadListener)
            }
            listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != PERMISSION_REQUEST_CODE) return
                    cleanup()
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    DftbLog.i(TAG, "Shizuku permission result: granted=$granted")
                    if (continuation.isActive) continuation.resume(granted)
                }
            }
            // If the service dies after the dialog is shown, the result callback never fires; react
            // to binder death so the request resolves instead of suspending forever under the mutex.
            deadListener = Shizuku.OnBinderDeadListener {
                DftbLog.w(TAG, "requestPermission: Shizuku binder died while awaiting result")
                cleanup()
                if (continuation.isActive) continuation.resume(false)
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.addBinderDeadListener(deadListener)
            continuation.invokeOnCancellation { cleanup() }
            try {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            } catch (error: RuntimeException) {
                DftbLog.w(TAG, "Shizuku.requestPermission threw", error)
                cleanup()
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    private suspend fun managerInstalled() = runner.run(
        DiagnosticIds.SHIZUKU_INSTALLED,
        R.string.diag_shizuku_installed_title,
        required = true,
        failureAction = DiagnosticAction.INSTALL_SHIZUKU,
    ) {
        if (runCatching { Sui.isSui() }.getOrDefault(false)) {
            return@run CheckOutcome(CheckStatus.PASS, detail = "Sui")
        }
        val versionName = installedVersionName(ShizukuProvider.MANAGER_APPLICATION_ID)
        if (versionName != null) {
            CheckOutcome(CheckStatus.PASS, detail = "Shizuku $versionName")
        } else {
            CheckOutcome(
                CheckStatus.FAIL,
                detail = ShizukuProvider.MANAGER_APPLICATION_ID,
                hintRes = R.string.diag_shizuku_installed_hint,
                action = DiagnosticAction.INSTALL_SHIZUKU,
            )
        }
    }

    private suspend fun binderAliveCheck() = runner.run(DiagnosticIds.SHIZUKU_BINDER, R.string.diag_shizuku_binder_title, required = true) {
        if (safePing()) CheckOutcome(CheckStatus.PASS) else CheckOutcome(CheckStatus.FAIL, hintRes = R.string.diag_shizuku_binder_hint)
    }

    private suspend fun apiVersionSupported() = runner.run(
        DiagnosticIds.SHIZUKU_VERSION_SUPPORTED,
        R.string.diag_shizuku_version_supported_title,
        required = true,
    ) {
        if (!safePing()) return@run CheckOutcome(CheckStatus.FAIL, hintRes = R.string.diag_shizuku_binder_hint)
        if (Shizuku.isPreV11()) {
            CheckOutcome(CheckStatus.FAIL, hintRes = R.string.diag_shizuku_version_supported_hint)
        } else {
            CheckOutcome(CheckStatus.PASS, detail = "v${Shizuku.getVersion()}")
        }
    }

    private suspend fun permissionGranted() = runner.run(
        DiagnosticIds.SHIZUKU_PERMISSION,
        R.string.diag_shizuku_permission_title,
        required = true,
        failureAction = DiagnosticAction.GRANT_SHIZUKU_PERMISSION,
    ) {
        if (!safePing()) return@run CheckOutcome(CheckStatus.FAIL, hintRes = R.string.diag_shizuku_binder_hint)
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            CheckOutcome(CheckStatus.PASS)
        } else {
            CheckOutcome(
                CheckStatus.FAIL,
                hintRes = R.string.diag_shizuku_permission_hint,
                action = DiagnosticAction.GRANT_SHIZUKU_PERMISSION,
            )
        }
    }

    private suspend fun serverVersion() = runner.run(DiagnosticIds.SHIZUKU_VERSION, R.string.diag_shizuku_version_title, required = false) {
        if (!safePing()) return@run CheckOutcome(CheckStatus.INFO)
        val version = Shizuku.getVersion()
        val patch = runCatching { Shizuku.getServerPatchVersion() }.getOrNull()
        CheckOutcome(CheckStatus.INFO, detail = if (patch != null && patch > 0) "API $version (patch $patch)" else "API $version")
    }

    private suspend fun serverUid() = runner.run(DiagnosticIds.SHIZUKU_UID, R.string.diag_shizuku_uid_title, required = false) {
        if (!safePing()) return@run CheckOutcome(CheckStatus.INFO)
        val uid = Shizuku.getUid()
        val hint = when (uid) {
            UID_ROOT -> R.string.diag_shizuku_uid_root_hint
            UID_SHELL -> R.string.diag_shizuku_uid_adb_hint
            else -> null
        }
        CheckOutcome(CheckStatus.INFO, detail = "uid=$uid", hintRes = hint)
    }

    private suspend fun selinuxContext() = runner.run(DiagnosticIds.SHIZUKU_SELINUX, R.string.diag_shizuku_selinux_title, required = false) {
        if (!safePing()) return@run CheckOutcome(CheckStatus.INFO)
        CheckOutcome(CheckStatus.INFO, detail = Shizuku.getSELinuxContext())
    }

    private fun safePing(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun installedVersionName(packageName: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName ?: ""
    } catch (error: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        private const val TAG = "Shizuku"
        /** Arbitrary but unique within this app: Shizuku echoes it back in the result callback. */
        const val PERMISSION_REQUEST_CODE = 0xDF7B
        private const val UID_ROOT = 0
        private const val UID_SHELL = 2000
    }
}
