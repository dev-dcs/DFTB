package ca.devdcs.backup.core.restore.twrp

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class TwrpPayloadSnifferTest {

    private fun tarBytes(firstName: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { tar ->
            val data = "hello".toByteArray()
            val entry = TarArchiveEntry(firstName)
            entry.size = data.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(data)
            tar.closeArchiveEntry()
        }
        return bytes.toByteArray()
    }

    private fun sniff(bytes: ByteArray, kind: TwrpPayloadKind = TwrpPayloadKind.FILESYSTEM_ARCHIVE): TwrpPayloadFormat {
        val header = ByteArray(TwrpPayloadSniffer.HEADER_BYTES)
        val n = minOf(bytes.size, header.size)
        System.arraycopy(bytes, 0, header, 0, n)
        return TwrpPayloadSniffer.sniff(header, n, kind)
    }

    @Test
    fun detectsTarGzipAndEncryption() {
        assertEquals(TwrpPayloadFormat.TAR, sniff(tarBytes("app/x")))
        // A member whose name starts with "OA" is still a tar, not ciphertext.
        assertEquals(TwrpPayloadFormat.TAR, sniff(tarBytes("OAuth/token")))
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tarBytes("a")) } }.toByteArray()
        assertEquals(TwrpPayloadFormat.GZIP, sniff(gz))
        assertEquals(TwrpPayloadFormat.ENCRYPTED, sniff("OAES\u0001\u0002 ciphertext".toByteArray() + ByteArray(600)))
        assertEquals(TwrpPayloadFormat.UNKNOWN, sniff(ByteArray(600) { 7 }))
        assertEquals(TwrpPayloadFormat.EMPTY, sniff(ByteArray(0)))
    }

    @Test
    fun rawImagesAreNeverReinterpreted() {
        assertEquals(TwrpPayloadFormat.RAW, sniff(byteArrayOf(0x1f, 0x8b.toByte(), 0, 0), TwrpPayloadKind.RAW_IMAGE))
        assertEquals(TwrpPayloadFormat.RAW, sniff("ANDROID!".toByteArray(), TwrpPayloadKind.RAW_IMAGE))
    }

    @Test
    fun zeroBlockIsNotATarHeader() {
        assertEquals(TwrpPayloadFormat.UNKNOWN, sniff(ByteArray(512)))
    }

    @Test
    fun archivePathsStayInsideTheFolder() {
        assertEquals("app/x", TwrpArchivePaths.outputPath("app/x", null))
        assertEquals("app/x", TwrpArchivePaths.outputPath("data/app/x", "data"))
        assertEquals("app/x", TwrpArchivePaths.outputPath("/data/./app/x", "data"))
        assertEquals("data/x", TwrpArchivePaths.outputPath("data/x", null))
        assertNull(TwrpArchivePaths.outputPath("../etc/passwd", null))
        assertNull(TwrpArchivePaths.outputPath("app/../../x", null))
        assertNull(TwrpArchivePaths.outputPath("data/", "data"))
        assertNull(TwrpArchivePaths.outputPath("a/b\nc", null))
    }
}
