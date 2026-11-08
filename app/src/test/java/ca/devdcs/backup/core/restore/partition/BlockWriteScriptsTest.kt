package ca.devdcs.backup.core.restore.partition

import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.ManifestItem
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CompressionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockWriteScriptsTest {

    @Test
    fun writeScriptGuardsBeforeDd() {
        val script = BlockWriteScripts.writeScript("/dev/block/sda12", 67_108_864L, setReadWrite = false)
        val dd = script.indexOf("dd of='/dev/block/sda12' bs=4194304 conv=fsync")
        assertTrue(dd > 0)
        assertTrue(script.indexOf("[ -b '/dev/block/sda12' ]") in 0 until dd)
        assertTrue(script.indexOf("'67108864'") in 0 until dd)
        assertTrue(script.indexOf("blockdev --getro") in 0 until dd)
        assertFalse(script.contains("--setrw"))
        assertTrue(script.endsWith("conv=fsync"))
    }

    @Test
    fun writeScriptRechecksHoldersMountsAndSwapsBeforeDd() {
        val script = BlockWriteScripts.writeScript("/dev/block/dm-3", 1L, setReadWrite = true)
        val dd = script.indexOf("dd of=")
        val holders = script.indexOf("/holders/*")
        val mounts = script.indexOf("/proc/mounts /proc/1/mounts /proc/swaps")
        assertTrue(holders in 0 until dd)
        assertTrue(mounts in 0 until dd)
        assertTrue(script.indexOf("exit ${BlockWriteScripts.EXIT_HELD}") in holders until dd)
        assertTrue(script.indexOf("exit ${BlockWriteScripts.EXIT_MOUNTED}") in mounts until dd)
        // The in-use checks run before setrw, so a refused device is never switched read-write.
        assertTrue(mounts < script.indexOf("--setrw"))
        assertTrue(BlockWriteScripts.EXIT_HELD in BlockWriteScripts.GUARD_EXITS)
        assertTrue(BlockWriteScripts.EXIT_MOUNTED in BlockWriteScripts.GUARD_EXITS)
    }

    @Test
    fun logicalWritesSetReadWriteFirst() {
        val script = BlockWriteScripts.writeScript("/dev/block/dm-3", 1L, setReadWrite = true)
        assertTrue(script.indexOf("blockdev --setrw '/dev/block/dm-3'") in 0 until script.indexOf("dd of="))
    }

    @Test
    fun pathsAreQuoted() {
        val script = BlockWriteScripts.writeScript("/dev/x'; rm -rf /; '", 1L, setReadWrite = false)
        assertTrue(script.contains("'/dev/x'\\''; rm -rf /; '\\'''"))
    }

    @Test
    fun helperReadsStdinFromTheDataFifo() {
        val helper = BlockWriteScripts.fifoHelper("dd of=x", "/c/d.data", "/c/d.status")
        assertTrue(helper.startsWith("exec 3<'/c/d.data' || exit 1; "))
        assertTrue(helper.contains("{ dd of=x ; } <&3 2>&1 >/dev/null 3<&-"))
        assertTrue(helper.endsWith("> '/c/d.status'"))
    }

    @Test
    fun readBackFlushesBuffersFirst() {
        val script = BlockWriteScripts.readBackScript("/dev/block/sda12")
        assertTrue(script.indexOf("--flushbufs") < script.indexOf("dd if="))
    }

    @Test
    fun guardExitsMeanUntouched() {
        assertTrue(BlockWriteScripts.EXIT_SIZE_MISMATCH in BlockWriteScripts.GUARD_EXITS)
        assertFalse(1 in BlockWriteScripts.GUARD_EXITS)
    }

    @Test
    fun referenceAlgorithmPrefersSha256() {
        assertEquals(ChecksumAlgorithm.SHA256, PartitionImageWriter.referenceAlgorithm(emptyMap()))
        assertEquals(ChecksumAlgorithm.SHA256, PartitionImageWriter.referenceAlgorithm(mapOf(ChecksumAlgorithm.MD5 to "a", ChecksumAlgorithm.SHA256 to "b")))
        assertEquals(ChecksumAlgorithm.MD5, PartitionImageWriter.referenceAlgorithm(mapOf(ChecksumAlgorithm.BLAKE3 to "a", ChecksumAlgorithm.MD5 to "b")))
    }

    private fun artifact(layout: String, items: List<ManifestItem>, kind: String = "partition") = ManifestArtifact(
        sourceId = "partitions:0123456789abcdef",
        kind = kind,
        name = "boot_a",
        storedName = "boot_a.img.zst",
        masked = false,
        layout = layout,
        format = ArchiveFormat.DD_IMAGE,
        codec = CompressionCodec.ZSTD,
        compressionLevel = 3,
        sizeBytes = 1000,
        sourceBytes = 67_108_864L,
        digests = mapOf("sha256" to "00"),
        checksumFile = "boot_a.img.zst.sha256",
        items = items,
    )

    @Test
    fun recognisesPartitionArtifacts() {
        val item = ManifestItem("partitions/boot_a.img", ManifestItem.ItemType.STREAM, sizeBytes = 67_108_864L, digests = mapOf("sha256" to "ab", "bogus" to "cd"))
        val ref = RootPartitionRestorer.imageOf(artifact("raw_stream", listOf(item)))
        assertNotNull(ref)
        ref!!
        assertEquals(ImagedNode("boot_a", false), ref.node)
        assertEquals(mapOf(ChecksumAlgorithm.SHA256 to "ab"), ref.expectedDigests)
        assertEquals(67_108_864L, ref.declaredSizeBytes)
        assertTrue(RootPartitionRestorer.isPartitionArtifact(artifact("raw_stream", listOf(item))))
        assertFalse(RootPartitionRestorer.isPartitionArtifact(artifact("raw_stream", listOf(item), kind = "files")))
        assertNull(RootPartitionRestorer.imageOf(artifact("archive", listOf(item))))
        assertNull(RootPartitionRestorer.imageOf(artifact("raw_stream", listOf(item, item))))
    }
}
