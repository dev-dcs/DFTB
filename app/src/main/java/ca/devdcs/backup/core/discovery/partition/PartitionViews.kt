package ca.devdcs.backup.core.discovery.partition

import ca.devdcs.backup.domain.model.PartitionTarget
import ca.devdcs.backup.domain.model.SlotPolicy

/** The two ways the partition picker presents a scan. */
enum class PartitionView {
    /** Curated, fastboot-style set most owners actually want (boot chain, modem, identity). */
    STANDARD,

    /** Every by-name entry plus device-mapper logical partitions, each flagged by risk. */
    ADVANCED,
}

/**
 * All slots of one logical partition name, so the UI shows `boot` once with its A/B copies
 * rather than `boot_a` and `boot_b` as unrelated rows.
 */
data class PartitionGroup(
    val baseName: String,
    /** Members ordered A, B, then un-slotted. */
    val members: List<DiscoveredPartition>,
    /** True when this name belongs to the curated STANDARD set. */
    val inStandardSet: Boolean,
) {
    val isSlotted: Boolean get() = members.any { it.slot != PartitionSlot.NONE }
    val isLogical: Boolean get() = members.any { it.isLogical }
    val category: PartitionCategory get() = members.first().category
    val risk: PartitionRisk get() = members.maxOf { it.risk }
    val source: PartitionSource get() = members.first().source

    /** Total bytes across members with a known size, or null when no member has one. */
    val totalSizeBytes: Long? get() = members.mapNotNull { it.sizeBytes }.takeIf { it.isNotEmpty() }?.sum()

    /**
     * The persisted selection for this group. Slotted groups store the base name and let the
     * resolver pick slots at run time (the active slot flips after every OTA); un-slotted groups
     * also capture their node path. [PartitionTarget.logical] records whether this is the mapper
     * or the by-name family so a same-named node of the other family is never substituted.
     */
    fun toTarget(slotPolicy: SlotPolicy): PartitionTarget {
        val logical = source == PartitionSource.MAPPER
        return if (isSlotted) {
            PartitionTarget(name = baseName, blockDevicePath = null, slotPolicy = slotPolicy, logical = logical)
        } else {
            PartitionTarget(name = members.first().name, blockDevicePath = members.first().blockDevice, slotPolicy = SlotPolicy.NONE, logical = logical)
        }
    }
}

/** A target resolved to one concrete partition node. */
data class ResolvedPartition(
    val target: PartitionTarget,
    val partition: DiscoveredPartition,
) {
    /** Canonical block device to read (falls back to the by-name link). */
    val devicePath: String get() = partition.devicePath
}

/** Why a target could not be resolved against the scan. */
enum class PartitionResolutionFailure {
    /** No partition with that name (or captured path) exists on this device. */
    NOT_FOUND,

    /** The ACTIVE/INACTIVE policy needs the boot slot, which the device did not report. */
    ACTIVE_SLOT_UNKNOWN,

    /** The partition is slotted but the requested slot's copy is absent (e.g. unmapped logical). */
    SLOT_MISSING,
}

data class UnresolvedPartition(
    val target: PartitionTarget,
    val failure: PartitionResolutionFailure,
)

/** Outcome of [PartitionViews.resolve]: every target lands in exactly one of the two lists. */
data class PartitionResolution(
    val resolved: List<ResolvedPartition>,
    val unresolved: List<UnresolvedPartition>,
) {
    val isComplete: Boolean get() = unresolved.isEmpty()
}

/**
 * Pure view and resolution logic over a [PartitionScanResult]: builds the STANDARD and ADVANCED
 * pickers and turns persisted [PartitionTarget]s back into real block devices for a slot policy.
 * Kept separate from the scanner so engines and tests can use it with any scan result.
 */
object PartitionViews {

    /**
     * Exact (lower-case) base names in the curated STANDARD set besides identity data. Every
     * [PartitionCategory.PERSIST_IDENTITY] partition is also STANDARD (see [isStandard]), so the
     * classifier stays the single source of truth for identity names.
     */
    val STANDARD_NAMES: Set<String> = setOf(
        "boot", "init_boot", "vendor_boot", "vendor_kernel_boot", "dtbo", "recovery",
        "modem", "radio", "md1img",
    )

