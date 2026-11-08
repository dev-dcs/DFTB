package ca.devdcs.backup.core.discovery.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionParsersTest {

    @Test
    fun `properties parse in script and getprop formats`() {
        val props = PartitionParsers.parseProperties(
            listOf("ro.boot.slot_suffix=_b", "[ro.build.ab_update]: [true]", "ro.boot.dynamic_partitions=", "garbage", ""),
        )
        assertEquals("_b", props["ro.boot.slot_suffix"])
        assertEquals("true", props["ro.build.ab_update"])
        assertEquals("", props["ro.boot.dynamic_partitions"])
        assertEquals(3, props.size)
    }

    @Test
    fun `slot properties decode A-B, dynamic and virtual AB`() {
        val slot = PartitionParsers.parseSlotProperties(
            mapOf("ro.boot.slot_suffix" to "_a", "ro.build.ab_update" to "true", "ro.boot.dynamic_partitions" to "true", "ro.virtual_ab.enabled" to "false"),
        )
        assertEquals(PartitionSlot.A, slot.activeSlot)
        assertTrue(slot.abDevice)
        assertTrue(slot.dynamicPartitions)
        assertFalse(slot.virtualAb)

        val legacy = PartitionParsers.parseSlotProperties(mapOf("ro.boot.slot_suffix" to ""))
        assertNull(legacy.activeSlot)
        assertFalse(legacy.abDevice)
    }

    @Test
    fun `slot suffix alone marks an AB device`() {
        val slot = PartitionParsers.parseSlotProperties(mapOf("ro.boot.slot_suffix" to "_b"))
        assertTrue(slot.abDevice)
        assertEquals(PartitionSlot.B, slot.activeSlot)
    }

    @Test
    fun `ls -l parses toybox, old toolbox and relative targets`() {
        val links = PartitionParsers.parseLsLong(
            "/dev/block/by-name",
            listOf(
                "total 0",
                "lrwxrwxrwx 1 root root 16 2024-10-01 09:12 boot_a -> /dev/block/sda12",
                "lrwxrwxrwx root     root              2017-03-14 11:02 EFS -> /dev/block/mmcblk0p3",
                "lrwxrwxrwx 1 root root 9 2024-10-01 09:12 misc -> ../sda2",
                "drwxr-xr-x 2 root root 60 2024-10-01 09:12 by-uuid",
            ),
        )
        assertEquals(listOf("boot_a", "EFS", "misc"), links.map { it.name })
        assertEquals("/dev/block/sda12", links[0].realPath)
        assertEquals("/dev/block/mmcblk0p3", links[1].realPath)
        assertEquals("/dev/block/sda2", links[2].realPath)
        assertEquals("/dev/block/by-name/misc", links[2].linkPath)
    }

    @Test
    fun `ls section keeps directories apart`() {
        val links = PartitionParsers.parseLsSection(
            listOf(
                "D\t/dev/block/by-name",
                "lrwxrwxrwx 1 root root 16 2024-10-01 09:12 boot_a -> /dev/block/sda12",
                "D\t/dev/block/platform/soc/by-name",
                "lrwxrwxrwx 1 root root 16 2024-10-01 09:12 boot_a -> /dev/block/sda12",
            ),
        )
        assertEquals(listOf("/dev/block/by-name", "/dev/block/platform/soc/by-name"), links.map { it.directory })
    }

    @Test
    fun `link listing parses tab rows with and without real path`() {
        val links = PartitionParsers.parseLinkListing(
            listOf("D\t/dev/block/by-name", "L\t/dev/block/by-name/boot_a\t/dev/block/sda12", "L\t/dev/block/by-name/frp\t", "noise"),
        )
        assertEquals(2, links.size)
        assertEquals("boot_a", links[0].name)
        assertEquals("/dev/block/sda12", links[0].realPath)
        assertNull(links[1].realPath)
        assertEquals("/dev/block/by-name", links[1].directory)
    }

    @Test
    fun `proc partitions skips header and reads 1K blocks`() {
        val rows = PartitionParsers.parseProcPartitions(
            """
            major minor  #blocks  name

               8        0  241172480 sda
             254        3     866384 dm-3
            """.trimIndent().lines(),
        )
        assertEquals(2, rows.size)
        assertEquals(241172480L * 1024, rows[0].sizeBytes)
        assertEquals(ProcPartition(254, 3, 866384, "dm-3"), rows[1])
    }

    @Test
    fun `sysfs sizes are 512-byte sectors and non-numeric rows are dropped`() {
        val sizes = PartitionParsers.parseSysfsSizes(listOf("sda12\t131072", "dm-0 4885552", "bad\tx"))
        assertEquals(131072L * 512, sizes["sda12"])
        assertEquals(4885552L * 512, sizes["dm-0"])
        assertFalse("bad" in sizes)
    }

    @Test
    fun `dmctl list devices output parses`() {
        val devices = PartitionParsers.parseDmctl(
            listOf("Available Device Mapper Devices:", "system_a             : 254:0", "vendor_dlkm_a        : 254:4"),
        )
        assertEquals(listOf(DmctlDevice("system_a", 254, 0), DmctlDevice("vendor_dlkm_a", 254, 4)), devices)
        assertTrue(PartitionParsers.parseDmctl(listOf("No devices available.")).isEmpty())
    }

    @Test
    fun `split slot only strips a trailing _a or _b`() {
        assertEquals("boot" to PartitionSlot.A, PartitionParsers.splitSlot("boot_a"))
        assertEquals("vbmeta_system" to PartitionSlot.B, PartitionParsers.splitSlot("vbmeta_system_b"))
        assertEquals("BOOT" to PartitionSlot.A, PartitionParsers.splitSlot("BOOT_A"))
        assertEquals("persist" to PartitionSlot.NONE, PartitionParsers.splitSlot("persist"))
        assertEquals("_a" to PartitionSlot.NONE, PartitionParsers.splitSlot("_a"))
        assertEquals("boot_para" to PartitionSlot.NONE, PartitionParsers.splitSlot("boot_para"))
    }

    @Test
    fun `normalize path collapses dot segments`() {
        assertEquals("/dev/block/sda2", PartitionParsers.normalizePath("/dev/block/by-name/../sda2"))
        assertEquals("/dev/block/sda2", PartitionParsers.normalizePath("//dev/./block//sda2"))
    }
}
