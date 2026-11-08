package ca.devdcs.backup.core.restore.partition

import ca.devdcs.backup.core.discovery.partition.PartitionSlot
import ca.devdcs.backup.core.discovery.partition.PartitionViews
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout
import ca.devdcs.backup.domain.model.StructureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotMappingTest {

    private val layout = ArchiveLayout(StructureMode.entries.first())

    @Test
    fun parsesArchivePathsWrittenByTheLayout() {
        assertEquals(ImagedNode("boot_a", false), SlotMapping.parseArchivePath(layout.partitionImage("boot_a", logical = false)))
        assertEquals(ImagedNode("system_b", true), SlotMapping.parseArchivePath(layout.partitionImage("system_b", logical = true)))
        assertEquals(ImagedNode("persist", false), SlotMapping.parseArchivePath("/partitions/persist.img"))
        assertNull(SlotMapping.parseArchivePath("apps/x/base.apk"))
        assertNull(SlotMapping.parseArchivePath("partitions/a/b.img"))
        assertNull(SlotMapping.parseArchivePath("partitions/.img"))
    }

    @Test
    fun splitsSlotSuffix() {
        assertEquals("boot" to PartitionSlot.A, SlotMapping.splitSlot("boot_a"))
        assertEquals("vbmeta_system" to PartitionSlot.B, SlotMapping.splitSlot("vbmeta_system_b"))
        assertEquals("persist" to PartitionSlot.NONE, SlotMapping.splitSlot("persist"))
        assertEquals("_a" to PartitionSlot.NONE, SlotMapping.splitSlot("_a"))
    }

    private fun planned(plan: SlotPlan): SlotPlan.Planned = plan as SlotPlan.Planned

    @Test
    fun sameRoleFollowsTheActiveSlotAfterAnOtaFlip() {
        // Imaged boot_a while A was active; device now boots from B.
        val p = planned(SlotMapping.plan(ImagedNode("boot_a", false), PartitionSlot.A, RestorePartitionFixtures.abScan(PartitionSlot.B), SlotChoice.SAME_ROLE))
        assertEquals("boot_b", p.target.name)
        assertEquals(PartitionSlot.B, p.targetSlot)
        assertTrue(p.crossesSlot)
    }

    @Test
    fun sameRoleWithoutFlipStaysOnTheSameSlot() {
        val p = planned(SlotMapping.plan(ImagedNode("boot_a", false), PartitionSlot.A, RestorePartitionFixtures.abScan(PartitionSlot.A), SlotChoice.SAME_ROLE))
        assertEquals("boot_a", p.target.name)
        assertFalse(p.crossesSlot)
    }

    @Test
    fun sameRoleKeepsInactiveRole() {
        // boot_b imaged while A was active (inactive copy); now B is active -> inactive is A.
        val p = planned(SlotMapping.plan(ImagedNode("boot_b", false), PartitionSlot.A, RestorePartitionFixtures.abScan(PartitionSlot.B), SlotChoice.SAME_ROLE))
        assertEquals("boot_a", p.target.name)
        assertTrue(p.crossesSlot)
    }

    @Test
    fun sameRoleWithUnknownBackupSlotUsesRecordedLetter() {
        val p = planned(SlotMapping.plan(ImagedNode("boot_b", false), null, RestorePartitionFixtures.abScan(PartitionSlot.A), SlotChoice.SAME_ROLE))
        assertEquals("boot_b", p.target.name)
        assertFalse(p.crossesSlot)
    }

    @Test
    fun explicitChoices() {
        val scan = RestorePartitionFixtures.abScan(PartitionSlot.A)
        val node = ImagedNode("boot_a", false)
        assertEquals("boot_a", planned(SlotMapping.plan(node, PartitionSlot.A, scan, SlotChoice.ACTIVE)).target.name)
        val inactive = planned(SlotMapping.plan(node, PartitionSlot.A, scan, SlotChoice.INACTIVE))
        assertEquals("boot_b", inactive.target.name)
        assertTrue(inactive.crossesSlot)
        assertEquals("boot_a", planned(SlotMapping.plan(node, PartitionSlot.A, RestorePartitionFixtures.abScan(PartitionSlot.B), SlotChoice.RECORDED)).target.name)
    }

    @Test
    fun failures() {
        assertEquals(
            SlotPlan.Failed(SlotPlanFailure.DEVICE_NOT_AB),
            SlotMapping.plan(ImagedNode("boot_a", false), PartitionSlot.A, RestorePartitionFixtures.legacyScan(), SlotChoice.SAME_ROLE),
        )
        assertEquals(
            SlotPlan.Failed(SlotPlanFailure.ACTIVE_SLOT_UNKNOWN),
            SlotMapping.plan(ImagedNode("boot_a", false), PartitionSlot.A, RestorePartitionFixtures.abScan(null), SlotChoice.ACTIVE),
        )
    }

    @Test
    fun unslottedImageTargetsTheUnslottedNode() {
        val p = planned(SlotMapping.plan(ImagedNode("persist", false), PartitionSlot.A, RestorePartitionFixtures.abScan(), SlotChoice.INACTIVE))
        assertEquals("persist", p.target.name)
        assertEquals(PartitionSlot.NONE, p.targetSlot)
        assertFalse(p.crossesSlot)
    }

    @Test
    fun plannedTargetsResolveToExactlyOneNodeOfTheSameFamily() {
        val scan = RestorePartitionFixtures.abScan(PartitionSlot.A)
        val logical = planned(SlotMapping.plan(ImagedNode("system_b", true), PartitionSlot.A, scan, SlotChoice.RECORDED))
        val resolved = PartitionViews.resolve(listOf(logical.target), scan).resolved.single()
        assertEquals("/dev/block/dm-2", resolved.devicePath)

        // A by-name image of "system_b" must never land on the mapper node.
        val physical = planned(SlotMapping.plan(ImagedNode("system_b", false), PartitionSlot.A, scan, SlotChoice.RECORDED))
        assertTrue(PartitionViews.resolve(listOf(physical.target), scan).resolved.isEmpty())
    }

    @Test
    fun twrpDefaultsToActiveSlot() {
        val scan = RestorePartitionFixtures.abScan(PartitionSlot.B)
        val p = planned(SlotMapping.planTwrp("boot", scan, SlotChoice.SAME_ROLE))
        assertEquals("boot_b", p.target.name)
        assertFalse(p.crossesSlot)
        val inactive = planned(SlotMapping.planTwrp("boot", scan, SlotChoice.INACTIVE))
        assertEquals("boot_a", inactive.target.name)
        assertTrue(inactive.crossesSlot)
        assertEquals("persist", planned(SlotMapping.planTwrp("persist", scan, SlotChoice.ACTIVE)).target.name)
        assertEquals("boot", planned(SlotMapping.planTwrp("boot", RestorePartitionFixtures.legacyScan(), SlotChoice.ACTIVE)).target.name)
        assertEquals(SlotPlan.Failed(SlotPlanFailure.ACTIVE_SLOT_UNKNOWN), SlotMapping.planTwrp("boot", RestorePartitionFixtures.abScan(null), SlotChoice.ACTIVE))
    }
}
