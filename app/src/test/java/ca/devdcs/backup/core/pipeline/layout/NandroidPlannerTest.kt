package ca.devdcs.backup.core.pipeline.layout

import ca.devdcs.backup.core.discovery.partition.DiscoveredPartition
import ca.devdcs.backup.core.discovery.partition.PartitionCategory
import ca.devdcs.backup.core.discovery.partition.PartitionRisk
import ca.devdcs.backup.core.discovery.partition.PartitionSlot
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.discovery.partition.ResolvedPartition
import ca.devdcs.backup.domain.model.PartitionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NandroidPlannerTest {

    private val time = LocalDateTime.of(2026, 10, 19, 7, 5, 9)

    private fun part(
        name: String,
        slot: PartitionSlot = PartitionSlot.NONE,
        active: Boolean = true,
        source: PartitionSource = PartitionSource.BY_NAME,
        size: Long? = 4096,
    ): ResolvedPartition {
        val base = if (slot == PartitionSlot.NONE) name else name.removeSuffix(slot.suffix)
        val p = DiscoveredPartition(
            name = name, baseName = base, slot = slot,
            blockDevice = "/dev/block/by-name/$name", realDevice = null,
            sizeBytes = size, isLogical = source == PartitionSource.MAPPER, category = PartitionCategory.BOOT_CHAIN,
            isActiveSlot = active, risk = PartitionRisk.SAFE_TO_IMAGE, source = source,
        )
        return ResolvedPartition(PartitionTarget(base), p)
    }

    private fun request(
        images: List<NandroidImageInput> = emptyList(),
        filesystems: List<NandroidFilesystemInput> = emptyList(),
        display: String? = "UP1A.231005.007",
        serial: String = "1A2B3C4D",
        log: String? = null,
    ) = NandroidRequest(serial, display, time, images, filesystems, log)

    @Test
    fun `backup name follows twrp auto naming`() {
        assertEquals("2026-10-19--07-05-09", TwrpConventions.currentDate(time))
        assertEquals("2026-10-19--07-05-09_UP1A231005007", TwrpConventions.backupName(time, "UP1A.231005.007"))
        assertEquals("2026-10-19--07-05-09", TwrpConventions.backupName(time, null))
        assertEquals("2026-10-19--07-05-09", TwrpConventions.backupName(time, ""))
        assertEquals("2026-10-19--07-05-09_lineage_201_user", TwrpConventions.backupName(time, "lineage_20.1 user"))
        // A character outside TWRP's whitelist falls back to the date.
        assertEquals("2026-10-19--07-05-09", TwrpConventions.backupName(time, "build/1"))
        val long = TwrpConventions.backupName(time, "X".repeat(100))
        assertEquals(TwrpConventions.MAX_BACKUP_NAME_LEN, long.length)
        // Truncation leaving a trailing space: stripped, then spaces become '_'.
        assertEquals("2026-10-19--07-05-09_" + "A".repeat(42), TwrpConventions.backupName(time, "A".repeat(42) + " B"))
    }

    @Test
    fun `device id sanitization and file names`() {
        assertEquals("ab-1.2_3", TwrpConventions.deviceId("a b-1.2_3\n!"))
        assertEquals("boot.emmc.win", TwrpConventions.imageFileName("boot"))
        assertEquals("data.f2fs.win", TwrpConventions.tarFileName("data", "f2fs"))
        assertEquals("data.ext4.win000", TwrpConventions.splitFileName("data.ext4.win", 0))
        assertEquals("data.ext4.win012", TwrpConventions.splitFileName("data.ext4.win", 12))
        assertEquals("boot.emmc.win.sha2", TwrpConventions.digestFileName("boot.emmc.win"))
        val hex = "AB".repeat(32)
        val line = TwrpConventions.digestLine(hex, "boot.emmc.win")
        assertEquals("ab".repeat(32) + "  boot.emmc.win\n", line)
        assertEquals(line.length.toLong(), TwrpConventions.digestLineLength("boot.emmc.win"))
    }

    @Test
    fun `images use active base names with digest sidecars in write order`() {
        val plan = NandroidPlanner.plan(request(images = listOf(
            NandroidImageInput(part("boot_a", PartitionSlot.A, active = true), 100),
            NandroidImageInput(part("boot_b", PartitionSlot.B, active = false), 100),
            NandroidImageInput(part("system_a", PartitionSlot.A, source = PartitionSource.MAPPER), 100),
            NandroidImageInput(part("persist"), 100),
            NandroidImageInput(part("abl_a", PartitionSlot.A), 100),
        )))
        assertEquals("TWRP/BACKUPS/1A2B3C4D/2026-10-19--07-05-09_UP1A231005007", plan.folder)
        assertEquals(
            listOf("boot.emmc.win", "boot.emmc.win.sha2", "persist.emmc.win", "persist.emmc.win.sha2", "abl.emmc.win", "abl.emmc.win.sha2"),
            plan.entries.map { it.fileName },
        )
        assertEquals("${plan.folder}/boot.emmc.win", plan.entries[0].archivePath)
        assertEquals(NandroidContent.DigestSidecar("boot.emmc.win"), plan.entries[1].content)
        val kinds = plan.warnings.map { it.kind to it.subject }
        assertTrue(NandroidWarningKind.INACTIVE_SLOT_SKIPPED to "boot_b" in kinds)
        assertTrue(NandroidWarningKind.LOGICAL_PARTITION_SKIPPED to "system_a" in kinds)
        assertTrue(NandroidWarningKind.RECOVERY_MAY_NOT_LIST to "abl" in kinds)
    }

    @Test
    fun `duplicate and invalid names are skipped`() {
        val plan = NandroidPlanner.plan(request(images = listOf(
            NandroidImageInput(part("boot"), 1),
            NandroidImageInput(part("BOOT"), 1),
            NandroidImageInput(part("we.ird"), 1),
        )))
        assertEquals(listOf("boot.emmc.win", "boot.emmc.win.sha2"), plan.entries.map { it.fileName })
        assertEquals(setOf(NandroidWarningKind.DUPLICATE_NAME_SKIPPED, NandroidWarningKind.INVALID_NAME_SKIPPED), plan.warnings.map { it.kind }.toSet())
    }

    private fun f(path: String, size: Long) = InventoryEntry(path, InventoryType.FILE, size)
    private fun d(path: String) = InventoryEntry(path, InventoryType.DIRECTORY, 4096)

    @Test
    fun `single data archive applies exclusions and mount-relative names`() {
        val fs = NandroidFilesystemInput(
            "data", "/data", "f2fs",
            listOf(
                d("/data"), d("/data/app"), f("/data/app/base.apk", 10), d("/data/media"), d("/data/media/0"), f("/data/media/0/x.jpg", 99),
                f("/data/cache/c", 1), InventoryEntry("/data/misc/sock", InventoryType.OTHER, 0), f("/data/odd\nname", 1),
                InventoryEntry("/data/link", InventoryType.SYMLINK, 7), d("/data/data/me/cache"), f("/data/data/me/cache/fifo", 0),
            ),
            extraExclusions = listOf("/data/data/me/cache"),
        )
        val plan = NandroidPlanner.plan(request(filesystems = listOf(fs)))
        assertEquals(listOf("data.f2fs.win", "data.f2fs.win.sha2"), plan.entries.map { it.fileName })
        val volume = plan.entries[0].content as NandroidContent.TarVolume
        assertEquals(TarNameStyle.RELATIVE_TO_MOUNT, volume.nameStyle)
        assertEquals("/data", volume.baseDirectory)
        assertEquals(listOf("app", "app/base.apk", "link"), volume.members)
        assertEquals(10L, volume.regularFileBytes)
        val warnings = plan.warnings.associate { it.kind to it.count }
        assertEquals(1, warnings[NandroidWarningKind.SPECIAL_FILES_SKIPPED])
        assertEquals(1, warnings[NandroidWarningKind.UNLISTABLE_NAMES_SKIPPED])
    }

    @Test
    fun `large data splits on file boundaries with root-relative names`() {
        val inventory = listOf(d("/data/a"), f("/data/a/1", 60), f("/data/a/2", 50), d("/data/b"), f("/data/b/3", 30), f("/data/b/4", 200), f("/data/b/5", 1))
        val plan = NandroidPlanner.plan(request(filesystems = listOf(NandroidFilesystemInput("data", "/data", "ext4", inventory))), maxArchiveSize = 100)
        assertEquals(
            (0..3).flatMap { listOf("data.ext4.win00$it", "data.ext4.win00$it.sha2") },
            plan.entries.map { it.fileName },
        )
        val volumes = plan.entries.mapNotNull { it.content as? NandroidContent.TarVolume }
        assertEquals(listOf("data/a", "data/a/1"), volumes[0].members)
        assertEquals(listOf("data/a/2", "data/b", "data/b/3"), volumes[1].members)
        // An oversized file gets its own archive; the next small file follows it.
        assertEquals(listOf("data/b/4"), volumes[2].members)
        assertEquals(listOf("data/b/5"), volumes[3].members)
        assertTrue(volumes.all { it.nameStyle == TarNameStyle.RELATIVE_TO_ROOT && it.baseDirectory == "/" })
        assertEquals(listOf(0, 1, 2, 3), volumes.map { it.volumeIndex })
    }

    @Test
    fun `more than 100 archives is refused`() {
        val inventory = (0 until 101).map { f("/data/f$it", 10) }
        val warnings = ArrayList<NandroidWarning>()
        val volumes = NandroidPlanner.planVolumes(NandroidFilesystemInput("data", "/data", "ext4", inventory), 10, warnings)
        assertNull(volumes)
        assertEquals(NandroidWarningKind.TOO_MANY_ARCHIVES, warnings.single().kind)
    }

    @Test
    fun `log and empty plans`() {
        val plan = NandroidPlanner.plan(request(images = listOf(NandroidImageInput(part("dtbo"), 1)), log = "hello"))
        assertEquals("recovery.log", plan.entries.last().fileName)
        assertTrue(!plan.isEmpty)
        assertTrue(NandroidPlanner.plan(request(log = "only a log")).isEmpty)
        assertEquals("TWRP/BACKUPS/unknown/2026-10-19--07-05-09", NandroidPlanner.plan(request(serial = "", display = null)).folder)
    }
}
