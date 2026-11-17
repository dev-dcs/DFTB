package ca.devdcs.backup.core.environment

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import com.topjohnwu.superuser.Shell
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tier 4 diagnostics for the systemless companion module. Every check needs the root shell, so
 * when [checks] is told root was refused it reports each row as FAIL with a single explanatory
 * hint instead of running shell commands that would only produce permission noise.
 */
@Singleton
class CompanionModuleProbe @Inject constructor(
    private val runner: CheckRunner,
) {

    suspend fun checks(rootGranted: Boolean): List<DiagnosticCheck> {
        if (!rootGranted) return rootUnavailable()
        return listOf(
            moduleInstalled(),
            moduleEnabled(),
            systemTool(DiagnosticIds.MODULE_TOOL_RCLONE, R.string.diag_module_tool_rclone_title, "rclone"),
            systemTool(DiagnosticIds.MODULE_TOOL_BUSYBOX, R.string.diag_module_tool_busybox_title, "busybox"),
            systemTool(DiagnosticIds.MODULE_TOOL_NSENTER, R.string.diag_module_tool_nsenter_title, "nsenter"),
            systemTool(DiagnosticIds.MODULE_TOOL_ZSTD, R.string.diag_module_tool_zstd_title, "zstd"),
            systemTool(DiagnosticIds.MODULE_TOOL_TAR, R.string.diag_module_tool_tar_title, "tar"),
            serviceScript(),
            journalDirectory(),
        )
    }

    private fun rootUnavailable(): List<DiagnosticCheck> {
        fun blocked(id: String, @StringRes titleRes: Int, required: Boolean, action: DiagnosticAction? = null) =
            DiagnosticCheck(
                id = id,
                titleRes = titleRes,
                status = CheckStatus.FAIL,
                detail = null,
                hintRes = R.string.diag_module_requires_root_hint,
                required = required,
                action = action,
            )
        return listOf(
            blocked(DiagnosticIds.MODULE_INSTALLED, R.string.diag_module_installed_title, required = true, action = DiagnosticAction.OPEN_ROOT_MANAGER),
            blocked(DiagnosticIds.MODULE_ENABLED, R.string.diag_module_enabled_title, required = true),
            blocked(DiagnosticIds.MODULE_TOOL_RCLONE, R.string.diag_module_tool_rclone_title, required = false),
            blocked(DiagnosticIds.MODULE_TOOL_BUSYBOX, R.string.diag_module_tool_busybox_title, required = false),
            blocked(DiagnosticIds.MODULE_TOOL_NSENTER, R.string.diag_module_tool_nsenter_title, required = false),
            blocked(DiagnosticIds.MODULE_TOOL_ZSTD, R.string.diag_module_tool_zstd_title, required = false),
            blocked(DiagnosticIds.MODULE_TOOL_TAR, R.string.diag_module_tool_tar_title, required = false),
            blocked(DiagnosticIds.MODULE_SERVICE, R.string.diag_module_service_title, required = false),
            blocked(DiagnosticIds.MODULE_JOURNAL, R.string.diag_module_journal_title, required = false),
        )
    }

    private suspend fun moduleInstalled() = runner.run(
        DiagnosticIds.MODULE_INSTALLED,
        R.string.diag_module_installed_title,
        required = true,
        timeoutMillis = CheckRunner.SHELL_COMMAND_TIMEOUT_MILLIS,
        failureAction = DiagnosticAction.INSTALL_COMPANION_MODULE,
    ) {
        val result = Shell.cmd("cat $MODULE_PROP").exec()
        if (!result.isSuccess) {
            return@run CheckOutcome(
                CheckStatus.FAIL,
                detail = MODULE_PROP,
                hintRes = R.string.diag_module_installed_hint,
                action = DiagnosticAction.INSTALL_COMPANION_MODULE,
            )
        }
        val props = parseModuleProp(result.out)
        val version = props["version"]
        val versionCode = props["versionCode"]
        val detail = buildString {
            append(version ?: props["id"] ?: MODULE_ID)
            if (versionCode != null) append(" (").append(versionCode).append(')')
        }
        CheckOutcome(CheckStatus.PASS, detail = detail)
    }

    private suspend fun moduleEnabled() = runner.run(
        DiagnosticIds.MODULE_ENABLED,
        R.string.diag_module_enabled_title,
        required = true,
        timeoutMillis = CheckRunner.SHELL_COMMAND_TIMEOUT_MILLIS,
    ) {
        val flags = listOf("disable", "remove")
            .map { "$MODULE_DIR/$it" }
            .filter { Shell.cmd("test -e $it").exec().isSuccess }
        if (flags.isEmpty()) {
            CheckOutcome(CheckStatus.PASS)
        } else {
            CheckOutcome(CheckStatus.FAIL, detail = flags.joinToString("\n"), hintRes = R.string.diag_module_enabled_hint)
        }
    }

    private suspend fun systemTool(id: String, @StringRes titleRes: Int, tool: String) = runner.run(
        id,
        titleRes,
        required = false,
        timeoutMillis = CheckRunner.SHELL_COMMAND_TIMEOUT_MILLIS,
    ) {
        val resolved = Shell.cmd("command -v $tool").exec()
        val path = resolved.out.firstOrNull { it.isNotBlank() }?.trim()
        if (resolved.isSuccess && path != null) {
            CheckOutcome(CheckStatus.PASS, detail = path)
        } else {
            CheckOutcome(CheckStatus.WARN, detail = "/system/bin/$tool", hintRes = R.string.diag_module_tool_missing_hint)
        }
    }

    private suspend fun serviceScript() = runner.run(
        DiagnosticIds.MODULE_SERVICE,
        R.string.diag_module_service_title,
        required = false,
        timeoutMillis = CheckRunner.SHELL_COMMAND_TIMEOUT_MILLIS,
    ) {
        val path = "$MODULE_DIR/service.sh"
        val present = Shell.cmd("test -f $path").exec().isSuccess
        CheckOutcome(CheckStatus.INFO, detail = if (present) path else null)
    }

    private suspend fun journalDirectory() = runner.run(
        DiagnosticIds.MODULE_JOURNAL,
        R.string.diag_module_journal_title,
        required = false,
        timeoutMillis = CheckRunner.SHELL_COMMAND_TIMEOUT_MILLIS,
    ) {
        val present = Shell.cmd("test -d $JOURNAL_DIR").exec().isSuccess
        if (present) {
            CheckOutcome(CheckStatus.INFO, detail = JOURNAL_DIR)
        } else {
            CheckOutcome(CheckStatus.INFO, hintRes = R.string.diag_module_journal_hint)
        }
    }

    /** `key=value` lines; comments and malformed lines are skipped. */
    private fun parseModuleProp(lines: List<String>): Map<String, String> = lines
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') && it.contains('=') }
        .associate { line ->
            val index = line.indexOf('=')
            line.substring(0, index).trim() to line.substring(index + 1).trim()
        }

    companion object {
        const val MODULE_ID = "dftb_companion"
        const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
        const val MODULE_PROP = "$MODULE_DIR/module.prop"
        const val JOURNAL_DIR = "/data/adb/dftb/journal"
    }
}
