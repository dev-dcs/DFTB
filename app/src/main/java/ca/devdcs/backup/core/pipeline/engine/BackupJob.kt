package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.BackupSource
import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.DigestResult
import ca.devdcs.backup.core.pipeline.EncryptionHeader
import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.SourceEstimate
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.core.pipeline.checksum.ChecksumSidecar
import ca.devdcs.backup.core.pipeline.crypto.EncryptingOutputStream
import ca.devdcs.backup.core.progress.JobKind
import ca.devdcs.backup.core.progress.JobPhase
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.storage.FreeSpaceVerifier
import ca.devdcs.backup.core.storage.PreflightResult
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StoragePaths
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CheckpointState
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.HashRecord
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.VaultException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * State machine of ONE backup run. Exists separately from [BackupEngine] (a singleton) so all
 * per-run mutable state — session, counters, pending `.part` files, warnings — lives in one
 * short-lived object and concurrent runs can never share it.
 */
internal class BackupJob(
    private val deps: EngineDeps,
    private val profile: BackupProfile,
    private val jobId: String,
    private val runOptions: BackupRunOptions,
    private val onEvent: suspend (BackupEvent) -> Unit,
) {
    private val options = profile.pipeline
    private val primary: ChecksumAlgorithm = options.checksumAlgorithm
    private val algorithms: Set<ChecksumAlgorithm> = setOf(primary)
    private val policy: RetryPolicy = runOptions.retryPolicy

    private val warnings: MutableList<BackupWarning> = Collections.synchronizedList(ArrayList())
    private var warningsEmitted = 0
    private val completed = ArrayList<CompletedArtifact>()
    private val manifestArtifacts = ArrayList<ManifestArtifact>()
    private var failedArtifacts = 0
    private val pendingParts: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val stagingCounter = AtomicInteger(0)

    private val sourceBytesDone = AtomicLong(0L)
    private val currentBytes = AtomicLong(0L)
    private val itemsDone = AtomicInteger(0)
    @Volatile private var phase: JobPhase = JobPhase.QUEUED
    @Volatile private var currentItem: String? = null
    @Volatile private var message: String? = null
    @Volatile private var totalBytes: Long = 0L
    @Volatile private var totalItems: Int = 0
    private val meter = ThroughputMeter()
    private val publishLock = Any()
    @Volatile private var finished = false
    @Volatile private var finalOutcome: BackupOutcome? = null

    private var startedAt = 0L
    @Volatile private var destination: Destination? = null
    private var plan: BackupPlan? = null
    private var checkpoint: JobCheckpoint? = null
    private var checkpointWarned = false
    private var manifestWritten = false
    private var writtenManifestPath: String? = null
    private var manifestTarget: String? = null
    private val carriedArtifacts = ArrayList<ManifestArtifact>()
    private var keys: KeyProvider? = null
    private var jobMasker: JobMasker? = null
    private val sessions = SessionHolder()
    @Volatile private var workJob: Job? = null
    private val isCancelled: () -> Boolean = { workJob?.isCancelled == true }

    // --- Entry ---------------------------------------------------------------------------------

    suspend fun execute(): BackupOutcome = coroutineScope {
        startedAt = deps.clock.nowMillis()
        deps.serviceLauncher.ensureStarted()
        phase = JobPhase.PREFLIGHT
        message = deps.messages.planning()
        publishProgress()

        val userCancelled = AtomicBoolean(false)
        val work = async { perform() }
        workJob = work
        val watcher = launch {
            deps.progressBus.cancelRequests.first { jobId in it }
            userCancelled.set(true)
            work.cancel(CancellationException("Backup cancelled by the user"))
            // Closing the session unblocks a socket write stuck in a blocking call.
            sessions.reset()
        }
        val ticker = launch {
            while (isActive) {
                delay(deps.progressIntervalMillis)
                publishProgress()
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            watcher.cancel()
            ticker.cancel()
            // Only a user cancel is final; a cancelled caller (process/worker shutdown) leaves it resumable.
            val status = if (userCancelled.get()) JobStatus.CANCELLED else JobStatus.INTERRUPTED
            val outcome = withContext(NonCancellable) { finish(status, null) }
            if (userCancelled.get() && currentCoroutineContext().isActive) outcome else throw e
        } finally {
            watcher.cancel()
            ticker.cancel()
            releaseResources()
        }
    }

    private suspend fun perform(): BackupOutcome {
        try {
            val dest = resolveDestination()
            destination = dest
            recordStart()
            val resolved = resolveSources()
            checkVerbatimSources(resolved.map { it.second }, dest)

            val transfer = TransferPlanner.resolve(options.transferMode, dest.type)
            if (transfer is TransferResolution.Unsupported) throw step(BackupErrorCode.TRANSFER_MODE_UNSUPPORTED, transfer.detail)
            if (options.isEncrypted && options.encryptionMode != EncryptionMode.AES_256_GCM) {
                throw step(
                    BackupErrorCode.ENCRYPTION_UNSUPPORTED,
                    "Encryption mode ${options.encryptionMode.storageKey} is not implemented; use ${EncryptionMode.AES_256_GCM.storageKey}",
                )
            }
            if (options.maskFilenames && !options.isEncrypted) {
                // Item paths, link targets and the profile in a plaintext manifest would undo masking.
                throw step(BackupErrorCode.MASKING_REQUIRES_ENCRYPTION, "maskFilenames needs encryptionMode ${EncryptionMode.AES_256_GCM.storageKey}")
            }
            if (options.isEncrypted) keys = resolveKeys()

            val jobDirName = resolveJobDirName()
            val priors = loadPriorManifests(jobDirName)
            if (options.maskFilenames) openMasker(priors)
            val masker = jobMasker?.masker
            val p = BackupPlanner.build(
                options = options,
                jobDirName = jobDirName,
                sources = resolved.map { it.second },
                transfer = transfer,
                mask = masker?.let { m -> { name: String -> m.mask(name) } },
            )
            plan = p
            val active = p.artifacts.filter { it.sourceId !in runOptions.skipSourceIds }
            totalBytes = BackupPlanner.saturatingSum(active.map { it.estimate.totalBytes })
            totalItems = BackupPlanner.saturatingSum(active.map { it.estimate.itemCount }).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            p.artifacts.filter { it.spec.substituted }.forEach {
                warn(BackupWarningCode.FORMAT_SUBSTITUTED, it.logicalName, "${options.archiveFormat.storageKey} -> ${it.spec.format.storageKey}")
            }
            emit(BackupEvent.Planned(jobId, p.jobDir, p.artifacts.size, totalBytes, totalItems.toLong(), p.estimatesExact))
            updateCheckpoint { state ->
                state.copy(extras = state.extras + mapOf(EXTRA_JOB_DIR to p.jobDir, EXTRA_DESTINATION_ID to dest.id))
            }

            carryForwardSkipped(p, priors)
            manifestTarget = chooseManifestTarget(p)
            preflight(dest, p, active)
            flushWarnings()

            phase = JobPhase.RUNNING
            message = null
            retrying(p.jobDir) { sessions.get().mkdirs(p.jobDir) }

            p.artifacts.forEachIndexed { index, artifact ->
                if (artifact.sourceId in runOptions.skipSourceIds) {
                    warn(BackupWarningCode.RESUME_SKIPPED, artifact.logicalName, null)
                } else {
                    processArtifact(p, artifact, resolved[index].first)
                }
            }
            if (completed.isEmpty() && failedArtifacts > 0) throw step(BackupErrorCode.ALL_ARTIFACTS_FAILED, null)

            phase = JobPhase.FINALIZING
            currentItem = null
            message = deps.messages.writingManifest()
            publishProgress()
            writeManifest(p, partial = false)

            val degraded = failedArtifacts > 0 || synchronized(warnings) { warnings.any { it.code.degradesStatus } }
            return finish(if (degraded) JobStatus.COMPLETED_WITH_WARNINGS else JobStatus.COMPLETED, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupStepException) {
            return finish(JobStatus.FAILED, e.failure)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            DftbLog.e(TAG, "Backup $jobId failed", e)
            return finish(JobStatus.FAILED, failureOf(e, BackupErrorCode.INTERNAL))
        }
    }

    // --- Planning ------------------------------------------------------------------------------

    private suspend fun resolveDestination(): Destination {
        val id = profile.destinationId ?: throw step(BackupErrorCode.NO_DESTINATION, null)
        return deps.destinationRepository.get(id) ?: throw step(BackupErrorCode.DESTINATION_NOT_FOUND, "Destination $id")
    }

    /**
     * Each source with its descriptor. Unavailable sources and estimate failures become warnings;
     * only "nothing at all can run" fails the job.
     */
    /** Post-run issue readers, by artifact index (same order as the plan). */
    private var runtimeIssueReaders: List<() -> List<SourceIssue>> = emptyList()

    private suspend fun resolveSources(): List<Pair<BackupSource, SourceDescriptor>> {
        val resolution = try {
            deps.sourceFactory.create(profile, profile.targets)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw step(BackupErrorCode.SOURCE_FAILED, e.message ?: e.javaClass.simpleName, e)
        }
        for (issue in resolution.issues) {
            val detail = listOfNotNull(issue.message, issue.detail).joinToString(" - ")
            warn(if (issue.blocking) BackupWarningCode.SOURCE_UNAVAILABLE else BackupWarningCode.SOURCE_PARTIAL, issue.subject, detail)
        }
        if (resolution.sources.isEmpty()) {
            val blocking = resolution.issues.filter { it.blocking }
            if (blocking.isNotEmpty()) {
                throw step(BackupErrorCode.SOURCES_UNAVAILABLE, blocking.joinToString("; ") { "${it.subject}: ${it.message}" })
            }
            throw step(BackupErrorCode.NOTHING_TO_BACK_UP, null)
        }
        runtimeIssueReaders = resolution.sources.map { it.runtimeIssues }
        return resolution.sources.map { resolved ->
            val source = resolved.source
            var failed = false
            val estimate = try {
                withContext(deps.io) { source.estimate() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                warn(BackupWarningCode.ESTIMATE_FAILED, resolved.displayName, e.message)
                SourceEstimate(itemCount = 0L, totalBytes = 0L, exact = false)
            }
            source to SourceDescriptor(
                sourceId = source.sourceId,
                kind = resolved.kind,
                displayName = resolved.displayName,
                estimate = estimate,
                estimateFailed = failed,
                verbatim = resolved.storeVerbatim,
            )
        }
    }

    /** TWRP folders are only restorable verbatim; refuse what would silently weaken the request. */
    private fun checkVerbatimSources(descriptors: List<SourceDescriptor>, dest: Destination) {
        val verbatim = descriptors.filter { it.verbatim }
        if (verbatim.isEmpty()) return
        if (!TransferPlanner.isLocal(dest.type)) {
            // TWRP only sees device storage / USB; a network copy must be moved there to restore.
            verbatim.forEach { warn(BackupWarningCode.TWRP_NOT_ON_DEVICE_STORAGE, it.displayName, dest.type.storageKey) }
        }
        if (options.isEncrypted) {
            throw step(BackupErrorCode.TWRP_ENCRYPTION_CONFLICT, "twrpCompatible output cannot be encrypted (${options.encryptionMode.storageKey})")
        }
        val compressionAsked = options.compressionCodec != CompressionCodec.NONE || ArtifactSpecResolver.impliedCodec(options.archiveFormat) != CompressionCodec.NONE
        if (compressionAsked || options.maskFilenames) {
            verbatim.forEach { warn(BackupWarningCode.TWRP_STORED_VERBATIM, it.displayName, null) }
        }
    }

    private suspend fun resolveKeys(): KeyProvider {
        runOptions.passphrase?.let { typed ->
            if (typed.isEmpty()) throw step(BackupErrorCode.ENCRYPTION_KEY_MISSING, "Empty passphrase")
            return KeyProvider(null, typed.copyOf())
        }
        val ref = options.encryptionKeyRef ?: throw step(BackupErrorCode.ENCRYPTION_KEY_MISSING, "The profile has no key reference")
        val secret = try {
            deps.credentialVault.load(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultException) {
            throw step(BackupErrorCode.ENCRYPTION_KEY_MISSING, "Vault: ${e.reason}", e)
        }
        return when (secret) {
            is Secret.EncryptionKey -> KeyProvider(ref, null)
            is Secret.Password -> KeyProvider(null, secret.password.toCharArray())
            null -> throw step(BackupErrorCode.ENCRYPTION_KEY_MISSING, "No vault entry for the profile key")
            else -> throw step(BackupErrorCode.ENCRYPTION_KEY_MISSING, "Vault entry is a ${secret.kind}, not a key or passphrase")
        }
    }

    private fun requireKeys(): KeyProvider = keys ?: throw step(BackupErrorCode.ENCRYPTION_KEY_MISSING, null)

    private suspend fun preflight(dest: Destination, p: BackupPlan, active: List<PlannedArtifact>) {
        phase = JobPhase.PREFLIGHT
        message = deps.messages.preflight()
        publishProgress()
        val destRequired = BackupPlanner.saturatingSum(active.map { it.estimate.totalBytes })
        val destResult = deps.freeSpaceVerifier.verify(dest, destRequired)
        handlePreflight(PreflightTarget.DESTINATION, destRequired, destResult, BackupErrorCode.INSUFFICIENT_DESTINATION_SPACE)

        val stagingRequired = active.maxOfOrNull { BackupPlanner.stagingBytesFor(it, p.transfer) } ?: 0L
        if (stagingRequired > 0L) {
            val free = deps.stagingArea.freeBytes()
            val result = FreeSpaceVerifier.evaluate(free?.let { SpaceInfo(freeBytes = it, totalBytes = null) }, stagingRequired)
            handlePreflight(PreflightTarget.LOCAL_STAGING, stagingRequired, result, BackupErrorCode.INSUFFICIENT_STAGING_SPACE)
        }
    }

    private suspend fun handlePreflight(target: PreflightTarget, required: Long, result: PreflightResult, code: BackupErrorCode) {
        val overridden = result is PreflightResult.Insufficient && runOptions.ignoreInsufficientSpace
        emit(BackupEvent.PreflightChecked(jobId, target, required, result, overridden))
        when (result) {
            is PreflightResult.Insufficient -> {
                val detail = "${target.name}: short by ${result.shortfallBytes} bytes for $required bytes plus margin"
                if (overridden) warn(BackupWarningCode.FREE_SPACE_OVERRIDDEN, target.name, detail) else throw step(code, detail)
            }
            is PreflightResult.Unknown -> warn(BackupWarningCode.FREE_SPACE_UNKNOWN, target.name, result.reason)
            is PreflightResult.Sufficient -> Unit
        }
    }

    // --- Artifacts -----------------------------------------------------------------------------

    private suspend fun processArtifact(p: BackupPlan, artifact: PlannedArtifact, source: BackupSource) {
        currentItem = artifact.logicalName
        message = null
        currentBytes.set(0L)
        updateCheckpoint { it.copy(currentItem = artifact.sourceId, currentOffsetBytes = 0L) }
        emit(BackupEvent.ArtifactStarted(jobId, artifact.index, artifact.logicalName, 1))
        try {
            val built = if (artifact.spec.layout == ArtifactLayout.FILE_TREE) {
                produceTree(p, artifact, source)
            } else {
                produceSingle(p, artifact, source)
            }
            val verified: Boolean? = if (options.verifyAfterBackup) {
                built.storedFiles.all { verifyStored(it, artifact.logicalName) }
            } else {
                null
            }
            val sidecarName = ArtifactNaming.checksumSidecarName(artifact.storedName, primary, options.twrpCompatible)
            writeSmallFile(
                ArtifactNaming.join(p.jobDir, sidecarName),
                ChecksumSidecar.format(built.sidecarEntries).toByteArray(Charsets.UTF_8),
                artifact.logicalName,
            )
            manifestArtifacts += ManifestArtifact(
                sourceId = artifact.sourceId,
                kind = artifact.kind.name.lowercase(Locale.ROOT),
                name = if (artifact.masked && !p.encryptedManifest) null else artifact.logicalName,
                storedName = artifact.storedName,
                masked = artifact.masked,
                layout = artifact.spec.layout.name.lowercase(Locale.ROOT),
                format = artifact.spec.format,
                codec = artifact.spec.codec,
                compressionLevel = artifact.spec.compressionLevel,
                sizeBytes = built.storedBytes,
                sourceBytes = built.sourceBytes,
                digests = ManifestText.digestMap(built.digests),
                encryption = built.encryption?.let(ManifestEncryption::of),
                checksumFile = sidecarName,
                verified = verified,
                treeRoot = if (artifact.spec.layout == ArtifactLayout.FILE_TREE) artifact.treeRoot else null,
                items = built.items,
            )
            sourceBytesDone.addAndGet(built.sourceBytes)
            currentBytes.set(0L)
            recordHashes(built.hashRecords)
            if (verified == false) {
                failArtifact(
                    artifact,
                    BackupFailure(BackupErrorCode.VERIFY_MISMATCH, "Stored bytes of ${artifact.storedName} differ from what was written"),
                )
                return
            }
            val done = CompletedArtifact(
                sourceId = artifact.sourceId,
                logicalName = artifact.logicalName,
                path = when {
                    artifact.verbatim -> built.storedFiles.firstOrNull()?.path?.let(StoragePaths::parent).orEmpty()
                    artifact.spec.layout == ArtifactLayout.FILE_TREE -> artifact.treeRoot
                    else -> artifact.path
                },
                format = artifact.spec.format,
                codec = artifact.spec.codec,
                storedBytes = built.storedBytes,
                sourceBytes = built.sourceBytes,
                itemCount = built.items.size,
                digests = built.digests,
                verified = verified,
            )
            completed += done
            updateCheckpoint { it.copy(completedItems = it.completedItems + artifact.sourceId, currentItem = null, currentOffsetBytes = 0L) }
            writePartialManifest()
            emit(BackupEvent.ArtifactCompleted(jobId, done))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            // A destination that keeps failing would fail every remaining artifact too: abort.
            if (RetryPolicy.destinationFailure(e) != null) throw e
            DftbLog.w(TAG, "Artifact ${artifact.logicalName} failed", e)
            currentBytes.set(0L)
            failArtifact(artifact, (e as? BackupStepException)?.failure ?: failureOf(e, BackupErrorCode.SOURCE_FAILED))
        } finally {
            reportRuntimeIssues(artifact)
            flushWarnings()
        }
    }

    /** Issues the source only learned while its items were read; they degrade the job status. */
    private fun reportRuntimeIssues(artifact: PlannedArtifact) {
        val issues = try {
            runtimeIssueReaders.getOrNull(artifact.index)?.invoke().orEmpty()
        } catch (e: Exception) {
            DftbLog.w(TAG, "Runtime issues of ${artifact.logicalName} unreadable", e)
            emptyList()
        }
        issues.forEach { issue ->
            warn(BackupWarningCode.SOURCE_PARTIAL, issue.subject, listOfNotNull(issue.message, issue.detail).joinToString(" - "))
        }
    }

    private suspend fun failArtifact(artifact: PlannedArtifact, failure: BackupFailure) {
        failedArtifacts++
        warn(BackupWarningCode.ARTIFACT_FAILED, artifact.logicalName, failure.detail ?: failure.code.name)
        emit(BackupEvent.ArtifactFailed(jobId, artifact.logicalName, failure))
    }

    private suspend fun produceSingle(p: BackupPlan, artifact: PlannedArtifact, source: BackupSource): BuiltArtifact {
        val itemsBase = itemsDone.get()
        var recorder = ItemRecorder(artifact.logicalName)
        val stored = retrying(artifact.logicalName, policyFor(p.transfer)) { attempt ->
            if (attempt > 1) emit(BackupEvent.ArtifactStarted(jobId, artifact.index, artifact.logicalName, attempt))
            currentBytes.set(0L)
            itemsDone.set(itemsBase)
            val attemptRecorder = ItemRecorder(artifact.logicalName)
            recorder = attemptRecorder
            writeOutput(artifact.path, artifact.spec.codec, artifact.spec.compressionLevel, artifact.encrypted, artifact.logicalName) { out ->
                if (artifact.spec.layout == ArtifactLayout.ARCHIVE) {
                    writeArchive(artifact, source, attemptRecorder, out)
                } else {
                    writeRawStream(artifact, source, attemptRecorder, out)
                }
            }
        }
        val hex = stored.digests[primary] ?: throw IllegalStateException("Checksum engine returned no ${primary.storageKey} digest")
        return BuiltArtifact(
            storedFiles = listOf(stored),
            storedBytes = stored.bytes,
            digests = stored.digests,
            encryption = stored.encryption,
            sourceBytes = recorder.sourceBytes(),
            items = recorder.items(),
            hashRecords = recorder.hashRecords(),
            sidecarEntries = listOf(ChecksumSidecar.Entry(hex, artifact.storedName)),
        )
    }

    private suspend fun writeArchive(artifact: PlannedArtifact, source: BackupSource, recorder: ItemRecorder, out: OutputStream) {
        val format = artifact.spec.writerFormat ?: throw IllegalStateException("Archive layout without writer format")
        val writer = deps.archiveFactory.writer(format, CloseShieldOutputStream(out), artifact.spec.compressionLevel)
        try {
            source.items().collect { item -> writer.write(recorder.wrap(filterMetadata(item))) }
            writer.finish()
        } finally {
            writer.close()
        }
    }

    private suspend fun writeRawStream(artifact: PlannedArtifact, source: BackupSource, recorder: ItemRecorder, out: OutputStream) {
        var dataSeen = false
        source.items().collect { raw ->
            val item = filterMetadata(raw)
            when (item) {
                is SourceItem.Directory, is SourceItem.Symlink -> recorder.record(item)
                is SourceItem.File, is SourceItem.Stream -> {
                    if (dataSeen) {
                        throw step(BackupErrorCode.SOURCE_SHAPE, "Source ${artifact.sourceId} emitted more than one image")
                    }
                    dataSeen = true
                    val index = recorder.reserve(item)
                    recorder.openTracked(index, item).use { input -> copyStream(input, out) }
                }
            }
        }
        if (!dataSeen) throw step(BackupErrorCode.SOURCE_FAILED, "Source ${artifact.sourceId} produced no image data")
    }

    private suspend fun produceTree(p: BackupPlan, artifact: PlannedArtifact, source: BackupSource): BuiltArtifact {
        val recorder = ItemRecorder(artifact.logicalName)
        val masker = jobMasker?.masker
        val mask = masker?.let { m -> { segment: String -> m.mask(segment) } }
        val storedFiles = ArrayList<StoredFile>()
        val entries = ArrayList<ChecksumSidecar.Entry>()
        var header: EncryptionHeader? = null
        source.items().collect { raw ->
            val item = filterMetadata(raw)
            when (item) {
                is SourceItem.Directory, is SourceItem.Symlink -> recorder.record(item)
                is SourceItem.File, is SourceItem.Stream -> {
                    val relative = ArtifactNaming.treeFilePath(item.archivePath, artifact.spec, artifact.encrypted, if (artifact.masked) mask else null)
                    val finalPath = ArtifactNaming.join(artifact.treeRoot, relative)
                    val index = recorder.reserve(item)
                    currentItem = artifact.logicalName + "/" + item.archivePath
                    val bytesBase = currentBytes.get()
                    val itemsBase = itemsDone.get()
                    val stored = retrying(artifact.logicalName, policyFor(p.transfer)) {
                        // A retry re-reads the item: rewind the counters so it is counted once.
                        currentBytes.set(bytesBase)
                        itemsDone.set(itemsBase)
                        writeOutput(finalPath, artifact.spec.codec, artifact.spec.compressionLevel, artifact.encrypted, artifact.logicalName) { out ->
                            recorder.openTracked(index, item).use { input -> copyStream(input, out) }
                        }
                    }
                    recorder.attachStored(index, relative, stored)
                    storedFiles += stored
                    if (header == null) header = stored.encryption
                    val hex = stored.digests[primary] ?: throw IllegalStateException("No ${primary.storageKey} digest")
                    // Sidecar lives in the job folder; names are relative to it (TWRP trees sit beside it).
                    val sidecarName = if (artifact.verbatim) "../$relative" else ArtifactNaming.join(artifact.storedName, relative)
                    entries += ChecksumSidecar.Entry(hex, sidecarName)
                }
            }
        }
        currentItem = artifact.logicalName
        return BuiltArtifact(
            storedFiles = storedFiles,
            storedBytes = BackupPlanner.saturatingSum(storedFiles.map { it.bytes }),
            digests = emptyMap(),
            encryption = header,
            sourceBytes = recorder.sourceBytes(),
            items = recorder.items(),
            hashRecords = recorder.hashRecords(),
            sidecarEntries = entries,
        )
    }

    /** Drops metadata the profile asked not to preserve, before any writer sees it. */
    private fun filterMetadata(item: SourceItem): SourceItem {
        if (options.preservePosixMetadata && options.preserveSelinuxContext) return item
        val m = item.metadata
        val filtered = EntryMetadata(
            modifiedAtMillis = m.modifiedAtMillis,
            mode = if (options.preservePosixMetadata) m.mode else null,
            uid = if (options.preservePosixMetadata) m.uid else null,
            gid = if (options.preservePosixMetadata) m.gid else null,
            seLinuxContext = if (options.preserveSelinuxContext) m.seLinuxContext else null,
        )
        return when (item) {
            is SourceItem.Directory -> item.copy(metadata = filtered)
            is SourceItem.File -> item.copy(metadata = filtered)
            is SourceItem.Symlink -> item.copy(metadata = filtered)
            is SourceItem.Stream -> item.copy(metadata = filtered)
        }
    }

    // --- Output chain --------------------------------------------------------------------------

    /**
     * Writes one destination file: producer → compress(codec) → encrypt → digest tee → counter →
     * `<path>.part` (or a staging file uploaded with resume), then renames to [finalPath].
     * On any failure the encrypted stream is aborted (never finalized) and the partial removed.
     */
    private suspend fun writeOutput(
        finalPath: String,
        codec: CompressionCodec,
        level: Int,
        encrypted: Boolean,
        label: String,
        producer: suspend (OutputStream) -> Unit,
    ): StoredFile = withContext(deps.io) {
        val part = ArtifactNaming.partPath(finalPath)
        val staged = currentPlan().transfer == TransferResolution.Staged
        val stagingFile = if (staged) File(deps.stagingArea.dirFor(jobId), "artifact-${stagingCounter.incrementAndGet()}.bin") else null
        var encrypting: OutputStream? = null
        var outermost: OutputStream? = null
        var chainClosed = false
        try {
            val sink: OutputStream = if (stagingFile != null) {
                FileOutputStream(stagingFile)
            } else {
                pendingParts += part
                DestinationGuardOutputStream(sessions.get().openWrite(part, append = false), destinationRetryable())
            }
            outermost = sink
            val counting = CountingOutputStream(sink, onBytes = {}, isCancelled = isCancelled)
            outermost = counting
            val tee = deps.checksumEngine.tee(counting, algorithms)
            outermost = tee
            var header: EncryptionHeader? = null
            val plainTarget: OutputStream = if (encrypted) {
                val key = requireKeys().streamKey()
                header = key.describe()
                key.encrypt(tee).also {
                    encrypting = it
                    outermost = it
                }
            } else {
                tee
            }
            val compressed = deps.archiveFactory.compress(codec, level, plainTarget)
            outermost = compressed
            producer(compressed)
            compressed.close()
            chainClosed = true
            val digest: DigestResult = tee.result()
            if (stagingFile != null) uploadStaged(stagingFile, part, label)
            commit(part, finalPath)
            if (stagingFile != null && !stagingFile.delete()) DftbLog.w(TAG, "Could not delete staging file ${stagingFile.name}")
            StoredFile(finalPath, digest.bytes, digest.hex, header)
        } catch (e: Throwable) {
            if (!chainClosed) abortChain(encrypting, outermost)
            withContext(NonCancellable) { discardPartial(part, stagingFile) }
            throw e
        }
    }

    /** Abort the cipher first so closing the compressor cannot finalize a truncated stream. */
    private fun abortChain(encrypting: OutputStream?, outermost: OutputStream?) {
        (encrypting as? EncryptingOutputStream)?.abort()
        try {
            outermost?.close()
        } catch (e: Exception) {
            DftbLog.d(TAG, "Ignoring close failure while aborting: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun discardPartial(part: String, stagingFile: File?) {
        stagingFile?.delete()
        if (part !in pendingParts) return
        try {
            withTimeout(CLEANUP_TIMEOUT_MILLIS) { sessions.get().delete(part) }
            pendingParts -= part
        } catch (e: Exception) {
            // Kept in pendingParts: the final cleanup retries with a fresh session.
            DftbLog.w(TAG, "Could not remove partial $part: ${e.message}")
        }
    }

    /** Upload a staged file to `.part`, resuming (append) after a transient failure when possible. */
    private suspend fun uploadStaged(file: File, part: String, label: String) {
        message = deps.messages.uploading(label)
        pendingParts += part
        val length = file.length()
        var attempt = 1
        while (true) {
            try {
                uploadOnce(file, length, part, resume = attempt > 1)
                message = null
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!policy.shouldRetry(attempt, e)) throw e
                backoff(label, attempt, e)
                attempt++
            }
        }
    }

    private suspend fun uploadOnce(file: File, length: Long, part: String, resume: Boolean) {
        val session = sessions.get()
        var offset = if (resume) {
            session.stat(part)?.takeIf { !it.isDirectory }?.sizeBytes?.takeIf { it in 1L..length } ?: 0L
        } else {
            0L
        }
        val raw: OutputStream = if (offset > 0L) {
            try {
                session.openWrite(part, append = true)
            } catch (e: StorageException) {
                if (e.reason != StorageException.Reason.UNSUPPORTED) throw e
                offset = 0L
                session.openWrite(part, append = false)
            }
        } else {
            session.openWrite(part, append = false)
        }
        withContext(deps.io) {
            CountingOutputStream(DestinationGuardOutputStream(raw, destinationRetryable()), onBytes = {}, isCancelled = isCancelled).use { out ->
                FileInputStream(file).use { input ->
                    skipFully(input, offset)
                    copyStream(input, out)
                }
            }
        }
        val uploaded = session.stat(part)?.sizeBytes
        if (uploaded != null && uploaded != length) {
            throw StorageException(StorageException.Reason.PROTOCOL, "Uploaded $uploaded of $length bytes to $part")
        }
    }

    /** `.part` → final, tolerating a rename that succeeded although its reply was lost. */
    private suspend fun commit(part: String, finalPath: String) {
        retrying(finalPath) { attempt ->
            val session = sessions.get()
            val alreadyDone = attempt > 1 && session.stat(part) == null && session.stat(finalPath) != null
            if (!alreadyDone) session.rename(part, finalPath)
        }
        pendingParts -= part
    }

    private suspend fun writeSmallFile(path: String, bytes: ByteArray, label: String) {
        val part = ArtifactNaming.partPath(path)
        retrying(label) {
            pendingParts += part
            val raw = sessions.get().openWrite(part, append = false)
            withContext(deps.io) {
                DestinationGuardOutputStream(raw, destinationRetryable()).use { it.write(bytes) }
            }
        }
        commit(part, path)
    }

    private suspend fun verifyStored(stored: StoredFile, label: String): Boolean {
        message = deps.messages.verifying(label)
        val expected = stored.digests[primary] ?: return false
        val actual = retrying(label) {
            val raw = sessions.get().openRead(stored.path, 0L)
            withContext(deps.io) {
                ObservedInputStream(
                    DestinationGuardInputStream(raw, destinationRetryable()),
                    digester = null,
                    onBytes = {},
                    isCancelled = isCancelled,
                    onClosed = { _, _, _ -> },
                ).use { input -> deps.checksumEngine.digest(input, algorithms) }
            }
        }
        message = null
        val actualHex = actual.hex[primary] ?: return false
        return actual.bytes == stored.bytes && ChecksumSidecar.matches(expected, actualHex)
    }

    /**
     * (Re)writes this run's manifest: artifacts carried from earlier runs plus everything this run
     * finished so far. Atomic (`.part` + rename). [partial] marks an in-progress/interrupted index.
     */
    private suspend fun writeManifest(p: BackupPlan, partial: Boolean) {
        val target = manifestTarget ?: p.manifestPath
        val manifest = BackupManifest(
            jobId = jobId,
            createdAtMillis = startedAt,
            finishedAtMillis = deps.clock.nowMillis(),
            app = deps.deviceInfo.app(),
            device = deps.deviceInfo.device(),
            profile = ManifestProfile.of(profile, destination?.type),
            checksumAlgorithm = primary,
            artifacts = carriedArtifacts + manifestArtifacts,
            masking = jobMasker?.params,
            warnings = synchronized(warnings) { warnings.map(::warningText) },
            partial = partial,
        )
        val bytes = BackupManifest.encode(manifest).toByteArray(Charsets.UTF_8)
        try {
            if (p.encryptedManifest) {
                retrying(target, policyFor(p.transfer)) {
                    writeOutput(target, CompressionCodec.NONE, 0, encrypted = true, label = ArtifactNaming.ENCRYPTED_MANIFEST_NAME) { out ->
                        out.write(bytes)
                    }
                }
            } else {
                writeSmallFile(target, bytes, ArtifactNaming.MANIFEST_NAME)
            }
            writtenManifestPath = target
            manifestWritten = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw BackupStepException(failureOf(e, BackupErrorCode.MANIFEST_FAILED).copy(code = BackupErrorCode.MANIFEST_FAILED), e)
        }
    }

    /** Best effort after each artifact: an interrupted job stays indexed up to its last artifact. */
    private suspend fun writePartialManifest() {
        val p = plan ?: return
        try {
            writeManifest(p, partial = true)
        } catch (e: BackupStepException) {
            DftbLog.w(TAG, "Partial manifest not written: ${e.message}")
        }
    }

    /** On a non-successful end: index what did complete (bounded, never throws). */
    private suspend fun writeManifestOnInterruption() {
        if (manifestArtifacts.isEmpty() || plan == null) return
        try {
            withTimeout(INTERRUPT_MANIFEST_TIMEOUT_MILLIS) { writePartialManifest() }
        } catch (e: Exception) {
            DftbLog.w(TAG, "Manifest not written after interruption: ${e.message}")
        }
    }

    // --- Resume --------------------------------------------------------------------------------

    private val resuming: Boolean get() = runOptions.jobDirName != null || runOptions.skipSourceIds.isNotEmpty()

    /** A new capped name, or the earlier run's folder verbatim (never re-sanitized/truncated). */
    private fun resolveJobDirName(): String {
        val earlier = runOptions.jobDirName ?: checkpoint?.state?.extras?.get(EXTRA_JOB_DIR)?.takeIf { resuming }
        if (earlier != null) {
            if (!ArtifactNaming.isSafeSegment(earlier)) throw step(BackupErrorCode.RESUME_FOLDER_INVALID, "Not a safe folder name: $earlier")
            return earlier
        }
        return ArtifactNaming.jobDirName(profile.name, startedAt, deps.clock.zone())
    }

    /** Every manifest an earlier run left in the folder, oldest first (decrypted when needed). */
    private suspend fun loadPriorManifests(jobDirName: String): List<BackupManifest> {
        if (!resuming) return emptyList()
        val names = try {
            retrying(jobDirName) { sessions.get().list(jobDirName) }
                .filter { !it.isDirectory && ArtifactNaming.MANIFEST_FILE.matches(it.name) }
                .map { it.name }
        } catch (e: StorageException) {
            if (e.reason == StorageException.Reason.NOT_FOUND) return emptyList()
            throw e
        }
        val ordered = names.sortedWith(compareBy<String>({ !it.startsWith(ArtifactNaming.MANIFEST_NAME) }, { it }))
        return ordered.mapNotNull { name -> readPriorManifest(ArtifactNaming.join(jobDirName, name)) }
    }

    private suspend fun readPriorManifest(path: String): BackupManifest? = try {
        val raw = retrying(path) { sessions.get().openRead(path, 0L) }
        val text = withContext(deps.io) {
            val input = if (path.endsWith(ArtifactNaming.ENCRYPTED_SUFFIX)) deps.keyPreparer.decrypt(raw, requireKeys().newSpec()) else raw
            input.use { it.readBytes() }.toString(Charsets.UTF_8)
        }
        BackupManifest.decode(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupStepException) {
        warn(BackupWarningCode.PRIOR_MANIFEST_UNREADABLE, path, e.failure.detail)
        null
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        DftbLog.w(TAG, "Earlier manifest $path unreadable", e)
        warn(BackupWarningCode.PRIOR_MANIFEST_UNREADABLE, path, e.message)
        null
    }

    /**
     * Same masking key as the earlier runs (from the checkpoint, else an earlier manifest), so a
     * resumed job's names and params stay consistent; persisted (no key) for later resumes.
     */
    private suspend fun openMasker(priors: List<BackupManifest>) {
        val fromCheckpoint = checkpoint?.state?.extras.orEmpty()
            .filterKeys { it.startsWith(EXTRA_MASK_PREFIX) }
            .mapKeys { it.key.removePrefix(EXTRA_MASK_PREFIX) }
            .takeIf { it.isNotEmpty() }
        val earlier = fromCheckpoint ?: priors.firstNotNullOfOrNull { it.masking }
        val opened = deps.maskerProvider.open(requireKeys().newSpec(), earlier)
        jobMasker = opened
        updateCheckpoint { state ->
            state.copy(extras = state.extras.filterKeys { !it.startsWith(EXTRA_MASK_PREFIX) } + opened.params.mapKeys { EXTRA_MASK_PREFIX + it.key })
        }
    }

    /** Re-index skipped (already completed) sources from the newest earlier manifest that has them. */
    private fun carryForwardSkipped(p: BackupPlan, priors: List<BackupManifest>) {
        for (artifact in p.artifacts) {
            if (artifact.sourceId !in runOptions.skipSourceIds) continue
            val earlier = priors.asReversed().firstNotNullOfOrNull { m -> m.artifacts.lastOrNull { it.sourceId == artifact.sourceId } }
            if (earlier != null) carriedArtifacts += earlier else warn(BackupWarningCode.RESUME_UNINDEXED, artifact.logicalName, artifact.sourceId)
        }
    }

    /** First run: the plan's manifest name; a resume into a folder that has it: a dated sibling. */
    private suspend fun chooseManifestTarget(p: BackupPlan): String {
        if (!resuming) return p.manifestPath
        val exists = retrying(p.manifestPath) { sessions.get().stat(p.manifestPath) } != null
        return if (exists) ArtifactNaming.join(p.jobDir, ArtifactNaming.resumedManifestName(startedAt, p.encryptedManifest)) else p.manifestPath
    }

    // --- Retry / session -----------------------------------------------------------------------

    private fun policyFor(transfer: TransferResolution): RetryPolicy =
        // Staged uploads retry (with resume) inside uploadStaged; rebuilding would only redo work.
        if (transfer == TransferResolution.Staged) policy.withoutRetries() else policy

    private suspend fun <T> retrying(label: String, retryPolicy: RetryPolicy = policy, block: suspend (attempt: Int) -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!retryPolicy.shouldRetry(attempt, e)) throw e
                backoff(label, attempt, e)
                attempt++
            }
        }
    }

    private suspend fun backoff(label: String, failedAttempt: Int, error: Exception) {
        val wait = policy.delayBefore(failedAttempt + 1)
        DftbLog.w(TAG, "Transient failure on $label (attempt $failedAttempt), retrying in $wait ms: ${error.message}")
        emit(BackupEvent.Retrying(jobId, label, failedAttempt + 1, wait, error.message ?: error.javaClass.simpleName))
        delay(wait)
        sessions.reset()
    }

    private fun destinationRetryable(): Boolean = destination?.type?.isNetwork == true

    private fun currentPlan(): BackupPlan = plan ?: throw IllegalStateException("No plan")

    /** One lazily (re)connected session; [reset] after a transient failure forces a reconnect. */
    private inner class SessionHolder {
        @Volatile private var current: StorageSession? = null
        private val mutex = Mutex()

        suspend fun get(): StorageSession {
            current?.let { return it }
            return mutex.withLock {
                current ?: run {
                    val dest = destination ?: throw IllegalStateException("No destination")
                    deps.storageRegistry.backendFor(dest.type).connect(dest).also { current = it }
                }
            }
        }

        fun reset() {
            val session = current
            current = null
            session?.close()
        }
    }

    // --- Items ---------------------------------------------------------------------------------

    /** Collects manifest entries, per-item digests and hash records for one artifact attempt. */
    private inner class ItemRecorder(private val artifactName: String) {
        private val lock = Any()
        private val entries = ArrayList<ManifestItem>()
        private val readBytes = HashMap<Int, Long>()
        private val hashes = HashMap<Int, HashRecord>()

        fun record(item: SourceItem) {
            synchronized(lock) { entries += baseEntry(item) }
            itemsDone.incrementAndGet()
        }

        fun reserve(item: SourceItem): Int = synchronized(lock) {
            entries += baseEntry(item)
            entries.size - 1
        }

        fun wrap(item: SourceItem): SourceItem = when (item) {
            is SourceItem.File -> {
                val index = reserve(item)
                item.copy(open = { openTracked(index, item) })
            }
            is SourceItem.Stream -> {
                val index = reserve(item)
                item.copy(open = { openTracked(index, item) })
            }
            is SourceItem.Directory, is SourceItem.Symlink -> {
                record(item)
                item
            }
        }

        suspend fun openTracked(index: Int, item: SourceItem): InputStream {
            val raw = when (item) {
                is SourceItem.File -> item.open()
                is SourceItem.Stream -> item.open()
                else -> throw IllegalArgumentException("Not a data item: ${item.archivePath}")
            }
            val digester = deps.checksumEngine.tee(NullOutputStream, algorithms)
            return ObservedInputStream(
                input = raw,
                digester = digester,
                onBytes = { n -> currentBytes.addAndGet(n) },
                isCancelled = isCancelled,
                onClosed = { read, digest, eof -> complete(index, item, read, digest, eof) },
            )
        }

        private fun complete(index: Int, item: SourceItem, read: Long, digest: DigestResult?, eof: Boolean) {
            val hex = digest?.hex.orEmpty()
            synchronized(lock) {
                entries[index] = entries[index].copy(sizeBytes = read, digests = ManifestText.digestMap(hex).takeIf { it.isNotEmpty() })
                readBytes[index] = read
                hex[primary]?.let { value ->
                    hashes[index] = HashRecord(
                        backupId = jobId,
                        relativePath = "$artifactName/${item.archivePath}",
                        algorithm = primary,
                        hash = value,
                        sizeBytes = read,
                        modifiedAt = item.metadata.modifiedAtMillis ?: 0L,
                    )
                }
            }
            itemsDone.incrementAndGet()
            val declared = when (item) {
                is SourceItem.File -> item.sizeBytes
                is SourceItem.Stream -> item.expectedSizeBytes?.takeIf { item.sizeIsExact }
                else -> null
            }
            if (eof && declared != null && declared != read) {
                warn(BackupWarningCode.ITEM_SIZE_MISMATCH, "$artifactName/${item.archivePath}", "declared $declared, read $read bytes")
            }
        }

        fun attachStored(index: Int, storedPath: String, stored: StoredFile) = synchronized(lock) {
            entries[index] = entries[index].copy(
                storedPath = storedPath,
                storedBytes = stored.bytes,
                storedDigests = ManifestText.digestMap(stored.digests),
                encryption = stored.encryption?.let(ManifestEncryption::of),
            )
        }

        fun items(): List<ManifestItem> = synchronized(lock) { ArrayList(entries) }

        fun hashRecords(): List<HashRecord> = synchronized(lock) { hashes.entries.sortedBy { it.key }.map { it.value } }

        fun sourceBytes(): Long = synchronized(lock) { BackupPlanner.saturatingSum(readBytes.values.toList()) }

        private fun baseEntry(item: SourceItem) = ManifestItem(
            archivePath = item.archivePath,
            type = ManifestText.itemType(item),
            sizeBytes = ManifestText.declaredSize(item),
            linkTarget = (item as? SourceItem.Symlink)?.target,
            metadata = ManifestMetadata.of(item.metadata),
        )
    }

    // --- Journal, history, progress ------------------------------------------------------------

    private suspend fun recordStart() {
        val existing = try {
            deps.checkpointRepository.get(jobId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val cp = JobCheckpoint(
            jobId = jobId,
            profileId = profile.id,
            type = JobType.BACKUP,
            status = JobStatus.RUNNING,
            attempt = (existing?.attempt ?: 0) + 1,
            state = existing?.state ?: CheckpointState.INITIAL,
            createdAt = existing?.createdAt ?: startedAt,
            updatedAt = startedAt,
            resumeAfterReboot = profile.schedule?.runOnBootIfMissed ?: true,
        )
        checkpoint = cp
        saveCheckpoint(cp)
        saveHistory(historyRecord(JobStatus.RUNNING, finishedAt = null, outcome = null, errorMessage = null))
    }

    private suspend fun updateCheckpoint(transform: (CheckpointState) -> CheckpointState) {
        val cp = checkpoint ?: return
        val next = cp.copy(state = transform(cp.state), updatedAt = deps.clock.nowMillis())
        checkpoint = next
        saveCheckpoint(next)
    }

    private suspend fun saveCheckpoint(cp: JobCheckpoint) {
        try {
            deps.checkpointRepository.save(cp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Checkpoint write failed for $jobId", e)
            if (!checkpointWarned) {
                checkpointWarned = true
                warn(BackupWarningCode.CHECKPOINT_WRITE_FAILED, null, e.message)
            }
        }
    }

    private suspend fun saveHistory(record: HistoryRecord) {
        try {
            deps.historyRepository.save(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "History write failed for $jobId", e)
        }
    }

    private suspend fun recordHashes(records: List<HashRecord>) {
        if (records.isEmpty()) return
        try {
            deps.hashRegistryRepository.record(records)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Hash records failed for $jobId", e)
            warn(BackupWarningCode.HASH_RECORDS_FAILED, null, e.message)
        }
    }

    private fun historyRecord(status: JobStatus, finishedAt: Long?, outcome: BackupOutcome?, errorMessage: String?) = HistoryRecord(
        id = jobId,
        profileId = profile.id,
        destinationId = destination?.id,
        type = JobType.BACKUP,
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt,
        bytesProcessed = outcome?.sourceBytes ?: 0L,
        itemCount = outcome?.itemCount ?: 0,
        artifactPath = outcome?.jobDir,
        artifactSizeBytes = outcome?.storedBytes,
        archiveFormat = options.archiveFormat,
        checksumAlgorithm = primary,
        checksumManifestPath = outcome?.manifestPath,
        errorMessage = errorMessage,
        logExcerpt = outcome?.warnings?.takeIf { it.isNotEmpty() }?.joinToString("\n") { deps.messages.warning(it) }?.take(LOG_EXCERPT_LIMIT),
    )

    /** Terminal bookkeeping; runs non-cancellably so a cancelled job is still recorded. */
    private suspend fun finish(status: JobStatus, failure: BackupFailure?): BackupOutcome = withContext(NonCancellable) {
        // A cancel that lands while a normal finish runs must not record the job twice.
        finalOutcome?.let { return@withContext it }
        if (status != JobStatus.COMPLETED && status != JobStatus.COMPLETED_WITH_WARNINGS) writeManifestOnInterruption()
        cleanupPartials()
        val now = deps.clock.nowMillis()
        val outcome = BackupOutcome(
            jobId = jobId,
            profileId = profile.id,
            status = status,
            jobDir = plan?.jobDir?.takeIf { completed.isNotEmpty() || manifestWritten },
            manifestPath = writtenManifestPath?.takeIf { manifestWritten },
            artifacts = completed.toList(),
            storedBytes = BackupPlanner.saturatingSum(completed.map { it.storedBytes }),
            sourceBytes = sourceBytesDone.get(),
            itemCount = itemsDone.get(),
            startedAtMillis = startedAt,
            finishedAtMillis = now,
            warnings = synchronized(warnings) { warnings.toList() },
            failure = failure,
        )
        val summary = when (status) {
            JobStatus.COMPLETED -> deps.messages.completed(completed.size)
            JobStatus.COMPLETED_WITH_WARNINGS -> deps.messages.completedWithWarnings(completed.size, outcome.warnings.size)
            JobStatus.CANCELLED, JobStatus.INTERRUPTED -> deps.messages.cancelled()
            else -> failure?.let(deps.messages::failure) ?: deps.messages.failure(BackupFailure(BackupErrorCode.INTERNAL))
        }
        saveHistory(
            historyRecord(
                status,
                now,
                outcome,
                errorMessage = if (status == JobStatus.COMPLETED || status == JobStatus.COMPLETED_WITH_WARNINGS) null else summary,
            ),
        )
        checkpoint?.let { cp ->
            // A destination that stayed unreachable leaves the job resumable instead of terminal.
            val journalStatus = if (status == JobStatus.FAILED && failure?.storageReason?.isRetryable == true) JobStatus.INTERRUPTED else status
            val next = cp.copy(status = journalStatus, updatedAt = now)
            checkpoint = next
            saveCheckpoint(next)
        }
        if (!deps.stagingArea.release(jobId)) DftbLog.w(TAG, "Staging for $jobId could not be fully removed")
        flushWarningsQuietly()
        val terminalPhase = when (status) {
            JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS -> JobPhase.COMPLETED
            JobStatus.CANCELLED, JobStatus.INTERRUPTED -> JobPhase.CANCELLED
            else -> JobPhase.FAILED
        }
        phase = terminalPhase
        message = summary
        currentItem = null
        val terminal = buildProgress(now)
        synchronized(publishLock) {
            finished = true
            deps.progressBus.publish(terminal)
        }
        finalOutcome = outcome
        emitQuietly(BackupEvent.Progress(jobId, terminal))
        emitQuietly(BackupEvent.Finished(jobId, outcome))
        outcome
    }

    private suspend fun cleanupPartials() {
        val parts = synchronized(pendingParts) { pendingParts.toList() }
        if (parts.isEmpty()) return
        sessions.reset()
        for (part in parts) {
            try {
                withTimeout(CLEANUP_TIMEOUT_MILLIS) { sessions.get().delete(part) }
                pendingParts -= part
            } catch (e: Exception) {
                DftbLog.w(TAG, "Could not remove $part after the job ended: ${e.message}")
                warn(BackupWarningCode.CLEANUP_FAILED, part, e.message)
            }
        }
    }

    private fun releaseResources() {
        try {
            jobMasker?.close()
        } catch (e: Exception) {
            DftbLog.w(TAG, "Masker close failed", e)
        }
        keys?.wipe()
        runOptions.passphrase?.fill('\u0000')
        sessions.reset()
    }

    private fun buildProgress(now: Long): JobProgress {
        val processed = sourceBytesDone.get() + currentBytes.get()
        val rate = synchronized(meter) { meter.sample(now, processed) }
        return JobProgress(
            jobId = jobId,
            kind = JobKind.BACKUP,
            title = profile.name,
            phase = phase,
            currentItem = currentItem,
            processedBytes = processed,
            totalBytes = totalBytes,
            processedItems = itemsDone.get(),
            totalItems = totalItems,
            bytesPerSecond = rate,
            startedAtMillis = startedAt,
            updatedAtMillis = now,
            message = message,
        )
    }

    private suspend fun publishProgress() {
        val progress = buildProgress(deps.clock.nowMillis())
        val published = synchronized(publishLock) {
            if (finished) {
                false
            } else {
                deps.progressBus.publish(progress)
                true
            }
        }
        if (published) emit(BackupEvent.Progress(jobId, progress))
    }

    // --- Warnings & events ---------------------------------------------------------------------

    private fun warn(code: BackupWarningCode, subject: String?, detail: String?) {
        warnings += BackupWarning(code, subject, detail)
    }

    private suspend fun flushWarnings() {
        val pending = synchronized(warnings) {
            val slice = warnings.subList(warningsEmitted, warnings.size).toList()
            warningsEmitted = warnings.size
            slice
        }
        pending.forEach { emit(BackupEvent.Warning(jobId, it)) }
    }

    private suspend fun flushWarningsQuietly() {
        try {
            flushWarnings()
        } catch (e: Exception) {
            DftbLog.d(TAG, "Event collector gone while flushing warnings")
        }
    }

    private suspend fun emit(event: BackupEvent) = onEvent(event)

    /** For terminal events: the collector may already be gone after a cancel. */
    private suspend fun emitQuietly(event: BackupEvent) {
        try {
            onEvent(event)
        } catch (e: Exception) {
            DftbLog.d(TAG, "Event collector gone; dropped ${event.javaClass.simpleName}")
        }
    }

    /** Language-neutral warning line for the manifest. */
    private fun warningText(warning: BackupWarning): String =
        listOfNotNull(warning.code.name, warning.subject, warning.detail).joinToString(": ")

    private fun step(code: BackupErrorCode, detail: String?, cause: Throwable? = null): BackupStepException =
        BackupStepException(BackupFailure(code, detail), cause)

    private fun failureOf(error: Throwable, fallback: BackupErrorCode): BackupFailure =
        when (val fault = RetryPolicy.destinationFailure(error)) {
            is StorageException -> BackupFailure(BackupErrorCode.STORAGE, fault.message, fault.reason)
            is DestinationIOException -> BackupFailure(
                BackupErrorCode.STORAGE,
                fault.message,
                if (fault.retryable) StorageException.Reason.NETWORK else StorageException.Reason.PROTOCOL,
            )
            else -> BackupFailure(fallback, error.message ?: error.javaClass.simpleName)
        }

    // --- Small types ---------------------------------------------------------------------------

    private class StoredFile(
        val path: String,
        val bytes: Long,
        val digests: Map<ChecksumAlgorithm, String>,
        val encryption: EncryptionHeader?,
    )

    private class BuiltArtifact(
        val storedFiles: List<StoredFile>,
        val storedBytes: Long,
        val digests: Map<ChecksumAlgorithm, String>,
        val encryption: EncryptionHeader?,
        val sourceBytes: Long,
        val items: List<ManifestItem>,
        val hashRecords: List<HashRecord>,
        val sidecarEntries: List<ChecksumSidecar.Entry>,
    )

    /**
     * Key material for the job: the spec (for the masker) and one lazily prepared [JobStreamKey]
     * reused by every stream and retry. The passphrase copy is owned here and wiped at the end.
     */
    private inner class KeyProvider(private val vaultRef: String?, private val passphrase: CharArray?) {
        private var prepared: JobStreamKey? = null

        fun newSpec(): CipherKeySpec = when {
            passphrase != null -> CipherKeySpec.Passphrase(passphrase)
            vaultRef != null -> CipherKeySpec.VaultKey(vaultRef)
            else -> throw IllegalStateException("No key material")
        }

        suspend fun streamKey(): JobStreamKey = prepared ?: deps.keyPreparer.prepare(newSpec()).also { prepared = it }

        fun wipe() {
            try {
                prepared?.close()
            } finally {
                prepared = null
                passphrase?.fill('\u0000')
            }
        }
    }

    companion object {
        private const val TAG = "BackupEngine"
        /** Checkpoint extras keys (read by resume). */
        const val EXTRA_JOB_DIR = "jobDir"
        const val EXTRA_DESTINATION_ID = "destinationId"
        /** Prefix of the persisted masking params (scheme, salt, KDF cost; never a key). */
        const val EXTRA_MASK_PREFIX = "mask."
        private const val INTERRUPT_MANIFEST_TIMEOUT_MILLIS = 30_000L
        private const val CLEANUP_TIMEOUT_MILLIS = 15_000L
        private const val LOG_EXCERPT_LIMIT = 4_000
    }
}
