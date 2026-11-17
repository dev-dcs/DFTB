package ca.devdcs.backup.core.discovery.partition

import ca.devdcs.backup.domain.model.PartitionTarget
import ca.devdcs.backup.domain.model.SlotPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionViewsTest {

    private fun result(stdout: List<String>): PartitionScanResult =
        PartitionAssembler.assemble(PartitionScanScript.parseSections(stdout)) as PartitionScanResult

    private val pixel = result(PartitionFixtures.pixel8)
    private val samsung = result(PartitionFixtures.samsungEmmc)
    private val mtk = result(PartitionFixtures.mediatek)

    @Test
    fun `standard view on pixel is the curated fastboot set without super`() {
        val names = PartitionViews.groups(pixel, PartitionView.STANDARD).map { it.baseName }.toSet()
        assertEquals(
            setOf(
                "boot", "init_boot", "vendor_boot", "dtbo", "vbmeta", "vbmeta_system", "vbmeta_vendor",
                "modem", "persist", "efs", "efs_backup",
                // Identity members come from the classifier, not a hand list.
                "modem_userdata", "frp", "devinfo",
            ),
            names,
        )
        val withSuper = PartitionViews.groups(pixel, PartitionView.STANDARD, includeSuper = true).map { it.baseName }
        assertTrue("super" in withSuper)
    }

    @Test
    fun `advanced view lists every by-name group and mapper partitions`() {
        val groups = PartitionViews.groups(pixel, PartitionView.ADVANCED)
        // 30 by-name nodes collapse to 20 groups (10 slotted pairs + 10 single); 7 mapper groups.
        assertEquals(20, groups.count { it.source == PartitionSource.BY_NAME })
        assertEquals(7, groups.count { it.source == PartitionSource.MAPPER })
        val boot = groups.single { it.baseName == "boot" }
        assertEquals(listOf(PartitionSlot.A, PartitionSlot.B), boot.members.map { it.slot })
        assertEquals(2 * 131072L * 512, boot.totalSizeBytes)
        assertTrue(groups.single { it.baseName == "system" }.isLogical)
        assertFalse(groups.single { it.baseName == "system" }.inStandardSet)
        assertEquals(PartitionRisk.CRITICAL_IDENTITY, groups.single { it.baseName == "efs" }.risk)
    }

    @Test
    fun `standard view on samsung matches uppercase names`() {
        val names = PartitionViews.groups(samsung, PartitionView.STANDARD).map { it.baseName }.toSet()
        assertEquals(setOf("BOOT", "RECOVERY", "RADIO", "EFS", "PERSISTENT"), names)
    }

    @Test
    fun `standard targets carry policy for slotted and path for single`() {
        val targets = PartitionViews.standardTargets(pixel, SlotPolicy.BOTH)
        val boot = targets.single { it.name == "boot" }
        assertEquals(SlotPolicy.BOTH, boot.slotPolicy)
        assertNull(boot.blockDevicePath)
        val efs = targets.single { it.name == "efs" }
        assertEquals(SlotPolicy.NONE, efs.slotPolicy)
        assertEquals("/dev/block/by-name/efs", efs.blockDevicePath)

        // Non-A/B devices never get a slot policy.
        assertTrue(PartitionViews.standardTargets(samsung, SlotPolicy.BOTH).all { it.slotPolicy == SlotPolicy.NONE })
        // NONE on an A/B device means the active slot.
        assertEquals(SlotPolicy.ACTIVE, PartitionViews.standardTargets(pixel, SlotPolicy.NONE).single { it.name == "boot" }.slotPolicy)
    }

    @Test
    fun `resolve follows active, inactive and both`() {
        val targets = listOf(PartitionTarget("boot"), PartitionTarget("persist", slotPolicy = SlotPolicy.NONE))

        val active = PartitionViews.resolve(targets, pixel)
        assertTrue(active.isComplete)
        assertEquals(listOf("/dev/block/sda12", "/dev/block/sda4"), active.resolved.map { it.devicePath })

        val inactive = PartitionViews.resolve(targets, pixel, overridePolicy = SlotPolicy.INACTIVE)
        assertEquals(listOf("/dev/block/sda13", "/dev/block/sda4"), inactive.resolved.map { it.devicePath })

        val both = PartitionViews.resolve(targets, pixel, overridePolicy = SlotPolicy.BOTH)
        assertEquals(listOf("boot_a", "boot_b", "persist"), both.resolved.map { it.partition.name })

        // MediaTek booted from slot b.
        val mtkActive = PartitionViews.resolve(listOf(PartitionTarget("boot")), mtk)
        assertEquals("boot_b", mtkActive.resolved.single().partition.name)
    }

    @Test
    fun `exact slotted name pins that slot regardless of policy`() {
        val r = PartitionViews.resolve(listOf(PartitionTarget("boot_b", slotPolicy = SlotPolicy.ACTIVE)), pixel)
        assertEquals("boot_b", r.resolved.single().partition.name)
    }

    @Test
    fun `by-name wins over mapper for the same name and mapper resolves logical`() {
        val r = PartitionViews.resolve(listOf(PartitionTarget("userdata", slotPolicy = SlotPolicy.NONE), PartitionTarget("system")), pixel)
        assertEquals(PartitionSource.BY_NAME, r.resolved[0].partition.source)
        assertEquals("/dev/block/sda29", r.resolved[0].devicePath)
        assertEquals("/dev/block/dm-0", r.resolved[1].devicePath)
    }

    @Test
    fun `failures are reported, never guessed`() {
        val missing = PartitionViews.resolve(listOf(PartitionTarget("recovery")), pixel)
        assertEquals(PartitionResolutionFailure.NOT_FOUND, missing.unresolved.single().failure)

        // Logical inactive slot is not mapped on a Virtual A/B device.
        val inactiveSystem = PartitionViews.resolve(listOf(PartitionTarget("system", slotPolicy = SlotPolicy.INACTIVE)), pixel)
        assertEquals(PartitionResolutionFailure.SLOT_MISSING, inactiveSystem.unresolved.single().failure)

        val noSlot = result(
            PartitionFixtures.stdout(
                props = listOf("ro.build.ab_update=true"),
                byName = listOf(PartitionFixtures.link("/dev/block/by-name/boot_a", "/dev/block/sda1"), PartitionFixtures.link("/dev/block/by-name/boot_b", "/dev/block/sda2")),
            ),
        )
        val unknown = PartitionViews.resolve(listOf(PartitionTarget("boot")), noSlot)
        assertEquals(PartitionResolutionFailure.ACTIVE_SLOT_UNKNOWN, unknown.unresolved.single().failure)
        // BOTH does not need the active slot.
        assertEquals(2, PartitionViews.resolve(listOf(PartitionTarget("boot", slotPolicy = SlotPolicy.BOTH)), noSlot).resolved.size)
    }

    @Test
    fun `captured block path is a fallback when the name is gone`() {
        val r = PartitionViews.resolve(listOf(PartitionTarget("old_name", blockDevicePath = "/dev/block/sda4")), pixel)
        assertEquals("persist", r.resolved.single().partition.name)
    }

    @Test
    fun `mediatek standard includes every identity partition`() {
        val names = PartitionViews.groups(mtk, PartitionView.STANDARD).map { it.baseName }.toSet()
        assertTrue(names.containsAll(setOf("nvcfg", "nvdata", "nvram", "protect1", "protect2", "persist", "proinfo", "seccfg", "frp", "md1img")))
        assertFalse("expdb" in names)
    }

    @Test
    fun `mapper and by-name groups of the same name never cross`() {
        val groups = PartitionViews.groups(pixel, PartitionView.ADVANCED)
        val physical = groups.single { it.baseName == "userdata" && it.source == PartitionSource.BY_NAME }.toTarget(SlotPolicy.ACTIVE)
        val dm = groups.single { it.baseName == "userdata" && it.source == PartitionSource.MAPPER }.toTarget(SlotPolicy.ACTIVE)
        assertEquals(false, physical.logical)
        assertEquals(true, dm.logical)
        assertTrue(physical != dm)

        val r = PartitionViews.resolve(listOf(physical, dm), pixel)
        assertTrue(r.isComplete)
        assertEquals(listOf("/dev/block/sda29", "/dev/block/dm-6"), r.resolved.map { it.devicePath })

        // A logical target never falls back to a physical node (and vice versa).
        val dmSystem = groups.single { it.baseName == "system" }.toTarget(SlotPolicy.ACTIVE)
        assertEquals("/dev/block/dm-0", PartitionViews.resolve(listOf(dmSystem), pixel).resolved.single().devicePath)
        val physicalSystem = PartitionTarget("system", logical = false)
        assertEquals(PartitionResolutionFailure.NOT_FOUND, PartitionViews.resolve(listOf(physicalSystem), pixel).unresolved.single().failure)
        val dmPersist = PartitionTarget("persist", slotPolicy = SlotPolicy.NONE, logical = true)
        assertEquals(PartitionResolutionFailure.NOT_FOUND, PartitionViews.resolve(listOf(dmPersist), pixel).unresolved.single().failure)
    }

    @Test
    fun `retrofit dynamic device keeps physical and mapper system apart`() {
        val retrofit = result(
            PartitionFixtures.stdout(
                props = listOf("ro.boot.slot_suffix=_a", "ro.boot.dynamic_partitions=true"),
                byName = listOf(
                    PartitionFixtures.link("/dev/block/by-name/system_a", "/dev/block/sda20"),
                    PartitionFixtures.link("/dev/block/by-name/system_b", "/dev/block/sda21"),
                ),
                mapper = listOf(PartitionFixtures.link("/dev/block/mapper/system_a", "/dev/block/dm-0")),
            ),
        )
        val groups = PartitionViews.groups(retrofit, PartitionView.ADVANCED)
        val targets = groups.map { it.toTarget(SlotPolicy.ACTIVE) }
        assertEquals(2, targets.distinct().size)
        val r = PartitionViews.resolve(targets, retrofit)
        assertEquals(setOf("/dev/block/sda20", "/dev/block/dm-0"), r.resolved.map { it.devicePath }.toSet())
    }

    @Test
    fun `captured link path wins over name lookup for un-slotted targets`() {
        val t = PartitionTarget("userdata", blockDevicePath = "/dev/block/mapper/userdata", slotPolicy = SlotPolicy.NONE)
        assertEquals("/dev/block/dm-6", PartitionViews.resolve(listOf(t), pixel).resolved.single().devicePath)
    }

    @Test
    fun `duplicate targets resolve once`() {
        val r = PartitionViews.resolve(listOf(PartitionTarget("boot"), PartitionTarget("boot_a"), PartitionTarget("BOOT")), pixel)
        assertEquals(1, r.resolved.size)
        assertTrue(r.isComplete)
    }
}
