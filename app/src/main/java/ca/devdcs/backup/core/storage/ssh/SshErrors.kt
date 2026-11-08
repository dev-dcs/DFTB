package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.core.storage.StorageException
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Pure mapping from sshj / socket exceptions to [StorageException.Reason]. Exists so retry policy
 * (network vs. auth vs. host-key) is decided in one tested place instead of at every call site.
 */
internal object SshErrors {

    fun reasonFor(error: Throwable): StorageException.Reason {
        if (error is StorageException) return error.reason
        if (causeChain(error).any { it is TimeoutException || it is SocketTimeoutException }) {
            return StorageException.Reason.TIMEOUT
        }
        return when (error) {
            is SFTPException -> sftpReason(error.statusCode)
            is UserAuthException -> StorageException.Reason.AUTH
            is SSHException -> when (error.disconnectReason) {
                DisconnectReason.HOST_KEY_NOT_VERIFIABLE -> StorageException.Reason.HOST_KEY
                DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE,
                DisconnectReason.AUTH_CANCELLED_BY_USER,
                DisconnectReason.ILLEGAL_USER_NAME,
                -> StorageException.Reason.AUTH
                DisconnectReason.CONNECTION_LOST,
                DisconnectReason.TOO_MANY_CONNECTIONS,
                DisconnectReason.HOST_NOT_ALLOWED_TO_CONNECT,
                -> StorageException.Reason.NETWORK
                else -> {
                    val cause = error.cause
                    if (cause != null && cause !== error && cause is IOException && cause !is SSHException) {
                        reasonFor(cause)
                    } else {
                        StorageException.Reason.PROTOCOL
                    }
                }
            }
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> StorageException.Reason.NETWORK
            is IOException -> StorageException.Reason.NETWORK
            else -> StorageException.Reason.PROTOCOL
        }
    }

    fun sftpReason(status: Response.StatusCode?): StorageException.Reason = when (status) {
        Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> StorageException.Reason.NOT_FOUND
        Response.StatusCode.PERMISSION_DENIED,
        Response.StatusCode.WRITE_PROTECT,
        Response.StatusCode.CANNOT_DELETE,
        Response.StatusCode.DIR_NOT_EMPTY,
        ->
            StorageException.Reason.PERMISSION
        Response.StatusCode.NO_SPACE_ON_FILESYSTEM, Response.StatusCode.QUOTA_EXCEEDED -> StorageException.Reason.NO_SPACE
        Response.StatusCode.OP_UNSUPPORTED -> StorageException.Reason.UNSUPPORTED
        Response.StatusCode.NO_CONNECTION, Response.StatusCode.CONNECITON_LOST -> StorageException.Reason.NETWORK
        else -> StorageException.Reason.PROTOCOL
    }

    /** Wrap [error] (never a [kotlinx.coroutines.CancellationException]; callers rethrow those first). */
    fun wrap(error: Throwable, message: String): StorageException =
        error as? StorageException ?: StorageException(reasonFor(error), message, error)

    private fun causeChain(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { current -> current.cause?.takeIf { it !== current } }.take(MAX_CHAIN)

    private const val MAX_CHAIN = 8
}
