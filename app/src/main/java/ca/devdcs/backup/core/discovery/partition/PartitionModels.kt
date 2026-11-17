package ca.devdcs.backup.core.discovery.partition

/**
 * Which A/B slot a partition belongs to. Kept separate from
 * [ca.devdcs.backup.domain.model.SlotPolicy] because a policy is a *request* (active / inactive /
 * both) while this is a *fact* about one discovered node.
 */
enum class PartitionSlot(val suffix: String) {
    A("_a"),
    B("_b"),

    /** Not slotted: a single shared copy (persist, userdata, misc...) or a non-A/B device. */
    NONE("");

    /** The opposite A/B slot; [NONE] has no counterpart and maps to itself. */
    fun other(): PartitionSlot = when (this) {
        A -> B
        B -> A
        NONE -> NONE
    }

    companion object {
        /** Parse a `ro.boot.slot_suffix` value ("_a", "_b", "a", "b"); anything else is null. */
        fun fromSuffix(value: String?): PartitionSlot? = when (value?.trim()?.lowercase()?.removePrefix("_")) {
            "a" -> A
            "b" -> B
            else -> null
        }
    }
}

/**
 * Broad functional family of a partition, so the UI can group a 150-entry by-name list into
 * something a device owner can reason about and so defaults (what STANDARD shows) are data-driven.
 */
enum class PartitionCategory {
    /** Kernel/ramdisk images, verified-boot metadata and bootloader stages. */
    BOOT_CHAIN,

    /** Co-processor / DSP / connectivity firmware blobs. */
    FIRMWARE,

    /** Baseband (modem/radio) images. */
    MODEM,

    /** User data and its encryption metadata / cache. */
    USERDATA,

    /** The dynamic-partition container (super) and the logical partitions mapped from it. */
    SYSTEM_LOGICAL,

    /** Device-unique calibration and identity data (IMEI/NV, MAC, DRM keys, FRP, lock state). */
    PERSIST_IDENTITY,

    OTHER,
}

/**
 * How careful the user must be with an image of this partition. Declared in ascending severity so
 * a group's overall risk is simply the max ordinal.
 */
enum class PartitionRisk {
    /** Ordinary image; safe to take and (on the same device) to restore. */
    SAFE_TO_IMAGE,

    /** Very large (userdata, super, or above [PartitionClassifier.HUGE_THRESHOLD_BYTES]). */
    HUGE,

    /**
     * Device-unique identity/calibration data, or device-specific boot state (DDR training,
     * bootloader params). Backing it up is precious; restoring it onto any other device (or a
     * corrupted copy) can permanently break radio/IMEI/DRM or stop the device booting.
     */
    CRITICAL_IDENTITY,
}

/** Where a partition was discovered. by-name nodes are physical; mapper nodes are dm logical. */
enum class PartitionSource {
    BY_NAME,
    MAPPER,
}

/**
 * One partition node found on the device. Every field comes from the read-only scan; nothing is
 * guessed. [blockDevice] is the stable symlink (by-name or mapper) the engines should open, and
 * [realDevice] is its canonical kernel node used for size lookup and duplicate detection.
 */
data class DiscoveredPartition(
    /** Full node name as on the device, e.g. `boot_a`, `BOOT`, `system_b`. */
    val name: String,
    /** [name] with the A/B suffix stripped when [slot] is A or B. */
    val baseName: String,
    val slot: PartitionSlot,
    /** Symlink path, e.g. `/dev/block/by-name/boot_a` or `/dev/block/mapper/system_a`. */
    val blockDevice: String,
    /** Canonical node (`/dev/block/sda12`, `/dev/block/dm-3`), or null when it could not be resolved. */
    val realDevice: String?,
    /** Size in bytes from sysfs or /proc/partitions; null when neither reported this node. */
    val sizeBytes: Long?,
    /** True for `super` and every device-mapper (dm-N) node. */
    val isLogical: Boolean,
    val category: PartitionCategory,
    /** True for un-slotted partitions and for the slot the device booted from. */
    val isActiveSlot: Boolean,
    val risk: PartitionRisk,
    val source: PartitionSource,
) {
    /** The path engines should read: the canonical node when known, else the symlink. */
    val devicePath: String get() = realDevice ?: blockDevice
}

/** Non-fatal oddities noticed during a scan; shown to the user, never hidden. */
enum class PartitionScanWarningKind {
    /** No by-name directory existed at any known location. */
    NO_BY_NAME_DIRECTORY,

    /** Two by-name directories listed the same name pointing at different devices. */
    BY_NAME_CONFLICT,

    /** Some partitions had no size in sysfs or /proc/partitions. */
    SIZES_INCOMPLETE,

    /** The device reports A/B but no boot slot suffix, so "active slot" cannot be resolved. */
    ACTIVE_SLOT_UNKNOWN,

    /** Dynamic partitions are enabled but no device-mapper names could be read. */
    DM_NAMES_UNAVAILABLE,

    /** The scan script reported errors or a non-zero exit; results may be partial. */
    SCRIPT_PARTIAL,

    /** Some links could not be canonicalised with `readlink -f`. */
    UNRESOLVED_LINKS,
}

/** A warning plus a short technical detail (partition names, stderr excerpt), not localised. */
data class PartitionScanWarning(
    val kind: PartitionScanWarningKind,
    val detail: String? = null,
)

/** Either a full [PartitionScanResult] or a [PartitionScanUnavailable] explaining why not. */
sealed interface PartitionScanOutcome

/**
 * The device's partition layout as observed by one read-only scan. [partitions] is the ADVANCED
 * (complete) list; [PartitionViews] derives the curated STANDARD view from it.
 */
data class PartitionScanResult(
    val abDevice: Boolean,
    /** Slot the device booted from; null on non-A/B devices or when the bootloader did not say. */
    val activeSlot: PartitionSlot?,
    val dynamicPartitions: Boolean,
    val virtualAb: Boolean,
    val partitions: List<DiscoveredPartition>,
    val warnings: List<PartitionScanWarning>,
) : PartitionScanOutcome

/** Why a partition scan could not run. Ordinary environment limits, never exceptions. */
enum class PartitionUnavailableReason {
    /** The current operating mode is not Root; partitions need a superuser shell. */
    ROOT_REQUIRED,

    /** Root mode is selected but the su shell is not granted / not reachable right now. */
    ROOT_NOT_GRANTED,

    /** The scan script could not be launched or timed out. */
    SCAN_FAILED,

    /** The scan ran but found neither by-name nor mapper partitions. */
    NO_PARTITIONS_FOUND,
}

data class PartitionScanUnavailable(
    val reason: PartitionUnavailableReason,
    /** Technical detail (stderr, tier) for diagnostics; not localised. */
    val detail: String? = null,
) : PartitionScanOutcome
