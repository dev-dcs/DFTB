package ca.devdcs.backup.core.pipeline.source

import ca.devdcs.backup.core.discovery.partition.PartitionScanResult
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.discovery.partition.PartitionViews
import ca.devdcs.backup.core.discovery.partition.ResolvedPartition
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.pipeline.BackupSource
import ca.devdcs.backup.core.pipeline.SourceEstimate
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.PartitionTarget
import ca.devdcs.backup.domain.model.SlotPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Pure script builders for block-device imaging (unit-tested). */
internal object PartitionScripts {

    /** 4 MiB reads: large enough for full throughput, small enough to keep the helper's RSS low. */
    const val DD_BLOCK_SIZE = 4_194_304

    const val EXIT_BLOCKDEV_FAILED = 90
    const val EXIT_SIZE_MISMATCH = 91

    /**
     * Image [device]. When [expectedSize] is known the same root call first re-reads the size with
     * `blockdev --getsize64` and refuses to stream if it changed. No `conv=noerror`: a read error
     * stops dd with a non-zero exit, which fails the item instead of zero-filling the image.
     */
    fun imageScript(device: String, expectedSize: Long?): String {
        val dev = PrivilegedStreamScripts.q(device)
        val dd = "dd if=$dev bs=$DD_BLOCK_SIZE"
        if (expectedSize == null) return dd
        return "__sz=\$(blockdev --getsize64 $dev) || { echo 'blockdev --getsize64 failed' >&2; exit $EXIT_BLOCKDEV_FAILED; }; " +
            "if [ \"\$__sz\" != '$expectedSize' ]; then echo \"size changed: expected $expectedSize, device reports \$__sz\" >&2; exit $EXIT_SIZE_MISMATCH; fi; " +
            dd
    }

    /** One `<size> <device>` line per device (`-1` when blockdev fails). */
    fun sizeProbeScript(devices: List<String>): String =
        devices.joinToString("; ") { device ->
            val dev = PrivilegedStreamScripts.q(device)
            "__s=\$(blockdev --getsize64 $dev 2>/dev/null) || __s=-1; printf '%s %s\\n' \"\$__s\" $dev"
        } + "; true"

    fun parseSizeProbe(lines: List<String>): Map<String, Long> {
        val sizes = HashMap<String, Long>()
        for (line in lines) {
            val size = line.substringBefore(' ').trim().toLongOrNull() ?: continue
            val device = line.substringAfter(' ', "").trim()
            if (device.isNotEmpty() && size >= 0) sizes[device] = size
        }
        return sizes
    }
}

/**
 * Produces one raw image ([SourceItem.Stream]) per resolved partition, read with root `dd`
 * through [RootByteStreams]. Exists so partition imaging has exactly one implementation shared by
 * the plain archive path and the TWRP (Nandroid) path ([openImage]).
 */
class PartitionImageSource internal constructor(
    private val partitions: List<ResolvedPartition>,
    /** Device path -> exact size from `blockdev --getsize64` (absent = unknown). */
    private val sizes: Map<String, Long>,
    private val layout: ArchiveLayout,
    private val streams: RootByteStreams,
    private val io: CoroutineDispatcher,
) : BackupSource {

    override val sourceId: String = "partitions:" + stableHash(partitions.map { it.partition.name + "@" + it.partition.blockDevice }.sorted())

    override suspend fun estimate(): SourceEstimate {
        val known = partitions.mapNotNull { sizeOf(it) }
        return SourceEstimate(partitions.size.toLong(), known.sum(), exact = partitions.all { sizes[it.devicePath] != null })
    }

    override fun items(): Flow<SourceItem> = flow {
        for (resolved in partitions) {
            // Only a blockdev-probed size is exact and guarded; a sysfs size is an estimate (the probe
            // failed for that node, so the open-time blockdev check would fail too).
            val probed = sizes[resolved.devicePath]
            emit(
                SourceItem.Stream(
                    archivePath = layout.partitionImage(resolved.partition.name, logical = resolved.partition.source == PartitionSource.MAPPER),
                    expectedSizeBytes = probed ?: resolved.partition.sizeBytes,
                    sizeIsExact = probed != null,
                    open = { openImage(streams, resolved, probed) },
                ),
            )
        }
    }.flowOn(io)

    private fun sizeOf(resolved: ResolvedPartition): Long? = sizes[resolved.devicePath] ?: resolved.partition.sizeBytes

    companion object {
        /**
         * Open an image of [resolved]. [probedSize] must come from `blockdev --getsize64`: when set,
         * the same root call re-verifies it and exactly that many bytes are enforced.
         */
        suspend fun openImage(streams: RootByteStreams, resolved: ResolvedPartition, probedSize: Long?): InputStream {
            val size = probedSize
            val label = "partition ${resolved.partition.name}"
            val raw = streams.open(PartitionScripts.imageScript(resolved.devicePath, size), label)
            return if (size != null) ExactLengthInputStream(raw, size, label) else raw
        }
    }
}