    /** Base-name prefixes in the STANDARD set (vbmeta, vbmeta_system, vbmeta_vendor...). */
    private val STANDARD_PREFIXES = listOf("vbmeta")

    /** Only shown in STANDARD when the caller explicitly asks (it is many GiB). */
    const val SUPER = "super"

    fun isStandard(baseName: String, includeSuper: Boolean): Boolean {
        val n = baseName.lowercase()
        return n in STANDARD_NAMES ||
            STANDARD_PREFIXES.any(n::startsWith) ||
            PartitionClassifier.category(n) == PartitionCategory.PERSIST_IDENTITY ||
            (includeSuper && n == SUPER)
    }

    /** All partitions grouped by base name (by-name and mapper kept apart), sorted by category then name. */
    fun allGroups(result: PartitionScanResult, includeSuperInStandard: Boolean = false): List<PartitionGroup> =
        result.partitions
            .groupBy { it.source to it.baseName.lowercase() }
            .map { (_, members) ->
                val sorted = members.sortedBy { slotOrder(it.slot) }
                PartitionGroup(
                    baseName = sorted.first().baseName,
                    members = sorted,
                    inStandardSet = sorted.first().source == PartitionSource.BY_NAME &&
                        isStandard(sorted.first().baseName, includeSuperInStandard),
                )
            }
            .sortedWith(compareBy<PartitionGroup>({ it.source.ordinal }, { it.category.ordinal }, { it.baseName.lowercase() }))

    /** Groups for [view]. STANDARD hides `super` unless [includeSuper]. */
    fun groups(result: PartitionScanResult, view: PartitionView, includeSuper: Boolean = false): List<PartitionGroup> {
        val all = allGroups(result, includeSuper)
        return when (view) {
            PartitionView.STANDARD -> all.filter { it.inStandardSet }
            PartitionView.ADVANCED -> all
        }
    }

    /** The STANDARD set as persisted targets with [slotPolicy] applied to slotted partitions. */
    fun standardTargets(result: PartitionScanResult, slotPolicy: SlotPolicy, includeSuper: Boolean = false): List<PartitionTarget> =
        groups(result, PartitionView.STANDARD, includeSuper).map { it.toTarget(effectivePolicy(slotPolicy, result)) }

