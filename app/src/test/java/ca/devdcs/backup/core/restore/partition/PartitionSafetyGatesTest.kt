package ca.devdcs.backup.core.restore.partition

import ca.devdcs.backup.core.discovery.partition.PartitionRisk
import ca.devdcs.backup.core.discovery.partition.PartitionSlot
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.restore.engine.RestoreOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionSafetyGatesTest {

    private val F = RestorePartitionFixtures
    private val boot = F.node("boot_a", "/dev/block/sda12")
    private val sameDevice = ImageOrigin.Manifest(F.PIXEL, F.PIXEL)
    private val everything = PartitionRestoreOptions(
        allowCrossSlot = true,
        allowOtherDevice = true,
        allowOtherBuild = true,
        allowSmallerImage = true,
        allowCriticalIdentity = true,
    )

    private fun evaluate(
        partition: ca.devdcs.backup.core.discovery.partition.DiscoveredPartition = boot,
        state: BlockDeviceState = F.idleState(partition.realDevice!!),
        origin: ImageOrigin = sameDevice,
        crossesSlot: Boolean = false,
        image: Long? = 67_108_864L,
        options: PartitionRestoreOptions = PartitionRestoreOptions(),
    ): GateReport = PartitionSafetyGates.evaluate(GateFacts(partition, state, origin, crossesSlot, image), options)

    private fun GateReport.refused(): Set<PartitionGate> = refusals.map { it.gate }.toSet()

    @Test
    fun idleSameDeviceExactSizeIsAllowed() {
        val report = evaluate()
        assertTrue(report.allowed)
        assertTrue(report.overridden.isEmpty())
    }

    @Test
    fun otherHardwareNeedsOverride() {
        val other = F.PIXEL.copy(model = "Pixel 9", device = "tokay")
        assertEquals(setOf(PartitionGate.DEVICE_MISMATCH), evaluate(origin = ImageOrigin.Manifest(other, F.PIXEL)).refused())
        val overridden = evaluate(origin = ImageOrigin.Manifest(other, F.PIXEL), options = PartitionRestoreOptions(allowOtherDevice = true))
        assertTrue(overridden.allowed)
        assertEquals(PartitionGate.DEVICE_MISMATCH, overridden.overridden.single().gate)
    }

    @Test
    fun otherBuildIsASeparateGate() {
        val older = F.PIXEL.copy(fingerprint = "google/shiba/shiba:14/UQ1A/1:user/release-keys")
        val origin = ImageOrigin.Manifest(older, F.PIXEL)
        assertEquals(setOf(PartitionGate.BUILD_MISMATCH), evaluate(origin = origin).refused())
        assertTrue(evaluate(origin = origin, options = PartitionRestoreOptions(allowOtherBuild = true)).allowed)
        assertFalse(evaluate(origin = origin, options = PartitionRestoreOptions(allowOtherDevice = true)).allowed)
    }

    @Test
    fun unknownIdentityNeverMatches() {
        val blank = F.PIXEL.copy(model = "", device = "")
        assertEquals(setOf(PartitionGate.DEVICE_MISMATCH), evaluate(origin = ImageOrigin.Manifest(blank, F.PIXEL)).refused())
    }

    @Test
    fun twrpSerialGate() {
        assertTrue(evaluate(origin = ImageOrigin.Twrp("ABC123", "ABC123")).allowed)
        assertEquals(setOf(PartitionGate.DEVICE_MISMATCH), evaluate(origin = ImageOrigin.Twrp("ABC123", "XYZ")).refused())
        assertEquals(setOf(PartitionGate.DEVICE_MISMATCH), evaluate(origin = ImageOrigin.Twrp("ABC123", null)).refused())
    }

    @Test
    fun crossSlotNeedsConfirmation() {
        assertEquals(setOf(PartitionGate.CROSS_SLOT), evaluate(crossesSlot = true).refused())
        assertTrue(evaluate(crossesSlot = true, options = PartitionRestoreOptions(allowCrossSlot = true)).allowed)
    }

    @Test
    fun sizeRules() {
        assertEquals(setOf(PartitionGate.IMAGE_LARGER), evaluate(image = 67_108_865L).refused())
        // Larger can never be overridden.
        val all = everything
        assertEquals(setOf(PartitionGate.IMAGE_LARGER), evaluate(image = 67_108_865L, options = all).refused())
        assertEquals(setOf(PartitionGate.IMAGE_SMALLER), evaluate(image = 1024L).refused())
        assertTrue(evaluate(image = 1024L, options = PartitionRestoreOptions(allowSmallerImage = true)).allowed)
        assertEquals(setOf(PartitionGate.TARGET_SIZE_UNKNOWN), evaluate(state = F.idleState("/dev/block/sda12", size = null)).refused())
        // Image size not yet known: only the target-side checks apply.
        assertTrue(evaluate(image = null).allowed)
    }

    @Test
    fun criticalIdentityNeedsOverride() {
        val persist = F.node("persist", "/dev/block/sda4", risk = PartitionRisk.CRITICAL_IDENTITY)
        assertEquals(setOf(PartitionGate.CRITICAL_IDENTITY), evaluate(partition = persist).refused())
        assertTrue(evaluate(partition = persist, options = PartitionRestoreOptions(allowCriticalIdentity = true)).allowed)
    }

    @Test
    fun livePartitionsAreRefusedOutright() {
        val all = everything
        for (name in listOf("userdata", "super", "metadata")) {
            assertTrue(name, PartitionGate.LIVE_SYSTEM_PARTITION in evaluate(partition = F.node(name, "/dev/block/sda29"), options = all).refused())
        }
        val activeSystem = F.node("system_a", "/dev/block/dm-1", PartitionSlot.A, PartitionSource.MAPPER)
        assertTrue(PartitionGate.LIVE_SYSTEM_PARTITION in evaluate(partition = activeSystem, options = all).refused())
        val legacySystem = F.node("system", "/dev/block/mmcblk0p30", null)
        assertTrue(PartitionGate.LIVE_SYSTEM_PARTITION in evaluate(partition = legacySystem, options = all).refused())
        // The inactive slot's system is not live.
        val inactiveSystem = F.node("system_b", "/dev/block/dm-2", PartitionSlot.A, PartitionSource.MAPPER)
        assertTrue(evaluate(partition = inactiveSystem).allowed)
    }

    @Test
    fun inUseRefusals() {
        val mounted = F.idleState("/dev/block/sda12").copy(mountPoints = listOf("/mnt/x"))
        assertEquals(setOf(PartitionGate.MOUNTED), evaluate(state = mounted).refused())
        val held = F.idleState("/dev/block/sda12").copy(holders = listOf(BlockHolder("dm-7", "boot-verity")))
        assertEquals(setOf(PartitionGate.HELD_BY_DEVICE_MAPPER), evaluate(state = held).refused())
        val swap = F.idleState("/dev/block/sda12").copy(usedAsSwap = true)
        assertEquals(setOf(PartitionGate.SWAP), evaluate(state = swap).refused())
        val notBlock = F.idleState("/dev/block/sda12").copy(isBlockDevice = false)
        assertEquals(setOf(PartitionGate.NOT_BLOCK_DEVICE), evaluate(state = notBlock).refused())
    }

    @Test
    fun readOnlyOnlyBlocksPhysicalNodes() {
        val ro = F.idleState("/dev/block/sda12").copy(readOnly = true)
        assertEquals(setOf(PartitionGate.READ_ONLY), evaluate(state = ro).refused())
        val logical = F.node("vendor_b", "/dev/block/dm-3", PartitionSlot.A, PartitionSource.MAPPER)
        assertTrue(evaluate(partition = logical, state = F.idleState("/dev/block/dm-3").copy(readOnly = true)).allowed)
    }

    @Test
    fun snapshotHelperNodesAreRefused() {
        val cow = F.node("system_b-cow", "/dev/block/dm-9", PartitionSlot.A, PartitionSource.MAPPER)
        assertTrue(PartitionGate.SNAPSHOT_DEVICE in evaluate(partition = cow).refused())
    }

    @Test
    fun sharedRestoreOptionsMapConservatively() {
        val safe = PartitionRestoreOptions.from(RestoreOptions())
        assertEquals(PartitionRestoreOptions(), safe)
        val cross = PartitionRestoreOptions.from(RestoreOptions(allowCrossDevice = true))
        assertTrue(cross.allowOtherDevice && cross.allowOtherBuild && cross.allowCrossSlot)
        assertFalse(cross.allowSmallerImage || cross.allowCriticalIdentity)
    }
}
