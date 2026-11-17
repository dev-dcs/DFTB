package ca.devdcs.backup.core.restore.partition

import ca.devdcs.backup.core.discovery.partition.PartitionScanResult
import ca.devdcs.backup.core.discovery.partition.PartitionSlot
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout
import ca.devdcs.backup.domain.model.PartitionTarget
import ca.devdcs.backup.domain.model.SlotPolicy

/**
 * The node an image was taken from, as recorded by its archive path (`partitions/boot_a.img`,
 * `partitions/mapper/system_b.img`). [logical] is the node family, which a restore never crosses.
 */
data class ImagedNode(val nodeName: String, val logical: Boolean) {
    val baseName: String get() = SlotMapping.splitSlot(nodeName).first
    val slot: PartitionSlot get() = SlotMapping.splitSlot(nodeName).second
}

/** Why no target slot could be chosen. */
enum class SlotPlanFailure {
    /** The image is of an A/B slot but this device has no slots. */
    DEVICE_NOT_AB,

    /** The choice needs the current boot slot, which the device does not report. */
    ACTIVE_SLOT_UNKNOWN,
}

/** Result of [SlotMapping.plan]. */
sealed interface SlotPlan {
    /**
     * Write to [target], which names exactly one node. [targetSlot] is what the resolved node must
     * be (checked after resolution so a NONE-slot image can never land in an A/B slot).
     */
    data class Planned(
        val target: PartitionTarget,
        val sourceSlot: PartitionSlot,
        val targetSlot: PartitionSlot,
        /** True when the slot letter written differs from the one imaged (needs explicit consent). */
        val crossesSlot: Boolean,
    ) : SlotPlan

    data class Failed(val failure: SlotPlanFailure) : SlotPlan
}

/**
 * Pure slot policy for partition restores. Exists so "which slot does this image go back to" is
 * decided in one tested place for DFTB images and TWRP images alike.
 */
object SlotMapping {

    private const val IMAGE_SUFFIX = ".img"
    private val MAPPER_PREFIX = "${ArchiveLayout.PARTITIONS}/mapper/"
    private val BY_NAME_PREFIX = "${ArchiveLayout.PARTITIONS}/"

    /** Parse an archive path written by [ArchiveLayout.partitionImage]; null for anything else. */
    fun parseArchivePath(archivePath: String): ImagedNode? {
        val path = archivePath.trimStart('/')
        if (!path.endsWith(IMAGE_SUFFIX)) return null
        val (rest, logical) = when {
            path.startsWith(MAPPER_PREFIX) -> path.removePrefix(MAPPER_PREFIX) to true
            path.startsWith(BY_NAME_PREFIX) -> path.removePrefix(BY_NAME_PREFIX) to false
            else -> return null
        }
        val node = rest.removeSuffix(IMAGE_SUFFIX)
        if (node.isEmpty() || '/' in node) return null
        return ImagedNode(node, logical)
    }

    /** `boot_a` -> (`boot`, A); `persist` -> (`persist`, NONE). */
    fun splitSlot(nodeName: String): Pair<String, PartitionSlot> {
        val lower = nodeName.lowercase()
        for (slot in listOf(PartitionSlot.A, PartitionSlot.B)) {
            if (lower.endsWith(slot.suffix) && nodeName.length > slot.suffix.length) {
                return nodeName.dropLast(slot.suffix.length) to slot
            }
        }
        return nodeName to PartitionSlot.NONE
    }

    /**
     * Plan the target of a DFTB image of [node]. [backupActiveSlot] is the slot the device booted
     * from when the image was taken (manifest `slotSuffix`); null when unknown, in which case
     * [SlotChoice.SAME_ROLE] falls back to the recorded letter.
     */
    fun plan(node: ImagedNode, backupActiveSlot: PartitionSlot?, scan: PartitionScanResult, choice: SlotChoice): SlotPlan {
        val sourceSlot = node.slot
        if (sourceSlot == PartitionSlot.NONE) {
            val target = PartitionTarget(name = node.nodeName, blockDevicePath = null, slotPolicy = SlotPolicy.NONE, logical = node.logical)
            return SlotPlan.Planned(target, PartitionSlot.NONE, PartitionSlot.NONE, crossesSlot = false)
        }
        if (!scan.abDevice) return SlotPlan.Failed(SlotPlanFailure.DEVICE_NOT_AB)
        val current = scan.activeSlot
        val targetSlot = when (choice) {
            SlotChoice.RECORDED -> sourceSlot
            SlotChoice.ACTIVE -> current ?: return SlotPlan.Failed(SlotPlanFailure.ACTIVE_SLOT_UNKNOWN)
            SlotChoice.INACTIVE -> current?.other() ?: return SlotPlan.Failed(SlotPlanFailure.ACTIVE_SLOT_UNKNOWN)
            SlotChoice.SAME_ROLE -> when {
                backupActiveSlot == null || backupActiveSlot == PartitionSlot.NONE -> sourceSlot
                current == null -> return SlotPlan.Failed(SlotPlanFailure.ACTIVE_SLOT_UNKNOWN)
                sourceSlot == backupActiveSlot -> current
                else -> current.other()
            }
        }
        return planned(node.baseName, node.logical, sourceSlot, targetSlot)
    }

    /**
     * Plan the target of a TWRP image named [baseName]. TWRP images the slot it booted with and
     * restores to the current slot, so the default (and [SlotChoice.SAME_ROLE] / [SlotChoice.RECORDED])
     * is the active slot; [SlotChoice.INACTIVE] counts as crossing slots.
     */
    fun planTwrp(baseName: String, scan: PartitionScanResult, choice: SlotChoice): SlotPlan {
        val slotted = scan.partitions.any {
            it.source == PartitionSource.BY_NAME && it.slot != PartitionSlot.NONE && it.baseName.equals(baseName, ignoreCase = true)
        }
        if (!slotted) {
            val target = PartitionTarget(name = baseName, blockDevicePath = null, slotPolicy = SlotPolicy.NONE, logical = false)
            return SlotPlan.Planned(target, PartitionSlot.NONE, PartitionSlot.NONE, crossesSlot = false)
        }
        val current = scan.activeSlot ?: return SlotPlan.Failed(SlotPlanFailure.ACTIVE_SLOT_UNKNOWN)
        return when (choice) {
            SlotChoice.INACTIVE -> planned(baseName, false, current, current.other())
            SlotChoice.ACTIVE, SlotChoice.SAME_ROLE, SlotChoice.RECORDED -> planned(baseName, false, current, current)
        }
    }

    private fun planned(baseName: String, logical: Boolean, sourceSlot: PartitionSlot, targetSlot: PartitionSlot): SlotPlan.Planned {
        // The exact node name pins one slot in PartitionViews.resolve; the policy is informational.
        val target = PartitionTarget(name = baseName + targetSlot.suffix, blockDevicePath = null, slotPolicy = SlotPolicy.NONE, logical = logical)
        return SlotPlan.Planned(target, sourceSlot, targetSlot, crossesSlot = targetSlot != sourceSlot)
    }
}
