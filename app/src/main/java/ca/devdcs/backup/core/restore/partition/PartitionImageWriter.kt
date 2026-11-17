package ca.devdcs.backup.core.restore.partition

import ca.devdcs.backup.R
import ca.devdcs.backup.core.discovery.partition.PartitionResolutionFailure
import ca.devdcs.backup.core.discovery.partition.PartitionScanResult
import ca.devdcs.backup.core.discovery.partition.PartitionScanUnavailable
import ca.devdcs.backup.core.discovery.partition.PartitionScanner
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.discovery.partition.PartitionViews
import ca.devdcs.backup.core.discovery.partition.ResolvedPartition
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.pipeline.checksum.ChecksumSidecar
import ca.devdcs.backup.core.pipeline.checksum.Digesters
import ca.devdcs.backup.core.pipeline.source.RootByteStreams
import ca.devdcs.backup.core.restore.RestoreUnitResult
import ca.devdcs.backup.core.restore.engine.RestoreProgress
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bytes of one partition image and what they must hash to. [open] is called once for the
 * verification read and once for the write, so it must return the same bytes each time.
 */
class ImagePayload(
    /** User-facing name (partition or file). */
    val label: String,
    val declaredSizeBytes: Long?,
    /** Expected plaintext digests; empty when none were recorded (the verified first read is then the reference). */
    val expectedDigests: Map<ChecksumAlgorithm, String>,
    val open: suspend () -> InputStream,
)

/** Root + a fresh partition scan, or why partition writes cannot run now. */
sealed interface WriterEnvironment {
    data class Ready(val executor: CommandExecutor, val scan: PartitionScanResult) : WriterEnvironment
    data class Unavailable(val message: String) : WriterEnvironment
}

/** A resolved target node and its probed state, or a user-facing reason it cannot be written. */
sealed interface TargetInspection {
    data class Ready(val resolved: ResolvedPartition, val state: BlockDeviceState) : TargetInspection
    data class Failed(val message: String) : TargetInspection
}

/**
 * The one write path for partition images, shared by DFTB partition restores and TWRP imports.
 * Order is fixed and every step can only refuse, never weaken a later one: resolve the node, probe
 * it, run the [PartitionSafetyGates], read the whole image once to prove it matches its digest,
 * write it through [RootBlockWriter] (`dd conv=fsync`, same-call size re-check), then read the
 * device back and compare digests. A failure after the first byte reached the device is always
 * reported as a partial write.
 */
