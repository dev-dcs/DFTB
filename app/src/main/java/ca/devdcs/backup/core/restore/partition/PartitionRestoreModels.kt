package ca.devdcs.backup.core.restore.partition

import ca.devdcs.backup.core.restore.engine.RestoreOptions

/**
 * Which slot of an A/B device a slotted image is written to. Exists because the active slot flips
 * after every OTA, so "the slot it came from" and "the slot with the same role" differ in practice.
 */
enum class SlotChoice {
    /** Default: the slot that now has the role (active/inactive) the backed-up slot had then. */
    SAME_ROLE,

    /** The slot the device booted from now. */
    ACTIVE,

    /** The slot the device did not boot from. */
    INACTIVE,

    /** The same slot letter that was imaged (`boot_a` back to `boot_a`). */
    RECORDED,
}

/**
 * Explicit, per-run overrides for partition writes. Every flag defaults to the safe value; a gate
 * whose flag is false refuses the write. Exists separately from [RestoreOptions] because these
 * risks are specific to flashing block devices and each needs its own confirmation in the UI.
 */
data class PartitionRestoreOptions(
    val slotChoice: SlotChoice = SlotChoice.SAME_ROLE,
    /** Allow a target slot letter different from the one imaged. */
    val allowCrossSlot: Boolean = false,
    /** Allow an image taken on another device model / codename. */
    val allowOtherDevice: Boolean = false,
    /** Allow an image taken on the same model but another build fingerprint (e.g. before an OTA). */
    val allowOtherBuild: Boolean = false,
    /** Allow an image smaller than the target partition (the tail of the partition is left as is). */
    val allowSmallerImage: Boolean = false,
    /** Allow writing device-unique identity / calibration partitions (IMEI/NV, persist, FRP...). */
    val allowCriticalIdentity: Boolean = false,
) {
    companion object {
        /**
         * The overrides reachable through the shared [RestoreOptions]: `allowCrossDevice` covers
         * device, build and slot ("a different device model or slot"); everything else stays safe.
         */
        fun from(options: RestoreOptions): PartitionRestoreOptions = PartitionRestoreOptions(
            allowCrossSlot = options.allowCrossDevice,
            allowOtherDevice = options.allowCrossDevice,
            allowOtherBuild = options.allowCrossDevice,
        )
    }
}

/**
 * One reason a partition write is refused (or, when overridden, warned about). Hard gates can never
 * be overridden from Android; they need recovery / fastboot.
 */
enum class PartitionGate(val overridable: Boolean) {
    DEVICE_MISMATCH(true),
    BUILD_MISMATCH(true),
    CROSS_SLOT(true),
    IMAGE_SMALLER(true),
    CRITICAL_IDENTITY(true),

    /** The image is larger than the partition: it cannot fit. */
    IMAGE_LARGER(false),

    /** `blockdev --getsize64` failed, so the exact-size check cannot be made. */
    TARGET_SIZE_UNKNOWN(false),

    /** The node is not a block device (stale link, wrong path). */
    NOT_BLOCK_DEVICE(false),

    /** userdata / super / metadata, or a live system partition of the running slot. */
    LIVE_SYSTEM_PARTITION(false),

    /** The node (or a filesystem on it) is mounted. */
    MOUNTED(false),

    /** Used as swap. */
    SWAP(false),

    /** A device-mapper device is stacked on the node (verity, encryption, logical partitions). */
    HELD_BY_DEVICE_MAPPER(false),

    /** A virtual A/B snapshot helper node (`-cow`, `-base`...), never a restore target. */
    SNAPSHOT_DEVICE(false),

    /** A physical node reporting read-only (logical nodes are switched with `blockdev --setrw`). */
    READ_ONLY(false),
}

/** One gate that fired, with a short technical detail (names, sizes) for the message. */
data class GateHit(val gate: PartitionGate, val detail: String)

/** Outcome of the safety gates: write only when [refusals] is empty. */
data class GateReport(
    /** Gates that block the write. */
    val refusals: List<GateHit>,
    /** Overridable gates the caller explicitly overrode; shown as warnings. */
    val overridden: List<GateHit>,
) {
    val allowed: Boolean get() = refusals.isEmpty()
}