    /**
     * Resolve [targets] into concrete partitions. [overridePolicy], when set, replaces every
     * target's own policy (e.g. a one-off "image the inactive slot" run).
     *
     * Rules: a target naming an exact slotted node (`boot_b`) resolves to that node only; a base
     * name resolves per policy: ACTIVE / INACTIVE need the boot slot, BOTH yields A then B, and
     * NONE on a slotted name is treated as ACTIVE (it fails with ACTIVE_SLOT_UNKNOWN rather than
     * guessing). Un-slotted partitions resolve to their single copy under any policy.
     * Node family: when [PartitionTarget.logical] is set only that family (mapper or by-name) is
     * considered, so a dm `userdata` target can never resolve to the raw physical partition or vice
     * versa; when null (legacy) by-name nodes win. An un-slotted target whose captured
     * [PartitionTarget.blockDevicePath] matches a node's link path resolves to that node first; a
     * match on the canonical device is the fallback when the name is gone. Duplicate devices are
     * emitted once.
     */
    fun resolve(targets: List<PartitionTarget>, result: PartitionScanResult, overridePolicy: SlotPolicy? = null): PartitionResolution {
        val resolved = ArrayList<ResolvedPartition>()
        val unresolved = ArrayList<UnresolvedPartition>()
        val seen = HashSet<String>()

        fun emit(target: PartitionTarget, partition: DiscoveredPartition) {
            if (seen.add(partition.devicePath)) resolved += ResolvedPartition(target, partition)
        }

        for (target in targets) {
            val policy = overridePolicy ?: target.slotPolicy
            val key = target.name.lowercase()
            val family = when (target.logical) {
                true -> PartitionSource.MAPPER
                false -> PartitionSource.BY_NAME
                null -> null
            }
            val pool = if (family == null) result.partitions else result.partitions.filter { it.source == family }

            // 1. Captured link path: exact node, as long as it is not a different slot of a base name.
            val byLink = target.blockDevicePath?.let { path -> preferByName(pool.filter { it.blockDevice == path }) }
            if (byLink != null && (byLink.slot == PartitionSlot.NONE || byLink.name.equals(target.name, ignoreCase = true))) {
                emit(target, byLink)
                continue
            }

            // 2. Exact slotted node name (`boot_b`) pins that slot.
            val exact = preferByName(pool.filter { it.name.lowercase() == key })
            if (exact != null && exact.slot != PartitionSlot.NONE) {
                emit(target, exact)
                continue
            }

            // 3. Base name, per slot policy.
            val candidates = pool.filter { it.baseName.lowercase() == key }.let { group ->
                if (family != null) group else group.filter { it.source == PartitionSource.BY_NAME }.ifEmpty { group }
            }
            val slotted = candidates.filter { it.slot != PartitionSlot.NONE }
            val single = candidates.firstOrNull { it.slot == PartitionSlot.NONE }

            when {
                slotted.isNotEmpty() -> resolveSlotted(target, policy, slotted, result.activeSlot, ::emit, unresolved)
                single != null -> emit(target, single)
                else -> {
                    // 4. Name gone (renamed/relinked): fall back to the captured canonical device.
                    val byPath = target.blockDevicePath?.let { path -> preferByName(pool.filter { it.realDevice == path }) }
                    if (byPath != null) emit(target, byPath) else unresolved += UnresolvedPartition(target, PartitionResolutionFailure.NOT_FOUND)
                }
            }
        }
        return PartitionResolution(resolved, unresolved)
    }

    private fun resolveSlotted(
        target: PartitionTarget,
        policy: SlotPolicy,
        slotted: List<DiscoveredPartition>,
        activeSlot: PartitionSlot?,
        emit: (PartitionTarget, DiscoveredPartition) -> Unit,
        unresolved: MutableList<UnresolvedPartition>,
    ) {
        fun pick(slot: PartitionSlot) {
            val match = slotted.firstOrNull { it.slot == slot }
            if (match != null) emit(target, match) else unresolved += UnresolvedPartition(target, PartitionResolutionFailure.SLOT_MISSING)
        }
        when (policy) {
            SlotPolicy.BOTH -> {
                pick(PartitionSlot.A)
                pick(PartitionSlot.B)
            }
            SlotPolicy.ACTIVE, SlotPolicy.NONE ->
                if (activeSlot == null) unresolved += UnresolvedPartition(target, PartitionResolutionFailure.ACTIVE_SLOT_UNKNOWN) else pick(activeSlot)
            SlotPolicy.INACTIVE ->
                if (activeSlot == null) unresolved += UnresolvedPartition(target, PartitionResolutionFailure.ACTIVE_SLOT_UNKNOWN) else pick(activeSlot.other())
        }
    }

    /** NONE is meaningless on an A/B device; treat it as ACTIVE there, and anything as NONE on non-A/B. */
    private fun effectivePolicy(policy: SlotPolicy, result: PartitionScanResult): SlotPolicy = when {
        !result.abDevice -> SlotPolicy.NONE
        policy == SlotPolicy.NONE -> SlotPolicy.ACTIVE
        else -> policy
    }

    private fun preferByName(candidates: List<DiscoveredPartition>): DiscoveredPartition? =
        candidates.firstOrNull { it.source == PartitionSource.BY_NAME } ?: candidates.firstOrNull()

    private fun slotOrder(slot: PartitionSlot): Int = when (slot) {
        PartitionSlot.A -> 0
        PartitionSlot.B -> 1
        PartitionSlot.NONE -> 2
    }
}
