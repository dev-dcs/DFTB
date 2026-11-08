package ca.devdcs.backup.domain

import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.StructureMode
import ca.devdcs.backup.domain.model.TransferMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineOptionsSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun `defaults survive a round trip`() {
        val original = PipelineOptions.DEFAULT
        val restored = json.decodeFromString<PipelineOptions>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `fully customised options survive a round trip`() {
        val original = PipelineOptions(
            archiveFormat = ArchiveFormat.SEVEN_ZIP,
            compressionCodec = CompressionCodec.XZ,
            compressionLevel = 7,
            encryptionMode = EncryptionMode.AES_256_GCM,
            encryptionKeyRef = "vault:key:abc",
            maskFilenames = true,
            preservePosixMetadata = false,
            preserveSelinuxContext = false,
            structureMode = StructureMode.CATEGORIZED,
            transferMode = TransferMode.DIRECT_STREAM,
            checksumAlgorithm = ChecksumAlgorithm.BLAKE3,
            verifyAfterBackup = false,
            twrpCompatible = true,
        )
        val restored = json.decodeFromString<PipelineOptions>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `enums are encoded by storageKey not by constant name`() {
        val encoded = json.encodeToString(PipelineOptions(archiveFormat = ArchiveFormat.SEVEN_ZIP, compressionCodec = CompressionCodec.ZSTD))
        val tree = json.parseToJsonElement(encoded).jsonObject
        assertEquals("7z", tree.getValue("archiveFormat").jsonPrimitive.content)
        assertEquals("zstd", tree.getValue("compressionCodec").jsonPrimitive.content)
        assertEquals("sha256", tree.getValue("checksumAlgorithm").jsonPrimitive.content)
        assertEquals("none", tree.getValue("encryptionMode").jsonPrimitive.content)
    }

    @Test
    fun `unknown enum keys fall back to the enum default instead of failing`() {
        val restored = json.decodeFromString<PipelineOptions>("""{"archiveFormat":"future_format","encryptionMode":"quantum"}""")
        assertEquals(ArchiveFormat.DEFAULT, restored.archiveFormat)
        assertEquals(EncryptionMode.NONE, restored.encryptionMode)
    }

    @Test
    fun `missing fields take defaults and unknown fields are ignored`() {
        val restored = json.decodeFromString<PipelineOptions>("""{"compressionLevel":9,"someNewField":true}""")
        assertEquals(9, restored.compressionLevel)
        assertEquals(PipelineOptions.DEFAULT.copy(compressionLevel = 9), restored)
    }

    @Test
    fun `effective compression level is clamped per format`() {
        assertEquals(19, PipelineOptions(archiveFormat = ArchiveFormat.TAR_ZST, compressionLevel = 42).effectiveCompressionLevel)
        assertEquals(9, PipelineOptions(archiveFormat = ArchiveFormat.TAR_GZ, compressionLevel = 42).effectiveCompressionLevel)
        assertEquals(0, PipelineOptions(archiveFormat = ArchiveFormat.TAR, compressionLevel = 5).effectiveCompressionLevel)
        assertEquals(0, PipelineOptions(archiveFormat = ArchiveFormat.TAR_XZ, compressionLevel = -3).effectiveCompressionLevel)
        assertEquals(
            9,
            PipelineOptions(archiveFormat = ArchiveFormat.DD_IMAGE, compressionCodec = CompressionCodec.GZIP, compressionLevel = 15).effectiveCompressionLevel,
        )
    }

    @Test
    fun `isEncrypted reflects the mode`() {
        assertTrue(PipelineOptions(encryptionMode = EncryptionMode.GPG).isEncrypted)
        assertTrue(!PipelineOptions.DEFAULT.isEncrypted)
    }
}
