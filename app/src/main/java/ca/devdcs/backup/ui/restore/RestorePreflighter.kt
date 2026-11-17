package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.engine.DeviceInfoProvider
import ca.devdcs.backup.core.pipeline.engine.ManifestDevice
import ca.devdcs.backup.core.restore.OpenedBackup
import ca.devdcs.backup.core.restore.apps.AppRestoreOptions
import ca.devdcs.backup.core.restore.apps.DefaultAppRestorer
import ca.devdcs.backup.core.restore.engine.AppRestorer
import ca.devdcs.backup.core.restore.engine.PartitionRestorer
import ca.devdcs.backup.core.restore.engine.RestoreMessages
import ca.devdcs.backup.core.restore.engine.RestoreOptions
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.core.restore.engine.UnitPreflight
import ca.devdcs.backup.core.restore.files.FileRestoreSpec
import ca.devdcs.backup.core.restore.files.FileRestorer
import ca.devdcs.backup.core.restore.partition.RootPartitionRestorer
import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** Dry-run preflight of the selected units plus the device comparison shown with it. */
data class PreflightReport(
    val checks: List<UnitCheck>,
    /** Backup device vs this device when they differ (model/codename), else null. */
    val crossDevice: Pair<ManifestDevice, ManifestDevice>?,
)

/**
 * Runs the same read-only per-unit preflight the restore engine runs at the start of a restore,
 * before anything is started, so the wizard can show tier, space and refusal reasons per unit and
 * the user can fix options first. Uses the same restorer bindings and option overloads as the
 * engine, so its answer predicts the run; the engine still re-checks everything when it runs.
 */
@Singleton
class RestorePreflighter @Inject constructor(
    private val fileRestorer: FileRestorer,
    private val appRestorer: Optional<AppRestorer>,
    private val partitionRestorer: Optional<PartitionRestorer>,
    private val messages: RestoreMessages,
    private val deviceInfo: DeviceInfoProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun check(
        backup: OpenedBackup,
        units: List<RestoreUnitInfo>,
        options: RestoreOptions,
        fileSpec: FileRestoreSpec,
    ): PreflightReport = withContext(io) {
        val checks = units.map { unit ->
            currentCoroutineContext().ensureActive()
            val probe = unitPreflight(backup, unit, options, fileSpec)
            val preflight = probe.preflight
            val (space, shortfall) = if (preflight.available) PreflightRules.space(preflight.requiredBytes, preflight.freeBytes) else SpaceVerdict.NOT_NEEDED to null
            UnitCheck(unit, preflight, space, shortfall, probe.failure)
        }
        val crossDevice = if (units.any { it.kind != RestoreUnitKind.FILE_TREE }) deviceDifference(backup) else null
        PreflightReport(checks, crossDevice)
    }

    private suspend fun unitPreflight(backup: OpenedBackup, unit: RestoreUnitInfo, options: RestoreOptions, fileSpec: FileRestoreSpec): Probe {
        val artifacts = backup.manifest.artifacts.filter { it.sourceId == unit.id }
        if (artifacts.isEmpty()) return blocked(unit, messages.restorerMissing(unit.kind))
        return try {
            Probe(when (unit.kind) {
                RestoreUnitKind.FILE_TREE -> {
                    val p = fileRestorer.preflight(backup, artifacts.first(), fileSpec)
                    UnitPreflight(
                        unitId = unit.id,
                        available = p.available,
                        requiredTier = p.tier,
                        requiredBytes = p.requiredBytes,
                        freeBytes = p.freeBytes,
                        reason = p.error?.takeIf { !p.available }?.let { messages.fileError(it, p.detail) },
                        warnings = listOfNotNull(p.error?.takeIf { p.available }?.let { messages.fileError(it, p.detail) }),
                    )
                }
                RestoreUnitKind.APP -> {
                    val restorer = appRestorer.orElse(null) ?: return blocked(unit, messages.restorerMissing(unit.kind))
                    if (restorer is DefaultAppRestorer) {
                        restorer.preflightApp(artifacts, options, options.app ?: AppRestoreOptions())
                    } else {
                        restorer.preflight(backup, artifacts, options)
                    }
                }
                RestoreUnitKind.PARTITION -> {
                    val restorer = partitionRestorer.orElse(null) ?: return blocked(unit, messages.restorerMissing(unit.kind))
                    val explicit = options.partition
                    if (explicit != null && restorer is RootPartitionRestorer) {
                        restorer.preflight(backup, artifacts, explicit)
                    } else {
                        restorer.preflight(backup, artifacts, options)
                    }
                }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            DftbLog.w(TAG, "Preflight of ${unit.id} failed", e)
            blocked(unit, e.message ?: e.javaClass.simpleName, BackupUnlocker.failureOf(e))
        }
    }

    private suspend fun deviceDifference(backup: OpenedBackup): Pair<ManifestDevice, ManifestDevice>? {
        val here = try {
            deviceInfo.device()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val from = backup.manifest.device
        return if (from.device != here.device || from.model != here.model) from to here else null
    }

    private fun blocked(unit: RestoreUnitInfo, reason: String, failure: OpenFailure? = null) =
        Probe(UnitPreflight(unit.id, available = false, requiredTier = ElevationTier.NONE, reason = reason), failure)

    private class Probe(val preflight: UnitPreflight, val failure: OpenFailure? = null)

    private companion object {
        const val TAG = "RestorePreflight"
    }
}
