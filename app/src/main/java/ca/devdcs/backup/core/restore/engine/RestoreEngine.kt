package ca.devdcs.backup.core.restore.engine

import ca.devdcs.backup.core.pipeline.engine.DeviceInfoProvider
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.pipeline.engine.JobServiceLauncher
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.core.restore.BackupCatalog
import ca.devdcs.backup.core.restore.apps.AppRestoreEvents
import ca.devdcs.backup.core.restore.files.FileRestorer
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.HistoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** Collaborators of one restore run, bundled so tests assemble the graph by hand. */
internal class RestoreDeps(
    val catalog: BackupCatalog,
    val fileRestorer: FileRestorer,
    val appRestorer: AppRestorer?,
    val partitionRestorer: PartitionRestorer?,
    val destinationRepository: DestinationRepository,
    val checkpointRepository: CheckpointRepository,
    val historyRepository: HistoryRepository,
    val progressBus: JobProgressBus,
    val serviceLauncher: JobServiceLauncher,
    val deviceInfo: DeviceInfoProvider,
    val clock: EngineClock,
    val messages: RestoreMessages,
    val io: CoroutineDispatcher,
    /** Install prompts from the app restorer, forwarded as [RestoreEvent.AppUserAction]; null in tests. */
    val appEvents: AppRestoreEvents? = null,
    val progressIntervalMillis: Long = DEFAULT_PROGRESS_INTERVAL_MILLIS,
) {
    companion object {
        const val DEFAULT_PROGRESS_INTERVAL_MILLIS = 250L
    }
}

/**
 * Restores a backup (all of it or selected units) from a destination: open + merge manifests →
 * optional verification → per-unit preflight (tier, target, free space) → file trees here, apps
 * and partitions through [AppRestorer] / [PartitionRestorer] → History + checkpoint.
 *
 * Exists as the single restore orchestrator so the UI, WorkManager and resume all behave alike.
 * It never throws for ordinary failures: the outcome describes them. A cancel requested through
 * [JobProgressBus.requestCancel] ends the run with CANCELLED; cancelling the collector (process
 * or worker shutdown) records INTERRUPTED and rethrows, like the backup engine.
 *
 * App and partition restorers are optional bindings (`@BindsOptionalOf`): when a build has none,
 * those units are reported as unavailable instead of failing the whole graph.
 */
@Singleton
class RestoreEngine internal constructor(private val deps: RestoreDeps) {

    @Inject
    constructor(
        catalog: BackupCatalog,
        fileRestorer: FileRestorer,
        appRestorer: Optional<AppRestorer>,
        partitionRestorer: Optional<PartitionRestorer>,
        destinationRepository: DestinationRepository,
        checkpointRepository: CheckpointRepository,
        historyRepository: HistoryRepository,
        progressBus: JobProgressBus,
        serviceLauncher: JobServiceLauncher,
        deviceInfo: DeviceInfoProvider,
        clock: EngineClock,
        messages: RestoreMessages,
        appEvents: AppRestoreEvents,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        RestoreDeps(
            catalog = catalog,
            fileRestorer = fileRestorer,
            appRestorer = appRestorer.orElse(null),
            partitionRestorer = partitionRestorer.orElse(null),
            destinationRepository = destinationRepository,
            checkpointRepository = checkpointRepository,
            historyRepository = historyRepository,
            progressBus = progressBus,
            serviceLauncher = serviceLauncher,
            deviceInfo = deviceInfo,
            clock = clock,
            messages = messages,
            io = io,
            appEvents = appEvents,
        ),
    )

    /** Cold flow running the restore when collected; the last event is always [RestoreEvent.Finished]. */
    fun restore(request: RestoreRequest): Flow<RestoreEvent> = channelFlow {
        execute(request) { send(it) }
    }

    /** Runs the restore and returns its outcome; [onEvent] receives the same events as [restore]. */
    suspend fun execute(request: RestoreRequest, onEvent: suspend (RestoreEvent) -> Unit = {}): RestoreOutcome =
        RestoreJob(deps, request, onEvent).execute()
}
