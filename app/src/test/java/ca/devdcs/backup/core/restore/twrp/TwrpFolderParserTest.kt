package ca.devdcs.backup.core.restore.twrp

import ca.devdcs.backup.core.discovery.partition.DiscoveredPartition
import ca.devdcs.backup.core.discovery.partition.PartitionCategory
import ca.devdcs.backup.core.discovery.partition.PartitionRisk
import ca.devdcs.backup.core.discovery.partition.PartitionSlot
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.discovery.partition.ResolvedPartition
import ca.devdcs.backup.core.pipeline.layout.InventoryEntry
import ca.devdcs.backup.core.pipeline.layout.InventoryType
import ca.devdcs.backup.core.pipeline.layout.NandroidContent
import ca.devdcs.backup.core.pipeline.layout.NandroidFilesystemInput
import ca.devdcs.backup.core.pipeline.layout.NandroidImageInput
import ca.devdcs.backup.core.pipeline.layout.NandroidPlanner
import ca.devdcs.backup.core.pipeline.layout.NandroidRequest
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.PartitionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TwrpFolderParserTest {

    private fun file(name: String, size: Long = 10) = FolderEntry(name, isDirectory = false, sizeBytes = size)

    @Test
    fun parsesWinNames() {
        assertEquals(TwrpFolderParser.WinName("boot", "emmc", null), TwrpFolderParser.parseWinName("boot.emmc.win"))
        assertEquals(TwrpFolderParser.WinName("data", "ext4", 3), TwrpFolderParser.parseWinName("data.ext4.win003"))
        assertEquals(TwrpFolderParser.WinName("data", "f2fs", 101), TwrpFolderParser.parseWinName("data.f2fs.win101"))
        assertNull(TwrpFolderParser.parseWinName("data.ext4.win.sha2"))
        assertNull(TwrpFolderParser.parseWinName("a.b.c.win"))
        assertNull(TwrpFolderParser.parseWinName("data.ext4.win01"))
        assertEquals("data.ext4.win000" to ChecksumAlgorithm.SHA256, TwrpFolderParser.parseSidecarName("data.ext4.win000.sha2"))
        assertEquals("boot.emmc.win" to ChecksumAlgorithm.MD5, TwrpFolderParser.parseSidecarName("boot.emmc.win.md5"))
        assertNull(TwrpFolderParser.parseSidecarName("notes.txt.sha2"))
    }

    @Test
    fun deviceIdFromFolder() {
        assertEquals("1A2B3C", TwrpFolderParser.deviceIdOf("TWRP/BACKUPS/1A2B3C/2024-01-01--10-00-00"))
        assertEquals("ser", TwrpFolderParser.deviceIdOf("sdcard/twrp/backups/ser/name"))
        assertNull(TwrpFolderParser.deviceIdOf("Backups/name"))
    }

    @Test
    fun parsesRealTwrpFolderWithSplitsSidecarsInfoAndLog() {
        val entries = listOf(
            file("boot.emmc.win", 100_663_296), file("boot.emmc.win.sha2"), file("boot.info"),
            file("data.f2fs.win000"), file("data.f2fs.win000.sha2"),
            file("data.f2fs.win001"), file("data.f2fs.win001.sha2"),
            file("data.f2fs.win002"),
            file("data.info"), file("recovery.log"), file("system_image.emmc.win"), file("system_image.emmc.win.sha256"),
            file("stray.bin"), FolderEntry("sub", isDirectory = true, sizeBytes = null),
        )
        val backup = TwrpFolderParser.parse("TWRP/BACKUPS/SER1/2024-01-01--10-00-00_AP1A", entries)
        assertEquals("SER1", backup.deviceId)
        assertEquals("2024-01-01--10-00-00_AP1A", backup.name)
        assertEquals("TWRP/BACKUPS/SER1/2024-01-01--10-00-00_AP1A/recovery.log", backup.logFile)
        assertEquals(listOf("stray.bin"), backup.otherFiles)
        assertTrue(backup.problems.isEmpty())

        val byName = backup.partitions.associateBy { it.backupName }
        val boot = byName.getValue("boot")
        assertEquals(TwrpPayloadKind.RAW_IMAGE, boot.kind)
        assertFalse(boot.isSplit)
        assertEquals(ChecksumAlgorithm.SHA256, boot.parts.single().sidecar!!.algorithm)
        assertEquals("TWRP/BACKUPS/SER1/2024-01-01--10-00-00_AP1A/boot.info", boot.infoFile)

        val data = byName.getValue("data")
        assertEquals(TwrpPayloadKind.FILESYSTEM_ARCHIVE, data.kind)
        assertTrue(data.isSplit)
        assertEquals(listOf(0, 1, 2), data.parts.map { it.splitIndex })
        assertNull(data.parts[2].sidecar)
        assertEquals(30L, data.totalBytes)

        assertEquals("system", byName.getValue("system_image").partitionName)
    }

    @Test
    fun reportsGapsOrphansDuplicatesAndMixedSets() {
        val entries = listOf(
            file("data.ext4.win000"), file("data.ext4.win002"),
            file("cache.ext4.win"), file("cache.ext4.win000"),
            file("persist.emmc.win"), file("persist.emmc.win.sha2"), file("persist.emmc.win.md5"),
            file("gone.emmc.win.sha2"),
        )
        val backup = TwrpFolderParser.parse("x", entries)
        val problems = backup.problems.map { it.kind to it.subject }.toSet()
        assertTrue(TwrpFolderProblemKind.SPLIT_GAP to "data.ext4.win001" in problems)
        assertTrue(TwrpFolderProblemKind.SPLIT_AND_SINGLE to "cache.ext4.win" in problems)
        assertTrue(TwrpFolderProblemKind.ORPHAN_SIDECAR to "gone.emmc.win" in problems)
        assertTrue(TwrpFolderProblemKind.DUPLICATE_SIDECAR to "persist.emmc.win" in problems)
        val persist = backup.partitions.single { it.backupName == "persist" }
        assertEquals("persist.emmc.win.sha2", persist.parts.single().sidecar!!.fileName)
        assertEquals(1, backup.partitions.single { it.backupName == "cache" }.parts.size)
    }

    @Test
    fun missingSplitsPerThread() {
        assertEquals(emptyList<Int>(), TwrpFolderParser.missingSplits(listOf(0, 1, 2)))
        assertEquals(listOf(1), TwrpFolderParser.missingSplits(listOf(0, 2)))
        assertEquals(listOf(0), TwrpFolderParser.missingSplits(listOf(1)))
        assertEquals(listOf(101), TwrpFolderParser.missingSplits(listOf(0, 1, 100, 102)))
    }

    @Test
    fun parsesWhatDftbNandroidLayoutWrites() {
        val boot = DiscoveredPartition(
            "boot_a", "boot", PartitionSlot.A, "/dev/block/by-name/boot_a", "/dev/block/sda12", 64L, false,
            PartitionCategory.BOOT_CHAIN, true, PartitionRisk.SAFE_TO_IMAGE, PartitionSource.BY_NAME,
        )
        val request = NandroidRequest(
            serial = "SER-1",
            buildDisplayId = "AP1A.240505.004",
            time = LocalDateTime.of(2024, 5, 6, 7, 8, 9),
            images = listOf(NandroidImageInput(ResolvedPartition(PartitionTarget("boot"), boot), 64L)),
            filesystems = listOf(
                NandroidFilesystemInput(
                    backupName = "data",
                    mountPoint = "/data",
                    fileSystem = "f2fs",
                    inventory = listOf(
                        InventoryEntry("/data/app", InventoryType.DIRECTORY, 0),
                        InventoryEntry("/data/app/a", InventoryType.FILE, 60),
                        InventoryEntry("/data/app/b", InventoryType.FILE, 60),
                    ),
                ),
            ),
            logText = "log",
        )
        val plan = NandroidPlanner.plan(request, maxArchiveSize = 100)
        val names = plan.entries.map { it.fileName }
        val backup = TwrpFolderParser.parse(plan.folder, names.map { file(it) })
        assertEquals("SER-1", backup.deviceId)
        assertTrue(backup.problems.isEmpty())
        assertTrue(backup.otherFiles.isEmpty())
        assertTrue(backup.logFile != null)
        val data = backup.partitions.single { it.backupName == "data" }
        val volumes = plan.entries.count { it.content is NandroidContent.TarVolume }
        assertEquals(volumes, data.parts.size)
        assertTrue(data.parts.all { it.sidecar?.algorithm == ChecksumAlgorithm.SHA256 })
        assertEquals(TwrpPayloadKind.RAW_IMAGE, backup.partitions.single { it.backupName == "boot" }.kind)
    }
}
