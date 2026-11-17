package ca.devdcs.backup.core.restore.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockDeviceUsageTest {

    private val procMounts = """
        /dev/block/dm-6 / ext4 ro,seclabel,relatime 0 0
        tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,mode=755 0 0
        /dev/block/by-name/metadata /metadata ext4 rw,seclabel,nosuid,nodev,noatime 0 0
        /dev/block/dm-45 /data f2fs rw,lazytime,seclabel,nosuid,nodev,noatime 0 0
        /dev/block/sda4 /mnt/vendor/persist ext4 rw,seclabel,nosuid,nodev,noatime 0 0
        /dev/fuse /mnt/user/0/emulated fuse rw,lazytime,nosuid,nodev,noexec,noatime 0 0
        /dev/block/vold/public:8,1 /mnt/media_rw/My\040Disk vfat rw,dirsync 0 0
        broken-line
    """.trimIndent().lines()

    @Test
    fun parsesMountsAndDecodesEscapes() {
        val mounts = BlockDeviceUsage.parseMounts(procMounts)
        assertEquals(7, mounts.size)
        assertEquals("/mnt/media_rw/My Disk", mounts.last().mountPoint)
        assertEquals("f2fs", mounts[3].fileSystem)
    }

    @Test
    fun unescapeHandlesOctalAndLiteralBackslashes() {
        assertEquals("a b\tc\\d", BlockDeviceUsage.unescape("a\\040b\\011c\\134d"))
        assertEquals("plain", BlockDeviceUsage.unescape("plain"))
        assertEquals("tail\\0", BlockDeviceUsage.unescape("tail\\0"))
    }

    @Test
    fun mountedThroughSymlinkIsDetectedByCanonicalPath() {
        val mounts = BlockDeviceUsage.parseMounts(procMounts)
        val canonical = mapOf("/dev/block/by-name/metadata" to "/dev/block/sda3")
        assertEquals(listOf("/metadata"), BlockDeviceUsage.mountPointsOf("/dev/block/sda3", emptySet(), mounts, canonical))
        assertEquals(listOf("/mnt/vendor/persist"), BlockDeviceUsage.mountPointsOf("/dev/block/sda4", emptySet(), mounts, emptyMap()))
        assertTrue(BlockDeviceUsage.mountPointsOf("/dev/block/sda12", emptySet(), mounts, canonical).isEmpty())
    }

    @Test
    fun aliasMatchesWhenCanonicalisationFailed() {
        val mounts = BlockDeviceUsage.parseMounts(procMounts)
        assertEquals(listOf("/metadata"), BlockDeviceUsage.mountPointsOf("/dev/block/sda3", setOf("/dev/block/by-name/metadata"), mounts, emptyMap()))
    }

    @Test
    fun parsesSwaps() {
        val swaps = BlockDeviceUsage.parseSwaps(
            listOf(
                "Filename\t\t\t\tType\t\tSize\t\tUsed\t\tPriority",
                "/dev/block/zram0                        partition\t2097148\t\t0\t\t-2",
            ),
        )
        assertEquals(listOf("/dev/block/zram0"), swaps)
        assertTrue(BlockDeviceUsage.isSwap("/dev/block/zram0", emptySet(), swaps, emptyMap()))
        assertFalse(BlockDeviceUsage.isSwap("/dev/block/sda12", emptySet(), swaps, emptyMap()))
    }

    private fun probeOutput(holders: List<String> = emptyList(), mounts: List<String> = emptyList(), canon: List<String> = emptyList(), end: Boolean = true): List<String> {
        val m = BlockDeviceProbeScript.MARKER
        return buildList {
            add("$m blk"); add("1")
            add("$m real"); add("/dev/block/sda29")
            add("$m size"); add("246960619520")
            add("$m ro"); add("0")
            add("$m holders"); addAll(holders)
            add("$m mounts"); addAll(mounts)
            add("$m canon"); addAll(canon)
            add("$m swaps"); add("Filename\tType\tSize\tUsed\tPriority")
            if (end) add("$m end")
        }
    }

    @Test
    fun probeParsesDmHolderOfMetadataEncryptedUserdata() {
        // Physical userdata is not in /proc/mounts itself: dm-default-key dm-45 on top of it is.
        val state = BlockDeviceProbeScript.parse(
            "/dev/block/sda29",
            setOf("/dev/block/by-name/userdata"),
            probeOutput(holders = listOf("dm-45\tuserdata"), mounts = listOf("/dev/block/dm-45 /data f2fs rw 0 0"), canon = listOf("/dev/block/dm-45\t/dev/block/dm-45")),
        )
        assertNotNull(state)
        state!!
        assertTrue(state.isBlockDevice)
        assertEquals(246960619520L, state.sizeBytes)
        assertEquals(false, state.readOnly)
        assertEquals(listOf(BlockHolder("dm-45", "userdata")), state.holders)
        assertTrue(state.mountPoints.isEmpty())
        assertEquals("sda29", state.kernelName)

        val report = PartitionSafetyGates.evaluate(
            GateFacts(
                RestorePartitionFixtures.node("userdata", "/dev/block/sda29"),
                state,
                ImageOrigin.Manifest(RestorePartitionFixtures.PIXEL, RestorePartitionFixtures.PIXEL),
                crossesSlot = false,
                imageSizeBytes = 246960619520L,
            ),
            PartitionRestoreOptions(),
        )
        val gates = report.refusals.map { it.gate }.toSet()
        assertTrue(PartitionGate.HELD_BY_DEVICE_MAPPER in gates)
        assertTrue(PartitionGate.LIVE_SYSTEM_PARTITION in gates)
    }

    @Test
    fun probeDetectsDirectMountAndUnknownSize() {
        val m = BlockDeviceProbeScript.MARKER
        val out = listOf(
            "$m blk", "1", "$m real", "/dev/block/sda4", "$m size", "$m ro", "",
            "$m holders", "$m mounts", "/dev/block/by-name/persist /mnt/vendor/persist ext4 rw 0 0",
            "$m canon", "/dev/block/by-name/persist\t/dev/block/sda4", "$m swaps", "$m end",
        )
        val state = BlockDeviceProbeScript.parse("/dev/block/sda4", emptySet(), out)!!
        assertNull(state.sizeBytes)
        assertNull(state.readOnly)
        assertEquals(listOf("/mnt/vendor/persist"), state.mountPoints)
    }

    @Test
    fun truncatedProbeIsRejected() {
        assertNull(BlockDeviceProbeScript.parse("/dev/block/sda29", emptySet(), probeOutput(end = false)))
    }

    @Test
    fun probeScriptQuotesTheDevice() {
        val script = BlockDeviceProbeScript.build("/dev/block/by-name/we'ird")
        assertTrue(script.startsWith("__d='/dev/block/by-name/we'\\''ird'"))
        assertTrue(script.contains("/proc/1/mounts"))
        assertTrue(script.trimEnd().endsWith("true"))
    }
}
