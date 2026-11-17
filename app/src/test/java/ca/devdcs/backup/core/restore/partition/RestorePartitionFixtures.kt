package ca.devdcs.backup.core.restore.partition

import ca.devdcs.backup.core.discovery.partition.DiscoveredPartition
import ca.devdcs.backup.core.discovery.partition.PartitionCategory
import ca.devdcs.backup.core.discovery.partition.PartitionRisk
import ca.devdcs.backup.core.discovery.partition.PartitionScanResult
import ca.devdcs.backup.core.discovery.partition.PartitionSlot
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.pipeline.engine.ManifestDevice

/** Small hand-built scans and states for the partition-restore tests. */
object RestorePartitionFixtures {

    fun node(
        name: String,
        real: String,
        active: PartitionSlot? = PartitionSlot.A,
        source: PartitionSource = PartitionSource.BY_NAME,
        risk: PartitionRisk = PartitionRisk.SAFE_TO_IMAGE,
        size: Long = 67_108_864L,
    ): DiscoveredPartition {
        val (base, slot) = SlotMapping.splitSlot(name)
        val dir = if (source == PartitionSource.MAPPER) "/dev/block/mapper" else "/dev/block/by-name"
        return DiscoveredPartition(
            name = name,
            baseName = base,
            slot = slot,
            blockDevice = "$dir/$name",
            realDevice = real,
            sizeBytes = size,
            isLogical = source == PartitionSource.MAPPER,
            category = PartitionCategory.OTHER,
            isActiveSlot = slot == PartitionSlot.NONE || slot == active,
            risk = risk,
            source = source,
        )
    }

    fun abScan(active: PartitionSlot? = PartitionSlot.A): PartitionScanResult = PartitionScanResult(
        abDevice = true,
        activeSlot = active,
        dynamicPartitions = true,
        virtualAb = true,
        partitions = listOf(
            node("boot_a", "/dev/block/sda12", active),
            node("boot_b", "/dev/block/sda13", active),
            node("persist", "/dev/block/sda4", active, risk = PartitionRisk.CRITICAL_IDENTITY),
            node("system_a", "/dev/block/dm-1", active, PartitionSource.MAPPER),
            node("system_b", "/dev/block/dm-2", active, PartitionSource.MAPPER),
            node("vendor_b", "/dev/block/dm-3", active, PartitionSource.MAPPER),
            node("super", "/dev/block/sda28", active),
        ),
        warnings = emptyList(),
    )

    fun legacyScan(): PartitionScanResult = PartitionScanResult(
        abDevice = false,
        activeSlot = null,
        dynamicPartitions = false,
        virtualAb = false,
        partitions = listOf(node("boot", "/dev/block/mmcblk0p20", null), node("recovery", "/dev/block/mmcblk0p21", null)),
        warnings = emptyList(),
    )

    fun idleState(path: String, size: Long? = 67_108_864L) = BlockDeviceState(
        path = path,
        canonicalPath = path,
        isBlockDevice = true,
        sizeBytes = size,
        readOnly = false,
        holders = emptyList(),
        mountPoints = emptyList(),
        usedAsSwap = false,
    )

    val PIXEL = ManifestDevice("Google", "Pixel 8", "shiba", "google/shiba/shiba:15/AP4A/1:user/release-keys", 35, "_a")
}
