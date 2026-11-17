package ca.devdcs.backup.core.restore.partition

import ca.devdcs.backup.core.discovery.partition.DiscoveredPartition
import ca.devdcs.backup.core.discovery.partition.PartitionRisk
import ca.devdcs.backup.core.discovery.partition.PartitionSlot
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.pipeline.engine.ManifestDevice
import java.util.Locale

/** Where the image came from, for the device / build gates. */
sealed interface ImageOrigin {
    /** A DFTB image: compare the manifest's device record with this device. */
    data class Manifest(val backup: ManifestDevice, val current: ManifestDevice) : ImageOrigin

    /**
     * A TWRP image: TWRP only records the device serial (folder name). [currentDeviceId] is this
     * device's serial sanitized the same way, or null when it could not be read.
     */
    data class Twrp(val folderDeviceId: String?, val currentDeviceId: String?) : ImageOrigin
}

/** Facts the gates decide on; all collected before a single byte is written. */
data class GateFacts(
    val partition: DiscoveredPartition,
    val state: BlockDeviceState,
    val origin: ImageOrigin,
    val crossesSlot: Boolean,
    /** Plaintext image size; null while unknown (re-evaluated once the image has been read). */
    val imageSizeBytes: Long?,
)

/**
 * Pure safety policy for writing a block device from running Android. Exists so every refusal is
 * decided (and tested) in one place and the writer only ever runs after [evaluate] allowed it.
 */
object PartitionSafetyGates {

    /** Never written from Android: the running system depends on them (or contains them, for super). */
    val ALWAYS_LIVE: Set<String> = setOf("userdata", "data", "super", "metadata")

    /** Live when they belong to the running slot (or are not slotted). */
    val LIVE_IN_RUNNING_SLOT: Set<String> = setOf(
        "system", "system_ext", "vendor", "product", "odm", "vendor_dlkm", "odm_dlkm", "system_dlkm",
    )

    fun evaluate(facts: GateFacts, options: PartitionRestoreOptions): GateReport {
        val hits = ArrayList<GateHit>()
        val p = facts.partition
        val state = facts.state

        hits += identityHits(facts.origin)
        if (facts.crossesSlot) hits += GateHit(PartitionGate.CROSS_SLOT, p.name)
        if (p.risk == PartitionRisk.CRITICAL_IDENTITY) hits += GateHit(PartitionGate.CRITICAL_IDENTITY, p.name)

        if (!state.isBlockDevice) hits += GateHit(PartitionGate.NOT_BLOCK_DEVICE, state.path)
        if (isLive(p)) hits += GateHit(PartitionGate.LIVE_SYSTEM_PARTITION, p.name)
        if (p.source == PartitionSource.MAPPER && isSnapshotNode(p.name)) hits += GateHit(PartitionGate.SNAPSHOT_DEVICE, p.name)
        if (state.mountPoints.isNotEmpty()) hits += GateHit(PartitionGate.MOUNTED, state.mountPoints.joinToString(", "))
        if (state.usedAsSwap) hits += GateHit(PartitionGate.SWAP, p.name)
        if (state.holders.isNotEmpty()) {
            hits += GateHit(PartitionGate.HELD_BY_DEVICE_MAPPER, state.holders.joinToString(", ") { h -> h.dmName?.let { "${h.kernelName} ($it)" } ?: h.kernelName })
        }
        // Logical nodes are switched read-write by the writer (blockdev --setrw); physical ones are not.
        if (p.source != PartitionSource.MAPPER && state.readOnly == true) hits += GateHit(PartitionGate.READ_ONLY, p.name)

        val target = state.sizeBytes
        val image = facts.imageSizeBytes
        when {
            target == null -> hits += GateHit(PartitionGate.TARGET_SIZE_UNKNOWN, p.name)
            image == null -> Unit
            image > target -> hits += GateHit(PartitionGate.IMAGE_LARGER, "$image > $target")
            image < target -> hits += GateHit(PartitionGate.IMAGE_SMALLER, "$image < $target")
        }
        val refusals = ArrayList<GateHit>()
        val overridden = ArrayList<GateHit>()
        for (hit in hits) {
            if (hit.gate.overridable && isOverridden(hit.gate, options)) overridden += hit else refusals += hit
        }
        return GateReport(refusals, overridden)
    }

    /** userdata/super/metadata always; system-family partitions of the running slot or un-slotted. */
    fun isLive(partition: DiscoveredPartition): Boolean {
        val base = partition.baseName.lowercase(Locale.ROOT)
        if (base in ALWAYS_LIVE) return true
        if (base !in LIVE_IN_RUNNING_SLOT) return false
        return partition.slot == PartitionSlot.NONE || partition.isActiveSlot
    }

    /** Virtual A/B helper nodes (`system_b-cow`, `-cow-img`, `-base`, `-src`, `-verity`) contain a '-'. */
    fun isSnapshotNode(name: String): Boolean = '-' in name

    fun identityHits(origin: ImageOrigin): List<GateHit> = when (origin) {
        is ImageOrigin.Manifest -> {
            val b = origin.backup
            val c = origin.current
            val sameHardware = same(b.manufacturer, c.manufacturer) && same(b.model, c.model) && same(b.device, c.device)
            when {
                !sameHardware -> listOf(GateHit(PartitionGate.DEVICE_MISMATCH, "${describe(b)} -> ${describe(c)}"))
                !same(b.fingerprint, c.fingerprint) -> listOf(GateHit(PartitionGate.BUILD_MISMATCH, "${b.fingerprint} -> ${c.fingerprint}"))
                else -> emptyList()
            }
        }
        is ImageOrigin.Twrp -> {
            val folder = origin.folderDeviceId
            val current = origin.currentDeviceId
            if (folder.isNullOrBlank() || current.isNullOrBlank() || folder != current) {
                listOf(GateHit(PartitionGate.DEVICE_MISMATCH, "${folder ?: "?"} -> ${current ?: "?"}"))
            } else {
                emptyList()
            }
        }
    }

    private fun isOverridden(gate: PartitionGate, options: PartitionRestoreOptions): Boolean = when (gate) {
        PartitionGate.DEVICE_MISMATCH -> options.allowOtherDevice
        PartitionGate.BUILD_MISMATCH -> options.allowOtherBuild
        PartitionGate.CROSS_SLOT -> options.allowCrossSlot
        PartitionGate.IMAGE_SMALLER -> options.allowSmallerImage
        PartitionGate.CRITICAL_IDENTITY -> options.allowCriticalIdentity
        else -> false
    }

    /** Both known and equal (case-insensitive); an unknown value never counts as a match. */
    private fun same(a: String, b: String): Boolean = a.isNotBlank() && b.isNotBlank() && a.trim().equals(b.trim(), ignoreCase = true)

    private fun describe(d: ManifestDevice): String = "${d.manufacturer} ${d.model} (${d.device})".trim()
}
