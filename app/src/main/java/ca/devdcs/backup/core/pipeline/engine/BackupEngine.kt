package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.ArchiveFactory
import ca.devdcs.backup.core.pipeline.ChecksumEngine
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.core.storage.FreeSpaceVerifier
import ca.devdcs.backup.core.storage.StorageRegistry
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.HashRegistryRepository
import ca.devdcs.backup.domain.repository.HistoryRepository
import ca.devdcs.backup.domain.security.CredentialVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the engine collaborates with, bundled so [BackupJob] (one per run) can be built
 * without a 20-argument constructor and tests can assemble the graph by hand.
 */
internal class EngineDeps(
    val sourceFactory: BackupSourceFactory,
    val archiveFactory: ArchiveFactory,
    val checksumEngine: ChecksumEngine,
    val keyPreparer: JobKeyPreparer,
    val maskerProvider: MaskerProvider,
    val storageRegistry: StorageRegistry,
    val destinationRepository: DestinationRepository,
    val freeSpaceVerifier: FreeSpaceVerifier,
    val checkpointRepository: CheckpointRepository,
    val historyRepository: HistoryRepository,
    val hashRegistryRepository: HashRegistryRepository,
    val credentialVault: CredentialVault,
    val progressBus: JobProgressBus,
    val serviceLauncher: JobServiceLauncher,
    val deviceInfo: DeviceInfoProvider,
    val stagingArea: StagingArea,
    val clock: EngineClock,
    val messages: BackupMessages,
    val io: CoroutineDispatcher,
    val progressIntervalMillis: Long = DEFAULT_PROGRESS_INTERVAL_MILLIS,
) {
    companion object {
        /** ~4 Hz: smooth enough for a progress bar, cheap enough for the notification. */
        const val DEFAULT_PROGRESS_INTERVAL_MILLIS = 250L
    }
}

/**
 * Runs one backup profile end to end: plan → free-space pre-flight → per-artifact stream chain
 * (archive → compress → encrypt → digest → destination) → verification → checksum sidecars →
 * manifest, with checkpoints, history, hash records and live progress along the way.
 *
 * Exists as the single orchestrator so every caller (UI "Back up now", WorkManager schedules,
 * resume) gets identical, crash-safe behaviour. It never throws for ordinary failures:
 * the result is a [BackupOutcome] (or the [BackupEvent.Finished] event) describing what happened.
 * Only coroutine cancellation propagates, after partial files were cleaned up and the job was
 * recorded as CANCELLED. A cancel requested through [JobProgressBus.requestCancel] (the
 * notification button) ends the run normally with a CANCELLED outcome.
 */
@Singleton
class BackupEngine internal constructor(private val deps: EngineDeps) {

    @Inject
    constructor(
        sourceFactory: BackupSourceFactory,
        archiveFactory: ArchiveFactory,
        checksumEngine: ChecksumEngine,
        keyPreparer: JobKeyPreparer,
        maskerProvider: MaskerProvider,
        storageRegistry: StorageRegistry,
        destinationRepository: DestinationRepository,
        freeSpaceVerifier: FreeSpaceVerifier,
        checkpointRepository: CheckpointRepository,
        historyRepository: HistoryRepository,
        hashRegistryRepository: HashRegistryRepository,
        credentialVault: CredentialVault,
        progressBus: JobProgressBus,
        serviceLauncher: JobServiceLauncher,
        deviceInfo: DeviceInfoProvider,
        stagingArea: StagingArea,
        clock: EngineClock,
        messages: BackupMessages,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        EngineDeps(
            sourceFactory = sourceFactory,
            archiveFactory = archiveFactory,
            checksumEngine = checksumEngine,
            keyPreparer = keyPreparer,
            maskerProvider = maskerProvider,
            storageRegistry = storageRegistry,
            destinationRepository = destinationRepository,
            freeSpaceVerifier = freeSpaceVerifier,
            checkpointRepository = checkpointRepository,
            historyRepository = historyRepository,
            hashRegistryRepository = hashRegistryRepository,
            credentialVault = credentialVault,
            progressBus = progressBus,
            serviceLauncher = serviceLauncher,
            deviceInfo = deviceInfo,
            stagingArea = stagingArea,
            clock = clock,
            messages = messages,
            io = io,
        ),
    )

    /**
     * Cold flow that runs the backup when collected and emits its [BackupEvent]s; the last event
     * is always [BackupEvent.Finished]. Cancelling the collector cancels the backup.
     */
    fun run(
        profile: BackupProfile,
        jobId: String = UUID.randomUUID().toString(),
        options: BackupRunOptions = BackupRunOptions.DEFAULT,
    ): Flow<BackupEvent> = channelFlow {
        execute(profile, jobId, options) { event -> send(event) }
    }

    /** Runs the backup and returns its outcome; [onEvent] receives the same events as [run]. */
    suspend fun execute(
        profile: BackupProfile,
        jobId: String = UUID.randomUUID().toString(),
        options: BackupRunOptions = BackupRunOptions.DEFAULT,
        onEvent: suspend (BackupEvent) -> Unit = {},
    ): BackupOutcome = BackupJob(deps, profile, jobId, options, onEvent).execute()
}
