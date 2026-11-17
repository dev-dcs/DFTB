package ca.devdcs.backup.core.discovery.partition

import ca.devdcs.backup.core.discovery.partition.PartitionFixtures.dir
import ca.devdcs.backup.core.discovery.partition.PartitionFixtures.link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionAssemblerTest {

    private fun scan(stdout: List<String>, stderr: List<String> = emptyList(), exit: Int = 0): PartitionScanResult {
        val outcome = PartitionAssembler.assemble(PartitionScanScript.parseSections(stdout), stderr, exit)
        assertTrue("expected a result, got $outcome", outcome is PartitionScanResult)
        return outcome as PartitionScanResult
    }

    private fun PartitionScanResult.byName(name: String, source: PartitionSource = PartitionSource.BY_NAME): DiscoveredPartition =
        partitions.single { it.name == name && it.source == source }

    @Test
    fun `pixel 8 layout`() {
        val r = scan(PartitionFixtures.pixel8)
        assertTrue(r.abDevice)
        assertEquals(PartitionSlot.A, r.activeSlot)
        assertTrue(r.dynamicPartitions)
        assertTrue(r.virtualAb)
        assertTrue("unexpected warnings ${r.warnings}", r.warnings.isEmpty())

        // Two by-name roots list the same 30 names: de-duplicated, /dev/block/by-name wins.
        val physical = r.partitions.filter { it.source == PartitionSource.BY_NAME }
        assertEquals(30, physical.size)
        assertTrue(physical.all { it.blockDevice.startsWith("/dev/block/by-name/") })

        val bootA = r.byName("boot_a")
        assertEquals("boot", bootA.baseName)
        assertEquals(PartitionSlot.A, bootA.slot)
        assertEquals("/dev/block/sda12", bootA.realDevice)
        assertEquals(131072L * 512, bootA.sizeBytes)
        assertTrue(bootA.isActiveSlot)
        assertEquals(PartitionCategory.BOOT_CHAIN, bootA.category)
        assertEquals(PartitionRisk.SAFE_TO_IMAGE, bootA.risk)
        assertFalse(r.byName("boot_b").isActiveSlot)

        assertEquals(PartitionCategory.MODEM, r.byName("modem_a").category)
        listOf("efs", "efs_backup", "persist", "modem_userdata", "frp", "devinfo").forEach {
            assertEquals(it, PartitionRisk.CRITICAL_IDENTITY, r.byName(it).risk)
        }
        val superP = r.byName("super")
        assertTrue(superP.isLogical)
        assertEquals(PartitionRisk.HUGE, superP.risk)
        assertEquals(PartitionCategory.SYSTEM_LOGICAL, superP.category)
        assertEquals(PartitionRisk.HUGE, r.byName("userdata").risk)
        assertFalse(r.byName("userdata").isLogical)

        // Mapper logical partitions: size from /proc/partitions since sysfs had none for dm-*.
        val system = r.byName("system_a", PartitionSource.MAPPER)
        assertTrue(system.isLogical)
        assertEquals(PartitionSlot.A, system.slot)
        assertEquals("/dev/block/dm-0", system.realDevice)
        assertEquals(2442776L * 1024, system.sizeBytes)
        assertEquals(PartitionCategory.SYSTEM_LOGICAL, system.category)
        val dmUserdata = r.byName("userdata", PartitionSource.MAPPER)
        assertEquals(PartitionCategory.USERDATA, dmUserdata.category)
        assertEquals(7, r.partitions.count { it.source == PartitionSource.MAPPER })
    }

    @Test
    fun `samsung emmc layout without readlink uses ls targets`() {
        val r = scan(PartitionFixtures.samsungEmmc)
        assertFalse(r.abDevice)
        assertNull(r.activeSlot)
        assertFalse(r.dynamicPartitions)
        assertTrue("unexpected warnings ${r.warnings}", r.warnings.isEmpty())

        val boot = r.byName("BOOT")
        assertEquals("BOOT", boot.baseName)
        assertEquals(PartitionSlot.NONE, boot.slot)
        assertTrue(boot.isActiveSlot)
        assertEquals("/dev/block/platform/13540000.dwmmc0/by-name/BOOT", boot.blockDevice)
        assertEquals("/dev/block/mmcblk0p10", boot.realDevice)
        assertEquals(36864L * 1024, boot.sizeBytes)
        assertEquals(PartitionCategory.BOOT_CHAIN, boot.category)

        assertEquals(PartitionRisk.CRITICAL_IDENTITY, r.byName("EFS").risk)
        assertEquals(PartitionRisk.CRITICAL_IDENTITY, r.byName("PERSISTENT").risk)
        assertEquals(PartitionCategory.MODEM, r.byName("RADIO").category)
        assertEquals(PartitionRisk.HUGE, r.byName("USERDATA").risk)
        val system = r.byName("SYSTEM")
        assertEquals(PartitionCategory.SYSTEM_LOGICAL, system.category)
        assertFalse(system.isLogical)
        assertEquals(PartitionRisk.SAFE_TO_IMAGE, system.risk)
    }

    @Test
    fun `mediatek layout with slot b active and dmctl-only logical names`() {
        val r = scan(PartitionFixtures.mediatek)
        assertEquals(PartitionSlot.B, r.activeSlot)
        assertTrue(r.byName("boot_b").isActiveSlot)
        assertFalse(r.byName("boot_a").isActiveSlot)
        assertEquals(PartitionCategory.MODEM, r.byName("md1img_a").category)
        assertEquals(PartitionCategory.BOOT_CHAIN, r.byName("preloader_raw_a").category)
        assertEquals("preloader_raw", r.byName("preloader_raw_a").baseName)
        listOf("nvcfg", "nvdata", "nvram", "protect1", "protect2", "persist", "proinfo", "seccfg").forEach {
            assertEquals(it, PartitionRisk.CRITICAL_IDENTITY, r.byName(it).risk)
        }
        assertEquals(PartitionCategory.OTHER, r.byName("expdb").category)

        val system = r.byName("system_b", PartitionSource.MAPPER)
        assertEquals("/dev/block/dm-0", system.blockDevice)
        assertEquals(1937408L * 1024, system.sizeBytes)
        assertTrue(system.isActiveSlot)
        assertEquals(3, r.partitions.count { it.source == PartitionSource.MAPPER })
    }

    @Test
    fun `xiaomi layout dedupes three roots and reports missing dm sizes`() {
        val r = scan(PartitionFixtures.xiaomi)
        assertEquals(35, r.partitions.count { it.source == PartitionSource.BY_NAME })
        listOf("modemst1", "modemst2", "fsg", "fsc", "persist", "persistbak").forEach {
            assertEquals(it, PartitionRisk.CRITICAL_IDENTITY, r.byName(it).risk)
        }
        assertEquals(PartitionCategory.FIRMWARE, r.byName("bluetooth_a").category)
        assertEquals(PartitionCategory.FIRMWARE, r.byName("dsp_b").category)
        assertEquals(PartitionCategory.BOOT_CHAIN, r.byName("xbl_config_a").category)
        assertEquals(PartitionRisk.HUGE, r.byName("userdata").risk)

        val mi = r.byName("mi_ext_a", PartitionSource.MAPPER)
        assertEquals(PartitionCategory.SYSTEM_LOGICAL, mi.category)
        assertNull(mi.sizeBytes)
        val sizes = r.warnings.single { it.kind == PartitionScanWarningKind.SIZES_INCOMPLETE }
        assertEquals("mi_ext_a, system_a, vendor_a", sizes.detail)
    }

    @Test
    fun `conflicting by-name roots are flagged and by-name wins`() {
        val r = scan(
            PartitionFixtures.stdout(
                props = listOf("ro.boot.slot_suffix=_a"),
                byName = listOf(
                    dir("/dev/block/platform/soc/by-name"), link("/dev/block/platform/soc/by-name/boot_a", "/dev/block/sdz1"),
                    dir("/dev/block/by-name"), link("/dev/block/by-name/boot_a", "/dev/block/sda12"),
                ),
            ),
        )
        assertEquals("/dev/block/sda12", r.byName("boot_a").realDevice)
        assertEquals("boot_a", r.warnings.single { it.kind == PartitionScanWarningKind.BY_NAME_CONFLICT }.detail)
    }

    @Test
    fun `suffix is not a slot on a non-AB device without a counterpart`() {
        val r = scan(
            PartitionFixtures.stdout(
                byName = listOf(link("/dev/block/by-name/ftm_a", "/dev/block/mmcblk0p9"), link("/dev/block/by-name/lk_a", "/dev/block/mmcblk0p4"), link("/dev/block/by-name/lk_b", "/dev/block/mmcblk0p5")),
            ),
        )
        assertEquals(PartitionSlot.NONE, r.byName("ftm_a").slot)
        assertEquals("ftm_a", r.byName("ftm_a").baseName)
        assertEquals(PartitionSlot.A, r.byName("lk_a").slot)
    }

    @Test
    fun `ab device without slot suffix warns`() {
        val r = scan(
            PartitionFixtures.stdout(
                props = listOf("ro.build.ab_update=true", "ro.boot.slot_suffix="),
                byName = listOf(link("/dev/block/by-name/boot_a", "/dev/block/sda1"), link("/dev/block/by-name/boot_b", "/dev/block/sda2")),
            ),
        )
        assertNull(r.activeSlot)
        assertFalse(r.byName("boot_a").isActiveSlot)
        assertNotNull(r.warnings.singleOrNull { it.kind == PartitionScanWarningKind.ACTIVE_SLOT_UNKNOWN })
    }

    @Test
    fun `truncated output and stderr produce a partial warning`() {
        val r = scan(
            PartitionFixtures.stdout(byName = listOf(link("/dev/block/by-name/boot", "/dev/block/sda1")), complete = false),
            stderr = listOf("ls: /dev/block/mapper: Permission denied"),
            exit = 1,
        )
        val w = r.warnings.single { it.kind == PartitionScanWarningKind.SCRIPT_PARTIAL }
        val detail = w.detail.orEmpty()
        assertTrue(detail.contains("Permission denied"))
        assertTrue(detail.contains("exit 1"))
    }

    @Test
    fun `dynamic device with no mapper names warns`() {
        val r = scan(
            PartitionFixtures.stdout(
                props = listOf("ro.boot.dynamic_partitions=true"),
                byName = listOf(link("/dev/block/by-name/super", "/dev/block/sda7")),
            ),
        )
        assertNotNull(r.warnings.singleOrNull { it.kind == PartitionScanWarningKind.DM_NAMES_UNAVAILABLE })
        assertNotNull(r.warnings.singleOrNull { it.kind == PartitionScanWarningKind.SIZES_INCOMPLETE })
    }

    @Test
    fun `nothing found is unavailable`() {
        val outcome = PartitionAssembler.assemble(PartitionScanScript.parseSections(PartitionFixtures.stdout()))
        assertEquals(PartitionUnavailableReason.NO_PARTITIONS_FOUND, (outcome as PartitionScanUnavailable).reason)
    }
}
