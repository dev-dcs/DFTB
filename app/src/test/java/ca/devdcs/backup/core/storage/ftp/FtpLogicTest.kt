package ca.devdcs.backup.core.storage.ftp

import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import org.apache.commons.net.ftp.FTPConnectionClosedException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class FtpLogicTest {

    @Test
    fun `segments drop empty and dot segments`() {
        assertEquals(listOf("a", "b", "c"), NetPaths.segments("/a//./b/c/"))
        assertEquals(emptyList<String>(), NetPaths.segments(""))
        assertEquals(emptyList<String>(), NetPaths.segments("/"))
    }

    @Test
    fun `dot dot is refused`() {
        expectReason(Reason.MISCONFIGURED) { NetPaths.segments("a/../b") }
        expectReason(Reason.MISCONFIGURED) { NetPaths.segments("a\u0000b") }
    }

    @Test
    fun `parent name and child`() {
        assertEquals("a/b", NetPaths.parent("/a/b/c.tar"))
        assertEquals("", NetPaths.parent("c.tar"))
        assertEquals("c.tar", NetPaths.name("a/b/c.tar"))
        assertEquals("", NetPaths.name(""))
        assertEquals("a/b/c", NetPaths.child("a/b/", "c"))
        assertEquals("c", NetPaths.child("", "c"))
    }

    @Test
    fun `root resolution against login directory`() {
        assertEquals("/home/me", FtpSession.resolveRoot(null, "/home/me"))
        assertEquals("/home/me/backups", FtpSession.resolveRoot("backups", "/home/me/"))
        assertEquals("/srv/dftb", FtpSession.resolveRoot("/srv//dftb/", "/home/me"))
        assertEquals("", FtpSession.resolveRoot("", "/"))
        assertEquals("", FtpSession.resolveRoot("/", null))
    }

    @Test
    fun `absolute paths under root`() {
        assertEquals("/", FtpSession.absolutePath("", ""))
        assertEquals("/a.tar", FtpSession.absolutePath("", "a.tar"))
        assertEquals("/srv", FtpSession.absolutePath("/srv", "/"))
        assertEquals("/srv/x/y.tar", FtpSession.absolutePath("/srv", "x/y.tar"))
    }

    @Test
    fun `reply codes map to reasons`() {
        assertEquals(Reason.AUTH, FtpErrors.reasonFor(530, "530 Login incorrect."))
        assertEquals(Reason.NOT_FOUND, FtpErrors.reasonFor(550, "550 No such file or directory"))
        assertEquals(Reason.NOT_FOUND, FtpErrors.reasonFor(550, "550 File not found"))
        assertEquals(Reason.PERMISSION, FtpErrors.reasonFor(550, "550 Permission denied"))
        assertEquals(Reason.NO_SPACE, FtpErrors.reasonFor(552, "552 Quota exceeded"))
        assertEquals(Reason.NO_SPACE, FtpErrors.reasonFor(452, "452 Insufficient storage"))
        assertEquals(Reason.NETWORK, FtpErrors.reasonFor(421, null))
        assertEquals(Reason.MISCONFIGURED, FtpErrors.reasonFor(534, "534 Policy requires SSL"))
        assertEquals(Reason.UNSUPPORTED, FtpErrors.reasonFor(502, "502 Command not implemented"))
        assertEquals(Reason.PROTOCOL, FtpErrors.reasonFor(451, "451 Local error"))
    }

    @Test
    fun `ftps data channel failure mentions session reuse`() {
        val e = FtpErrors.fromReply("Upload", 425, "425 Unable to build data connection", ftps = true)
        assertEquals(Reason.NETWORK, e.reason)
        assertTrue(e.message!!.contains("session reuse"))
        val plain = FtpErrors.fromReply("Upload", 425, "425 Unable to build data connection", ftps = false)
        assertTrue(!plain.message!!.contains("session reuse"))
    }

    @Test
    fun `io exceptions map to reasons`() {
        assertEquals(Reason.TIMEOUT, FtpErrors.fromIo("x", SocketTimeoutException("t")).reason)
        assertEquals(Reason.NETWORK, FtpErrors.fromIo("x", FTPConnectionClosedException("c")).reason)
        assertEquals(Reason.NETWORK, FtpErrors.fromIo("x", IOException("reset")).reason)
    }

    @Test
    fun `avbl reply parsing`() {
        assertEquals(123456789L, FtpErrors.parseAvbl(213, "213 123456789\r\n"))
        assertNull(FtpErrors.parseAvbl(502, "502 Command not implemented"))
        assertNull(FtpErrors.parseAvbl(213, "213 unknown"))
        assertNull(FtpErrors.parseAvbl(213, null))
    }

    @Test
    fun `certificate pin parsing`() {
        val hex = "AB:".repeat(31) + "CD"
        val pin = TlsTrust.parsePin(hex)!!
        assertEquals(32, pin.size)
        assertEquals(0xAB.toByte(), pin[0])
        assertEquals(0xCD.toByte(), pin[31])
        assertArrayEquals(pin, TlsTrust.parsePin(hex.replace(":", "").lowercase()))
        assertNull(TlsTrust.parsePin(null))
        assertNull(TlsTrust.parsePin("  "))
        expectReason(Reason.MISCONFIGURED) { TlsTrust.parsePin("abcd") }
        expectReason(Reason.MISCONFIGURED) { TlsTrust.parsePin("zz".repeat(32)) }
    }

    private fun expectReason(reason: Reason, block: () -> Unit) {
        try {
            block()
            fail("Expected StorageException($reason)")
        } catch (e: StorageException) {
            assertEquals(reason, e.reason)
        }
    }
}
