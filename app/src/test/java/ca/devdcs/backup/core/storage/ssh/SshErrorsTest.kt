package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException.Reason
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

class SshErrorsTest {

    @Test
    fun `host key and auth failures are not retryable reasons`() {
        assertEquals(Reason.HOST_KEY, SshErrors.reasonFor(TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, "bad key")))
        assertEquals(Reason.AUTH, SshErrors.reasonFor(UserAuthException("Exhausted available authentication methods")))
        assertEquals(Reason.AUTH, SshErrors.reasonFor(TransportException(DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE)))
    }

    @Test
    fun `sftp status codes map to contract reasons`() {
        assertEquals(Reason.NOT_FOUND, SshErrors.reasonFor(SFTPException(Response.StatusCode.NO_SUCH_FILE, "x")))
        assertEquals(Reason.PERMISSION, SshErrors.reasonFor(SFTPException(Response.StatusCode.PERMISSION_DENIED, "x")))
        assertEquals(Reason.NO_SPACE, SshErrors.reasonFor(SFTPException(Response.StatusCode.NO_SPACE_ON_FILESYSTEM, "x")))
        assertEquals(Reason.UNSUPPORTED, SshErrors.reasonFor(SFTPException(Response.StatusCode.OP_UNSUPPORTED, "x")))
        assertEquals(Reason.PROTOCOL, SshErrors.reasonFor(SFTPException(Response.StatusCode.FAILURE, "x")))
        assertEquals(Reason.PERMISSION, SshErrors.reasonFor(SFTPException(Response.StatusCode.DIR_NOT_EMPTY, "x")))
    }

    @Test
    fun `network and timeout failures`() {
        assertEquals(Reason.NETWORK, SshErrors.reasonFor(UnknownHostException("nas")))
        assertEquals(Reason.NETWORK, SshErrors.reasonFor(ConnectException("refused")))
        assertEquals(Reason.TIMEOUT, SshErrors.reasonFor(SocketTimeoutException("connect timed out")))
        assertEquals(Reason.TIMEOUT, SshErrors.reasonFor(ConnectionException("Timeout expired", TimeoutException())))
        assertEquals(Reason.NETWORK, SshErrors.reasonFor(TransportException(DisconnectReason.CONNECTION_LOST)))
        assertEquals(Reason.NETWORK, SshErrors.reasonFor(TransportException("broken", IOException("Broken pipe"))))
        assertEquals(Reason.NETWORK, SshErrors.reasonFor(IOException("reset")))
    }

    @Test
    fun `statvfs uses available blocks times fragment size`() {
        assertEquals(SpaceInfo(freeBytes = 250L * 4096, totalBytes = 1000L * 4096), SftpSession.statvfsToSpace(4096, 1000, 250))
    }
}
