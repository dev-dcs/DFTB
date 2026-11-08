package ca.devdcs.backup.ui.profiles

import ca.devdcs.backup.domain.model.AppTarget
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.PartitionTarget
import ca.devdcs.backup.domain.model.PathTarget
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.TargetSelection
import ca.devdcs.backup.domain.model.TransferMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineRulesTest {

    private val treesOnly = TargetShape(hasPartitions = false, hasTrees = true)
    private val imagesOnly = TargetShape(hasPartitions = true, hasTrees = false)
    private val both = TargetShape(hasPartitions = true, hasTrees = true)
    private val nothing = TargetShape(hasPartitions = false, hasTrees = false)

    @Test
    fun `shape follows the selection`() {
        assertEquals(nothing, TargetShape.of(TargetSelection.EMPTY))
        assertEquals(imagesOnly, TargetShape.of(TargetSelection(partitions = listOf(PartitionTarget("boot")))))
        assertEquals(treesOnly, TargetShape.of(TargetSelection(apps = listOf(AppTarget("a.b")))))
        assertEquals(treesOnly, TargetShape.of(TargetSelection(paths = listOf(PathTarget("/sdcard")))))
        assertEquals(
            both,
            TargetShape.of(TargetSelection(partitions = listOf(PartitionTarget("boot")), paths = listOf(PathTarget("/x")))),
        )
    }

    @Test
    fun `dd image is never offered for trees`() {
        assertFalse(ArchiveFormat.DD_IMAGE in PipelineRules.TREE_FORMATS)
        assertTrue(PipelineRules.TREE_FORMATS.containsAll(ArchiveFormat.entries - ArchiveFormat.DD_IMAGE))
    }

    @Test
    fun `stored dd image maps to the tar format the engine substitutes`() {
        assertEquals(ArchiveFormat.TAR_GZ, PipelineRules.treeFormatOf(ArchiveFormat.DD_IMAGE, CompressionCodec.GZIP))
        assertEquals(ArchiveFormat.TAR, PipelineRules.treeFormatOf(ArchiveFormat.DD_IMAGE, CompressionCodec.NONE))
        assertEquals(ArchiveFormat.ZIP, PipelineRules.treeFormatOf(ArchiveFormat.ZIP, CompressionCodec.XZ))
    }

    @Test
    fun `archive format picker only matters for trees or an empty selection`() {
        assertTrue(PipelineRules.showsArchiveFormat(treesOnly))
        assertTrue(PipelineRules.showsArchiveFormat(both))
        assertTrue(PipelineRules.showsArchiveFormat(nothing))
        assertFalse(PipelineRules.showsArchiveFormat(imagesOnly))
    }

    @Test
    fun `codec matters for partitions and raw copies only`() {
        val zst = PipelineOptions(archiveFormat = ArchiveFormat.TAR_ZST)
        val raw = PipelineOptions(archiveFormat = ArchiveFormat.RAW_COPY)
        assertFalse(PipelineRules.showsCodec(treesOnly, zst))
        assertTrue(PipelineRules.showsCodec(treesOnly, raw))
        assertTrue(PipelineRules.showsCodec(imagesOnly, zst))
        assertTrue(PipelineRules.showsCodec(both, zst))
    }

    @Test
    fun `tree level limits follow the format or its implied codec`() {
        assertEquals(19, PipelineRules.treeMaxLevel(ArchiveFormat.TAR_ZST, CompressionCodec.GZIP))
        assertEquals(9, PipelineRules.treeMaxLevel(ArchiveFormat.TAR_GZ, CompressionCodec.ZSTD))
        assertEquals(9, PipelineRules.treeMaxLevel(ArchiveFormat.ZIP, CompressionCodec.ZSTD))
        assertEquals(9, PipelineRules.treeMaxLevel(ArchiveFormat.SEVEN_ZIP, CompressionCodec.ZSTD))
        assertEquals(0, PipelineRules.treeMaxLevel(ArchiveFormat.TAR, CompressionCodec.ZSTD))
        assertEquals(19, PipelineRules.treeMaxLevel(ArchiveFormat.RAW_COPY, CompressionCodec.ZSTD))
        assertEquals(0, PipelineRules.treeMaxLevel(ArchiveFormat.RAW_COPY, CompressionCodec.NONE))
    }

    @Test
    fun `level range is the widest applicable scale or none`() {
        assertEquals(0..19, PipelineRules.levelRange(PipelineOptions(archiveFormat = ArchiveFormat.TAR_ZST), treesOnly))
        assertNull(PipelineRules.levelRange(PipelineOptions(archiveFormat = ArchiveFormat.TAR), treesOnly))
        // Plain tar trees plus gzip images: only the images are tunable.
        assertEquals(
            0..9,
            PipelineRules.levelRange(PipelineOptions(archiveFormat = ArchiveFormat.TAR, compressionCodec = CompressionCodec.GZIP), both),
        )
        assertEquals(
            0..19,
            PipelineRules.levelRange(PipelineOptions(archiveFormat = ArchiveFormat.TAR_GZ, compressionCodec = CompressionCodec.ZSTD), both),
        )
        assertNull(PipelineRules.levelRange(PipelineOptions(compressionCodec = CompressionCodec.NONE), imagesOnly))
    }

    @Test
    fun `effective levels clamp per part`() {
        val options = PipelineOptions(archiveFormat = ArchiveFormat.TAR_ZST, compressionCodec = CompressionCodec.GZIP, compressionLevel = 19)
        assertEquals(19, PipelineRules.effectiveTreeLevel(options))
        assertEquals(9, PipelineRules.effectiveImageLevel(options))
    }

    @Test
    fun `level tiers split the scale in thirds with ultra on 0 to 19`() {
        assertEquals(LevelTier.STORE, PipelineRules.levelTier(0, 9))
        assertEquals(LevelTier.FAST, PipelineRules.levelTier(3, 9))
        assertEquals(LevelTier.BALANCED, PipelineRules.levelTier(5, 9))
        assertEquals(LevelTier.MAXIMUM, PipelineRules.levelTier(9, 9))
        assertEquals(LevelTier.MAXIMUM, PipelineRules.levelTier(15, 19))
        assertEquals(LevelTier.ULTRA, PipelineRules.levelTier(16, 19))
        assertEquals(LevelTier.STORE, PipelineRules.levelTier(5, 0))
    }

    @Test
    fun `twrp forces plain output and drops encryption`() {
        val options = PipelineOptions(
            archiveFormat = ArchiveFormat.TAR_XZ,
            compressionCodec = CompressionCodec.ZSTD,
            compressionLevel = 12,
            maskFilenames = true,
            twrpCompatible = true,
        )
        val normalized = PipelineRules.normalize(options, KeyChoice.VAULT_KEY)
        assertEquals(ArchiveFormat.RAW_COPY, normalized.archiveFormat)
        assertEquals(CompressionCodec.NONE, normalized.compressionCodec)
        assertEquals(0, normalized.compressionLevel)
        assertEquals(EncryptionMode.NONE, normalized.encryptionMode)
        assertFalse(normalized.maskFilenames)
        assertEquals(KeyChoice.NONE, PipelineRules.effectiveKeyChoice(KeyChoice.VAULT_KEY, options))
    }

    @Test
    fun `encryption mode follows the key choice and masking needs encryption`() {
        val masked = PipelineOptions(maskFilenames = true)
        assertEquals(EncryptionMode.AES_256_GCM, PipelineRules.normalize(masked, KeyChoice.PASSPHRASE).encryptionMode)
        assertTrue(PipelineRules.normalize(masked, KeyChoice.PASSPHRASE).maskFilenames)
        val plain = PipelineRules.normalize(masked, KeyChoice.NONE)
        assertEquals(EncryptionMode.NONE, plain.encryptionMode)
        assertFalse(plain.maskFilenames)
    }

    @Test
    fun `normalize keeps the level so switching formats does not lose it`() {
        val options = PipelineOptions(archiveFormat = ArchiveFormat.TAR_GZ, compressionLevel = 17)
        assertEquals(17, PipelineRules.normalize(options, KeyChoice.NONE).compressionLevel)
    }

    @Test
    fun `option blocks explain what cannot be chosen`() {
        val twrp = PipelineOptions(twrpCompatible = true)
        assertEquals(OptionBlock.TWRP_FORCES_PLAIN, PipelineRules.keyChoiceBlock(KeyChoice.PASSPHRASE, twrp))
        assertNull(PipelineRules.keyChoiceBlock(KeyChoice.NONE, twrp))
        assertNull(PipelineRules.keyChoiceBlock(KeyChoice.VAULT_KEY, PipelineOptions()))
        assertEquals(OptionBlock.TWRP_FORCES_PLAIN, PipelineRules.maskBlock(KeyChoice.VAULT_KEY, twrp))
        assertEquals(OptionBlock.MASK_NEEDS_ENCRYPTION, PipelineRules.maskBlock(KeyChoice.NONE, PipelineOptions()))
        assertNull(PipelineRules.maskBlock(KeyChoice.VAULT_KEY, PipelineOptions()))
        assertEquals(OptionBlock.TWRP_FORCES_PLAIN, PipelineRules.compressionBlock(twrp))
        assertNull(PipelineRules.compressionBlock(PipelineOptions()))
    }

    @Test
    fun `remote compression is blocked with the engine's reason`() {
        assertEquals(OptionBlock.REMOTE_COMPRESS_NOT_AVAILABLE, PipelineRules.transferBlock(TransferMode.REMOTE_COMPRESS, DestinationType.SFTP))
        assertEquals(OptionBlock.REMOTE_COMPRESS_NOT_AVAILABLE, PipelineRules.transferBlock(TransferMode.REMOTE_COMPRESS, DestinationType.SSH))
        assertEquals(OptionBlock.REMOTE_COMPRESS_NEEDS_SSH, PipelineRules.transferBlock(TransferMode.REMOTE_COMPRESS, DestinationType.SMB))
        assertEquals(OptionBlock.REMOTE_COMPRESS_NEEDS_SSH, PipelineRules.transferBlock(TransferMode.REMOTE_COMPRESS, null))
        TransferMode.entries.filter { it != TransferMode.REMOTE_COMPRESS }.forEach { mode ->
            DestinationType.entries.forEach { type -> assertNull("$mode/$type", PipelineRules.transferBlock(mode, type)) }
        }
    }

    @Test
    fun `staging is direct for local destinations`() {
        assertTrue(PipelineRules.stagingIsDirect(DestinationType.LOCAL_INTERNAL))
        assertTrue(PipelineRules.stagingIsDirect(DestinationType.USB_OTG))
        assertFalse(PipelineRules.stagingIsDirect(DestinationType.WEBDAV))
        assertFalse(PipelineRules.stagingIsDirect(null))
    }

    @Test
    fun `stored secret is only offered when the profile already has one`() {
        assertEquals(listOf(KeyChoice.NONE, KeyChoice.PASSPHRASE, KeyChoice.VAULT_KEY), PipelineRules.keyChoices(hasStoredSecret = false))
        assertTrue(KeyChoice.STORED_SECRET in PipelineRules.keyChoices(hasStoredSecret = true))
    }
}
