package ca.devdcs.backup.core.storage.smb

import ca.devdcs.backup.core.storage.StorageException.Reason
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class SmbParsingTest {

    @Test
    fun `share is the first path segment`() {
        val loc = SmbLocation.parse("/Backups/phone/daily", null)!!
        assertEquals("Backups", loc.share)
        assertEquals(listOf("phone", "daily"), loc.baseSegments)
        assertEquals("phone\\daily\\a\\b.tar", loc.smbPath("a/b.tar"))
        assertEquals("phone\\daily", loc.smbPath(""))
    }

    @Test
    fun `backslash input and share option`() {
        assertEquals(SmbLocation("Backups", listOf("phone")), SmbLocation.parse("Backups\\phone", null))
        val opt = SmbLocation.parse("/phone", "\\\\Backups\\")!!
        assertEquals("Backups", opt.share)
        assertEquals("phone\\x", opt.smbPath("x"))
        assertEquals("", SmbLocation.parse(null, "Share")!!.smbPath(""))
        assertEquals("phone\\a\\b", opt.smbPath("a\\b"))
        assertNull(SmbLocation.parse("/", null))
        assertNull(SmbLocation.parse(null, "  "))
    }

    @Test
    fun `nt status mapping`() {
        assertEquals(Reason.AUTH, SmbErrors.reasonFor(NtStatus.STATUS_LOGON_FAILURE))
        assertEquals(Reason.PERMISSION, SmbErrors.reasonFor(NtStatus.STATUS_ACCESS_DENIED))
        assertEquals(Reason.NOT_FOUND, SmbErrors.reasonFor(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND))
        assertEquals(Reason.NOT_FOUND, SmbErrors.reasonFor(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND))
        assertEquals(Reason.NO_SPACE, SmbErrors.reasonFor(NtStatus.STATUS_DISK_FULL))
        assertEquals(Reason.PERMISSION, SmbErrors.reasonFor(NtStatus.STATUS_DIRECTORY_NOT_EMPTY))
        assertEquals(Reason.PROTOCOL, SmbErrors.reasonFor(NtStatus.STATUS_SHARING_VIOLATION))
    }

    @Test
    fun `exception mapping walks causes`() {
        val denied = SMBApiException(NtStatus.STATUS_ACCESS_DENIED.value, SMB2MessageCommandCode.SMB2_CREATE, null)
        assertEquals(Reason.PERMISSION, SmbErrors.reasonFor(denied))
        assertEquals(Reason.PERMISSION, SmbErrors.reasonFor(SMBRuntimeException(denied)))
        assertEquals(Reason.NETWORK, SmbErrors.reasonFor(SMBRuntimeException(TransportException("closed"))))
        assertEquals(Reason.TIMEOUT, SmbErrors.reasonFor(TransportException(SocketTimeoutException())))
        assertEquals(Reason.PROTOCOL, SmbErrors.reasonFor(SMBRuntimeException("odd")))
    }

    @Test
    fun `not found and collision helpers`() {
        val missing = SMBApiException(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.value, SMB2MessageCommandCode.SMB2_CREATE, null)
        val badShare = SMBApiException(NtStatus.STATUS_BAD_NETWORK_NAME.value, SMB2MessageCommandCode.SMB2_CREATE, null)
        val collision = SMBApiException(NtStatus.STATUS_OBJECT_NAME_COLLISION.value, SMB2MessageCommandCode.SMB2_CREATE, null)
        assertTrue(SmbErrors.isNotFound(missing))
        assertFalse(SmbErrors.isNotFound(badShare))
        assertTrue(SmbErrors.isNameCollision(collision))
        assertFalse(SmbErrors.isNameCollision(missing))
    }
}
