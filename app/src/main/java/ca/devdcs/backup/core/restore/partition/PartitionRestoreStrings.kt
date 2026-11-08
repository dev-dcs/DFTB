package ca.devdcs.backup.core.restore.partition

import android.content.Context
import androidx.annotation.StringRes
import ca.devdcs.backup.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves partition-restore and TWRP-import messages without a UI context. */
@Singleton
class PartitionRestoreStrings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun get(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    /** One user-facing sentence for a gate that fired on [partitionName]. */
    fun gate(hit: GateHit, partitionName: String): String = get(gateMessage(hit.gate), partitionName, hit.detail)

    fun overridden(hit: GateHit, partitionName: String): String = get(R.string.partition_gate_overridden, gate(hit, partitionName))

    @StringRes
    fun gateMessage(gate: PartitionGate): Int = when (gate) {
        PartitionGate.DEVICE_MISMATCH -> R.string.partition_gate_device_mismatch
        PartitionGate.BUILD_MISMATCH -> R.string.partition_gate_build_mismatch
        PartitionGate.CROSS_SLOT -> R.string.partition_gate_cross_slot
        PartitionGate.IMAGE_SMALLER -> R.string.partition_gate_image_smaller
        PartitionGate.CRITICAL_IDENTITY -> R.string.partition_gate_critical_identity
        PartitionGate.IMAGE_LARGER -> R.string.partition_gate_image_larger
        PartitionGate.TARGET_SIZE_UNKNOWN -> R.string.partition_gate_target_size_unknown
        PartitionGate.NOT_BLOCK_DEVICE -> R.string.partition_gate_not_block_device
        PartitionGate.LIVE_SYSTEM_PARTITION -> R.string.partition_gate_live_system
        PartitionGate.MOUNTED -> R.string.partition_gate_mounted
        PartitionGate.SWAP -> R.string.partition_gate_swap
        PartitionGate.HELD_BY_DEVICE_MAPPER -> R.string.partition_gate_held
        PartitionGate.SNAPSHOT_DEVICE -> R.string.partition_gate_snapshot
        PartitionGate.READ_ONLY -> R.string.partition_gate_read_only
    }
}