/**
 * Prepares [PartitionImageSource]s: resolves targets against a scan, requires the Root tier, and
 * fixes exact sizes with one `blockdev --getsize64` round trip.
 */
@Singleton
class PartitionImageSourceFactory @Inject constructor(
    private val router: CommandExecutorRouter,
    private val streams: RootByteStreams,
    private val strings: PipelineSourceStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun prepare(
        targets: List<PartitionTarget>,
        scan: PartitionScanResult,
        layout: ArchiveLayout,
        overridePolicy: SlotPolicy? = null,
    ): SourcePreparation<PartitionImageSource> = withContext(io) {
        if (targets.isEmpty()) return@withContext strings.unavailable(SourceUnavailableReason.NOTHING_SELECTED)
        val resolution = PartitionViews.resolve(targets, scan, overridePolicy)
        val prepared = prepareResolved(resolution.resolved, layout)
        if (prepared !is SourcePreparation.Ready) return@withContext prepared
        val unresolved = resolution.unresolved.map { SourceWarning(SourceWarningKind.PARTITION_UNRESOLVED, it.target.name, it.failure.name) }
        prepared.copy(warnings = unresolved + prepared.warnings)
    }

    /** Same as [prepare] for partitions already resolved by the caller. */
    suspend fun prepareResolved(resolved: List<ResolvedPartition>, layout: ArchiveLayout): SourcePreparation<PartitionImageSource> = withContext(io) {
        if (resolved.isEmpty()) return@withContext strings.unavailable(SourceUnavailableReason.NOTHING_SELECTED)
        val executor = router.current()
        if (executor.tier != ElevationTier.ROOT) return@withContext strings.unavailable(SourceUnavailableReason.ROOT_REQUIRED, executor.tier.name)
        if (!executor.isAvailable()) return@withContext strings.unavailable(SourceUnavailableReason.ROOT_NOT_GRANTED)
        val sizes = probeSizes(resolved.map { it.devicePath }.distinct())
        val warnings = resolved.filter { sizes[it.devicePath] == null && it.partition.sizeBytes == null }
            .map { SourceWarning(SourceWarningKind.PARTITION_SIZE_UNKNOWN, it.partition.name) }
        SourcePreparation.Ready(PartitionImageSource(resolved, sizes, layout, streams, io), warnings)
    }

    /** Exact block-device sizes; missing entries mean blockdev failed for that node. */
    suspend fun probeSizes(devices: List<String>): Map<String, Long> {
        if (devices.isEmpty()) return emptyMap()
        val result = router.current().execute(Command(listOf("sh", "-c", PartitionScripts.sizeProbeScript(devices)), timeoutMillis = PROBE_TIMEOUT_MILLIS))
        return if (result.launched) PartitionScripts.parseSizeProbe(result.stdout) else emptyMap()
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 30_000L
    }
}