@Singleton
class PartitionImageWriter @Inject constructor(
    private val router: CommandExecutorRouter,
    private val scanner: PartitionScanner,
    private val probe: BlockDeviceProbe,
    private val writer: RootBlockWriter,
    private val streams: RootByteStreams,
    private val strings: PartitionRestoreStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun environment(): WriterEnvironment = withContext(io) {
        val executor = router.current()
        if (executor.tier != ElevationTier.ROOT || !executor.isAvailable()) {
            return@withContext WriterEnvironment.Unavailable(strings.get(R.string.partition_restore_root_required))
        }
        when (val scan = scanner.scan()) {
            is PartitionScanResult -> WriterEnvironment.Ready(executor, scan)
            is PartitionScanUnavailable -> WriterEnvironment.Unavailable(
                strings.get(R.string.partition_restore_scan_failed, scan.detail ?: scan.reason.name),
            )
        }
    }

    fun slotFailureMessage(failure: SlotPlanFailure, name: String): String = when (failure) {
        SlotPlanFailure.DEVICE_NOT_AB -> strings.get(R.string.partition_restore_slot_device_not_ab, name)
        SlotPlanFailure.ACTIVE_SLOT_UNKNOWN -> strings.get(R.string.partition_restore_slot_unknown, name)
    }

    /** Resolve [plan] against the scan and probe the node. Read-only. */
    suspend fun inspect(env: WriterEnvironment.Ready, plan: SlotPlan.Planned): TargetInspection {
        val target = plan.target
        val resolution = PartitionViews.resolve(listOf(target), env.scan)
        val resolved = resolution.resolved.firstOrNull()
        if (resolved == null) {
            val failure = resolution.unresolved.firstOrNull()?.failure ?: PartitionResolutionFailure.NOT_FOUND
            val family = if (target.logical == true) PartitionSource.MAPPER else PartitionSource.BY_NAME
            val base = SlotMapping.splitSlot(target.name).first
            val otherSlotExists = env.scan.partitions.any { it.source == family && it.baseName.equals(base, ignoreCase = true) }
            val message = when {
                failure == PartitionResolutionFailure.ACTIVE_SLOT_UNKNOWN -> strings.get(R.string.partition_restore_slot_unknown, target.name)
                failure == PartitionResolutionFailure.SLOT_MISSING || otherSlotExists -> strings.get(R.string.partition_restore_slot_missing, target.name)
                else -> strings.get(R.string.partition_restore_not_found, target.name)
            }
            return TargetInspection.Failed(message)
        }
        if (resolved.partition.slot != plan.targetSlot) {
            return TargetInspection.Failed(strings.get(R.string.partition_restore_slot_layout, resolved.partition.name))
        }
        val aliases = setOfNotNull(resolved.partition.blockDevice, resolved.partition.realDevice)
        val state = probe.probe(env.executor, resolved.devicePath, aliases)
            ?: return TargetInspection.Failed(strings.get(R.string.partition_restore_probe_failed, resolved.partition.name))
        return TargetInspection.Ready(resolved, state)
    }

    fun gates(target: TargetInspection.Ready, origin: ImageOrigin, crossesSlot: Boolean, imageSizeBytes: Long?, options: PartitionRestoreOptions): GateReport =
        PartitionSafetyGates.evaluate(GateFacts(target.resolved.partition, target.state, origin, crossesSlot, imageSizeBytes), options)

    /** Localized refusal (all refusals joined) and override warnings of [report]. */
    fun describe(report: GateReport, partitionName: String): Pair<String?, List<String>> {
        val refusal = report.refusals.takeIf { it.isNotEmpty() }?.joinToString("\n") { strings.gate(it, partitionName) }
        return refusal to report.overridden.map { strings.overridden(it, partitionName) }
    }

    /**
     * Full verified write of [payload] to [target]. Never throws for ordinary failures; rethrows
     * CancellationException only before the first byte is written (the write itself and the
     * read-back are not cancellable: abandoning them would leave a half-flashed partition).
     */
    suspend fun write(
        unitId: String,
        env: WriterEnvironment.Ready,
        target: TargetInspection.Ready,
        payload: ImagePayload,
        origin: ImageOrigin,
        crossesSlot: Boolean,
        options: PartitionRestoreOptions,
        progress: RestoreProgress,
    ): RestoreUnitResult = withContext(io) {
        val name = target.resolved.partition.name
        val warnings = ArrayList<String>()
        fun fail(message: String) = RestoreUnitResult(unitId, ok = false, warnings = warnings.toList(), error = message)

        val early = gates(target, origin, crossesSlot, payload.declaredSizeBytes, options)
        describe(early, name).first?.let { return@withContext fail(it) }

        // 1. Prove the image is intact before touching the device.
        progress.onItem(strings.get(R.string.partition_restore_item_verifying_image, name))
        val reference = referenceAlgorithm(payload.expectedDigests)
        val verified = try {
            digestStream(payload, reference, progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext fail(strings.get(R.string.partition_restore_image_read_failed, payload.label, e.message ?: e.javaClass.simpleName))
        }
        val expected = payload.expectedDigests[reference]
        if (expected != null && !ChecksumSidecar.matches(expected, verified.hex)) {
            return@withContext fail(strings.get(R.string.partition_restore_image_digest_mismatch, payload.label))
        }
        if (expected == null) warnings += strings.get(R.string.partition_restore_no_reference_digest, payload.label)
        val referenceHex = expected ?: verified.hex

        if (progress.isCancelled()) throw CancellationException("restore cancelled")
        currentCoroutineContext().ensureActive()

        // The verification read can take hours over a network: re-probe so every gate (mounts,
        // dm holders, swap, size, read-only) is decided on the device's state right before the write.
        val partition = target.resolved.partition
        val freshState = probe.probe(env.executor, target.resolved.devicePath, setOfNotNull(partition.blockDevice, partition.realDevice))
            ?: return@withContext fail(strings.get(R.string.partition_restore_probe_failed, name))
        val fresh = TargetInspection.Ready(target.resolved, freshState)
        val confirmed = gates(fresh, origin, crossesSlot, verified.size, options)
        val (refusal, overrides) = describe(confirmed, name)
        if (refusal != null) return@withContext fail(refusal)
        warnings += overrides
        val targetSize = freshState.sizeBytes ?: return@withContext fail(strings.gate(GateHit(PartitionGate.TARGET_SIZE_UNKNOWN, name), name))

        // 2. Write and 3. read back; neither is abandoned half-way. The helper re-checks the in-use
        // conditions once more in the same root call that runs dd.
        withContext(NonCancellable) {
            val written = writeImage(env, fresh, payload, reference, targetSize, progress)
            when (written) {
                is WriteOutcome.Failed -> return@withContext fail(written.message)
                is WriteOutcome.Done -> if (!ChecksumSidecar.matches(referenceHex, written.hex) || written.size != verified.size) {
                    return@withContext fail(strings.get(R.string.partition_restore_source_changed, name))
                }
            }
            progress.onItem(strings.get(R.string.partition_restore_item_reading_back, name))
            val readBack = try {
                readBack(fresh, reference, verified.size, targetSize)
            } catch (e: Exception) {
                return@withContext fail(strings.get(R.string.partition_restore_readback_failed, name, e.message ?: e.javaClass.simpleName))
            }
            if (!ChecksumSidecar.matches(referenceHex, readBack)) {
                return@withContext fail(strings.get(R.string.partition_restore_readback_mismatch, name, referenceHex, readBack))
            }
            RestoreUnitResult(unitId, ok = true, warnings = warnings.toList())
        }
    }

    private sealed interface WriteOutcome {
        data class Done(val size: Long, val hex: String) : WriteOutcome
        data class Failed(val message: String) : WriteOutcome
    }

    private suspend fun writeImage(
        env: WriterEnvironment.Ready,
        target: TargetInspection.Ready,
        payload: ImagePayload,
        algorithm: ChecksumAlgorithm,
        targetSize: Long,
        progress: RestoreProgress,
    ): WriteOutcome {
        val partition = target.resolved.partition
        val name = partition.name
        progress.onItem(strings.get(R.string.partition_restore_item_writing, name))
        val out = try {
            writer.open(env.executor, target.resolved.devicePath, targetSize, setReadWrite = partition.source == PartitionSource.MAPPER, label = name)
        } catch (e: BlockWriteException) {
            return WriteOutcome.Failed(writeFailure(name, e, 0L))
        }
        val input = try {
            payload.open()
        } catch (e: Exception) {
            out.abort()
            return WriteOutcome.Failed(strings.get(R.string.partition_restore_image_read_failed, payload.label, e.message ?: e.javaClass.simpleName))
        }
        val digester = Digesters.create(algorithm)
        var total = 0L
        try {
            input.use { src ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    digester.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    total += n
                    progress.onBytes(n.toLong())
                }
            }
        } catch (e: BlockWriteException) {
            return WriteOutcome.Failed(writeFailure(name, e, out.bytesWritten))
        } catch (e: Exception) {
            // Reading the image failed mid-write: stop dd; whatever reached the device stays there.
            out.abort()
            val detail = e.message ?: e.javaClass.simpleName
            return WriteOutcome.Failed(
                if (out.bytesWritten == 0L) strings.get(R.string.partition_restore_image_read_failed, payload.label, detail)
                else strings.get(R.string.partition_restore_partial_write, name, out.bytesWritten, detail),
            )
        }
        try {
            out.close()
        } catch (e: BlockWriteException) {
            return WriteOutcome.Failed(writeFailure(name, e, out.bytesWritten))
        }
        return WriteOutcome.Done(total, digester.finishHex())
    }

    private fun writeFailure(name: String, e: BlockWriteException, bytes: Long): String = when {
        e.exitCode == BlockWriteScripts.EXIT_HELD -> strings.gate(GateHit(PartitionGate.HELD_BY_DEVICE_MAPPER, e.detail), name)
        e.exitCode == BlockWriteScripts.EXIT_MOUNTED -> strings.gate(GateHit(PartitionGate.MOUNTED, e.detail), name)
        !e.deviceTouched -> strings.get(R.string.partition_restore_refused_by_helper, name, e.detail)
        else -> strings.get(R.string.partition_restore_partial_write, name, bytes, e.detail)
    }

    /** Digest of the first [imageSize] bytes of the device, read with the page cache flushed. */
    private suspend fun readBack(target: TargetInspection.Ready, algorithm: ChecksumAlgorithm, imageSize: Long, targetSize: Long): String {
        val name = target.resolved.partition.name
        val digester = Digesters.create(algorithm)
        streams.open(BlockWriteScripts.readBackScript(target.resolved.devicePath), "read-back $name").use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = imageSize
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("device ended after ${imageSize - remaining} of $imageSize bytes")
                digester.update(buffer, 0, n)
                remaining -= n
            }
            // A full-size image reads to EOF so dd's exit status is checked; a shorter one stops early.
            if (imageSize == targetSize && input.read() >= 0) throw IOException("device is larger than $targetSize bytes")
        }
        return digester.finishHex()
    }

    private class Digested(val size: Long, val hex: String)

    /** Read all of [payload], hashing with [algorithm]. Cancellable between chunks. */
    private suspend fun digestStream(payload: ImagePayload, algorithm: ChecksumAlgorithm, progress: RestoreProgress): Digested {
        val digester = Digesters.create(algorithm)
        var total = 0L
        payload.open().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (progress.isCancelled()) throw CancellationException("restore cancelled")
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                digester.update(buffer, 0, n)
                total += n
            }
        }
        return Digested(total, digester.finishHex())
    }

    companion object {
        private const val BUFFER_SIZE = 1 shl 20

        /** SHA-256 when recorded (or nothing is), else the first recorded algorithm. */
        fun referenceAlgorithm(expected: Map<ChecksumAlgorithm, String>): ChecksumAlgorithm =
            if (expected.isEmpty() || ChecksumAlgorithm.SHA256 in expected) ChecksumAlgorithm.SHA256
            else expected.keys.minBy { it.ordinal }
    }
}
