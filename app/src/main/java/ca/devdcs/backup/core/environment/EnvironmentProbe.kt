package ca.devdcs.backup.core.environment

import android.os.SystemClock
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.shell.ShellInitializer
import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for the onboarding "Operating Mode Selection & Diagnostics" step and Settings.
 * Composes the per-tier probes into one [DiagnosticReport] for the requested mode and guarantees
 * it never throws: each check is individually sandboxed by [CheckRunner], and a failure of the
 * composition itself degrades to a single FAIL row. Runs are serialized because the root probe
 * opens and may replace libsu's shared shell.
 */
@Singleton
class EnvironmentProbe @Inject constructor(
    private val standardProbe: StandardModeProbe,
    private val shizukuProbe: ShizukuProbe,
    private val rootProbe: RootProbe,
    private val companionModuleProbe: CompanionModuleProbe,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val mutex = Mutex()

    suspend fun diagnose(mode: OperatingMode): DiagnosticReport = withContext(ioDispatcher) {
        mutex.withLock {
            val start = SystemClock.elapsedRealtime()
            ShellInitializer.ensureInitialized()
            val checks = runCatching { collectChecks(mode) }.getOrElse { error ->
                if (error is CancellationException) throw error
                DftbLog.e(TAG, "Diagnostics for $mode crashed", error)
                listOf(
                    DiagnosticCheck(
                        id = DiagnosticIds.PROBE,
                        titleRes = R.string.diag_probe_title,
                        status = CheckStatus.FAIL,
                        detail = error.message ?: error.javaClass.simpleName,
                        hintRes = R.string.diag_probe_hint,
                        required = true,
                        action = null,
                    ),
                )
            }
            val report = DiagnosticReport(
                mode = mode,
                readiness = Readiness.from(checks),
                checks = checks,
                durationMillis = SystemClock.elapsedRealtime() - start,
            )
            DftbLog.i(
                TAG,
                "Diagnostics for $mode: ${report.readiness} in ${report.durationMillis} ms " +
                    "(${checks.count { it.status == CheckStatus.FAIL }} failed, ${checks.count { it.status == CheckStatus.WARN }} warned)",
            )
            report
        }
    }

    private suspend fun collectChecks(mode: OperatingMode): List<DiagnosticCheck> = buildList {
        addAll(standardProbe.checks(mode))
        when (mode) {
            OperatingMode.STANDARD -> Unit
            OperatingMode.SHIZUKU -> addAll(shizukuProbe.checks())
            OperatingMode.ROOT -> addAll(rootProbe.checks())
            OperatingMode.ROOT_MODULE -> {
                val rootChecks = rootProbe.checks()
                addAll(rootChecks)
                val rootGranted = rootChecks.any { it.id == DiagnosticIds.ROOT_GRANTED && it.status == CheckStatus.PASS }
                addAll(companionModuleProbe.checks(rootGranted))
            }
        }
    }

    private companion object {
        const val TAG = "Diagnostics"
    }
}
