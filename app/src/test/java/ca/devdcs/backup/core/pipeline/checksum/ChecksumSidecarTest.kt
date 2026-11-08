package ca.devdcs.backup.core.pipeline.checksum

import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumSidecarTest {

    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun formatMatchesSha256sumOutput() {
        assertEquals("$sha  backup.tar.zst\n", ChecksumSidecar.formatLine(sha, "backup.tar.zst"))
        assertEquals("$sha *backup.img\n", ChecksumSidecar.formatLine(sha.uppercase(), "backup.img", binaryMode = true))
    }

    @Test
    fun escapedNamesRoundTrip() {
        val name = "we\\ird\nna\rme"
        val line = ChecksumSidecar.formatLine(sha, name)
        assertTrue(line.startsWith("\\$sha  "))
        assertEquals("\\$sha  we\\\\ird\\nna\\rme\n", line)
        val parsed = ChecksumSidecar.parse(line)
        assertEquals(listOf(ChecksumSidecar.Entry(sha, name)), parsed.entries)
    }

    @Test
    fun parsesMultipleFormatsAndReportsMalformed() {
        val text = buildString {
            append("# comment\n")
            append("$sha  a file with  spaces.bin\r\n")
            append("d41d8cd98f00b204e9800998ecf8427e *boot.emmc.win\n")
            append("SHA256 (system.img) = $sha\n")
            append("not a checksum line\n")
            append("\n")
        }
        val r = ChecksumSidecar.parse(text)
        assertEquals(3, r.entries.size)
        assertEquals("a file with  spaces.bin", r.entries[0].fileName)
        assertTrue(r.entries[1].binaryMode)
        assertEquals("boot.emmc.win", r.entries[1].fileName)
        assertEquals(ChecksumAlgorithm.SHA256, r.entries[2].algorithm)
        assertEquals("system.img", r.entries[2].fileName)
        assertEquals(listOf(5), r.malformedLines)
    }

    @Test
    fun sidecarNamesIncludingTwrp() {
        assertEquals("boot.emmc.win.sha256", ChecksumSidecar.sidecarName("boot.emmc.win", ChecksumAlgorithm.SHA256))
        assertEquals("boot.emmc.win.sha2", ChecksumSidecar.sidecarName("boot.emmc.win", ChecksumAlgorithm.SHA256, ChecksumSidecar.Style.TWRP))
        assertEquals("boot.emmc.win.md5", ChecksumSidecar.sidecarName("boot.emmc.win", ChecksumAlgorithm.MD5, ChecksumSidecar.Style.TWRP))
        assertEquals(ChecksumAlgorithm.SHA256, ChecksumSidecar.algorithmForSidecarName("x.sha2"))
        assertEquals(ChecksumAlgorithm.SHA256, ChecksumSidecar.algorithmForSidecarName("SHA256SUMS"))
        assertEquals(ChecksumAlgorithm.MD5, ChecksumSidecar.algorithmForSidecarName("x.md5sum"))
        assertEquals(ChecksumAlgorithm.BLAKE3, ChecksumSidecar.algorithmForSidecarName("x.blake3"))
        assertNull(ChecksumSidecar.algorithmForSidecarName("x.tar"))
    }

    @Test
    fun matchesIgnoresCase() {
        assertTrue(ChecksumSidecar.matches(sha.uppercase(), sha))
        assertFalse(ChecksumSidecar.matches(sha, sha.replace('a', 'b')))
    }
}
