package ca.devdcs.backup.core.environment

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.shell.ShellInitializer
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tier 3 diagnostics. Opening the root shell is the one step that can block on a superuser
 * prompt, so it runs under the long [CheckRunner.ROOT_SHELL_TIMEOUT_MILLIS]; everything after it
 * reuses libsu's cached main shell through `Shell.cmd(...).exec()`.
 */
@Singleton
class RootProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: CheckRunner,
) {

    suspend fun checks(): List<DiagnosticCheck> {
        ShellInitializer.ensureInitialized()
        return listOf(
            suBinary(),
            rootManager(),
            rootGranted(),
            shellIdentity(),
            selinux(),
            rootImplementation(),
            busybox(),
            nsenter(),
        )
    }

    private suspend fun suBinary() = runner.run(DiagnosticIds.SU_BINARY, R.string.diag_su_binary_title, required = false) {
        val pathDirs = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
        val candidates = pathDirs.map { "$it/su" } + KNOWN_SU_PATHS
        val found = candidates.distinct().filter { File(it).exists() }
        if (found.isEmpty()) {
            CheckOutcome(CheckStatus.WARN, hintRes = R.string.diag_su_binary_hint)
        } else {
            CheckOutcome(CheckStatus.INFO, detail = found.joinToString("\n"))
        }
    }

    private suspend fun rootManager() = runner.run(DiagnosticIds.ROOT_MANAGER, R.string.diag_root_manager_title, required = false) {
        val installed = ROOT_MANAGERS.mapNotNull { (packageName, label) ->
            installedVersionName(packageName)?.let { version -> "$label $version ($packageName)" }
        }
        if (installed.isEmpty()) {
            CheckOutcome(CheckStatus.INFO, hintRes = R.string.diag_root_manager_hint)
        } else {
            CheckOutcome(CheckStatus.INFO, detail = installed.joinToString("\n"))
        }
    }

    private suspend fun rootGranted() = runner.run(
        DiagnosticIds.ROOT_GRANTED,
        R.string.diag_root_granted_title,
        required = true,
        timeoutMillis = CheckRunner.ROOT_SHELL_TIMEOUT_MILLIS,
        failureAction = DiagnosticAction.OPEN_ROOT_MANAGER,
    ) {
        // A cached non-root shell would make every later probe see "sh" forever, even after the
        // user grants access in the manager and re-runs. Drop it so getShell() asks `su` again.
        Shell.getCachedShell()?.let { cached ->
            if (!cached.isRoot) {
                runCatching { cached.close() }.onFailure { DftbLog.w(TAG, "Closing cached non-root shell failed", it) }
            }
        }
        val shell = Shell.getShell()
        val granted = shell.isRoot || Shell.isAppGrantedRoot() == true
        val statusLabel = context.getString(
            when (shell.status) {
                Shell.ROOT_MOUNT_MASTER -> R.string.diag_root_granted_shell_mount_master
                Shell.ROOT_SHELL -> R.string.diag_root_granted_shell_root
                Shell.NON_ROOT_SHELL -> R.string.diag_root_granted_shell_non_root
                else -> R.string.diag_root_granted_shell_unknown
            },
        )
        if (granted) {
            CheckOutcome(CheckStatus.PASS, detail = statusLabel)
        } else {
            CheckOutcome(
                CheckStatus.FAIL,
                detail = statusLabel,
                hintRes = R.string.diag_root_granted_hint,
                action = DiagnosticAction.OPEN_ROOT_MANAGER,
            )
        }
    }

    private suspend fun shellIdentity() = shellInfo(DiagnosticIds.SHELL_IDENTITY, R.string.diag_shell_identity_title, "id")

    private suspend fun selinux() = shellInfo(DiagnosticIds.SELINUX, R.string.diag_selinux_title, "getenforce")

    private suspend fun rootImplementation() = runner.run(
        DiagnosticIds.ROOT_IMPLEMENTATION,
        R.string.diag_root_implementation_title,
        required = false,
        timeoutMillis = CheckRunner.SHELL_COMMAND_TIMEOUT_MILLIS,
    ) {
        val labels = mutableListOf<String>()
        firstLine("magisk -v")?.let { version ->
            val code = firstLine("magisk -V")
            labels += if (code != null) "Magisk $version ($code)" else "Magisk $version"
        }
        if (Shell.cmd("test -d /data/adb/ksu").exec().isSuccess) {
            val version = firstLine("/data/adb/ksud -V") ?: firstLine("ksud -V")
            labels += if (version != null) "KernelSU $version" else "KernelSU"
        }
        if (Shell.cmd("test -d /data/adb/ap").exec().isSuccess) {
            val version = firstLine("/data/adb/apd -V") ?: firstLine("apd -V")
            labels += if (version != null) "APatch $version" else "APatch"
        }
        if (labels.isEmpty()) {
            CheckOutcome(CheckStatus.INFO, hintRes = R.string.diag_root_implementation_hint)
        } else {
            CheckOutcome(CheckStatus.INFO, detail = labels.joinToString("\n"))
        }
    }

    private suspend fun busybox() = runner.run(
        DiagnosticIds.BUSYBOX,
        R.string.diag_busybox_title,
        required = false,
        timeoutMillis = CheckRunner.SHELL_COMMAND_TIMEOUT_MILLIS,
    ) {
        val path = firstLine("command -v busybox")
        if (path != null) {
            CheckOutcome(CheckStatus.PASS, detail = path)
        } else {
            CheckOutcome(CheckStatus.WARN, hintRes = R.string.diag_busybox_hint)
        }
    }

    private suspend fun nsenter() = runner.run(
        DiagnosticIds.NSENTER,
        R.string.diag_nsenter_title,
        required = false,
        timeoutMillis = CheckRunner.SHELL_COMMAND_TIMEOUT_MILLIS,
    ) {
        val path = firstLine("command -v nsenter")
        if (path != null) {
            CheckOutcome(CheckStatus.INFO, detail = path)
        } else {
            CheckOutcome(CheckStatus.INFO, hintRes = R.string.diag_nsenter_hint)
        }
    }

    /** INFO check whose detail is the first output line of [command]; FAIL text if it errors. */
    private suspend fun shellInfo(id: String, titleRes: Int, command: String) = runner.run(
        id,
        titleRes,
        required = false,
        timeoutMillis = CheckRunner.SHELL_COMMAND_TIMEOUT_MILLIS,
    ) {
        val result = Shell.cmd(command).exec()
        val output = result.out.joinToString("\n").trim()
        CheckOutcome(CheckStatus.INFO, detail = if (output.isNotEmpty()) output else "exit ${result.code}")
    }

    /** First non-blank stdout line of a successful command, or null when it fails / prints nothing. */
    private fun firstLine(command: String): String? {
        val result = Shell.cmd(command).exec()
        if (!result.isSuccess) return null
        return result.out.firstOrNull { it.isNotBlank() }?.trim()
    }

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
        private const val TAG = "RootProbe"

        val KNOWN_SU_PATHS: List<String> = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
        )

        /** Manager package -> display label. Also declared under `<queries>` in the manifest. */
        val ROOT_MANAGERS: List<Pair<String, String>> = listOf(
            "com.topjohnwu.magisk" to "Magisk",
            "me.weishu.kernelsu" to "KernelSU",
            "me.bmax.apatch" to "APatch",
        )
    }
}
