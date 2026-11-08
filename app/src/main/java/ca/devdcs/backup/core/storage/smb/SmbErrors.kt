package ca.devdcs.backup.core.storage.smb

import ca.devdcs.backup.core.storage.StorageException
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Pure mapping from smbj failures to [StorageException.Reason]. Exists so NTSTATUS codes (logon
 * failure, access denied, missing path, disk full...) drive the UI message and retry policy from a
 * single tested table.
 */
internal object SmbErrors {

    fun reasonFor(status: NtStatus?): StorageException.Reason = when (status) {
        NtStatus.STATUS_LOGON_FAILURE,
        NtStatus.STATUS_PASSWORD_EXPIRED,
        NtStatus.STATUS_ACCOUNT_DISABLED,
        NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
        -> StorageException.Reason.AUTH
        NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> StorageException.Reason.PERMISSION
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_NO_SUCH_FILE,
        NtStatus.STATUS_NOT_FOUND,
        NtStatus.STATUS_BAD_NETWORK_NAME,
        -> StorageException.Reason.NOT_FOUND
        NtStatus.STATUS_DISK_FULL -> StorageException.Reason.NO_SPACE
        NtStatus.STATUS_NOT_SUPPORTED -> StorageException.Reason.UNSUPPORTED
        NtStatus.STATUS_IO_TIMEOUT -> StorageException.Reason.TIMEOUT
        NtStatus.STATUS_BAD_NETWORK_PATH,
        NtStatus.STATUS_NETWORK_NAME_DELETED,
        NtStatus.STATUS_USER_SESSION_DELETED,
        NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
        -> StorageException.Reason.NETWORK
        else -> StorageException.Reason.PROTOCOL
    }

    fun reasonFor(error: Throwable): StorageException.Reason {
        if (error is StorageException) return error.reason
        val chain = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CHAIN).toList()
        chain.firstOrNull { it is SMBApiException }?.let { return reasonFor((it as SMBApiException).status) }
        if (chain.any { it is TimeoutException || it is SocketTimeoutException }) return StorageException.Reason.TIMEOUT
        // SMBRuntimeException without an IO cause is a protocol-level fault.
        return if (chain.any { it is TransportException || it is IOException }) {
            StorageException.Reason.NETWORK
        } else {
            StorageException.Reason.PROTOCOL
        }
    }

    /** True when [error] says the path is absent (used to make stat/delete tolerate missing paths). */
    fun isNotFound(error: Throwable): Boolean =
        error is SMBApiException && reasonFor(error.status) == StorageException.Reason.NOT_FOUND &&
            error.status != NtStatus.STATUS_BAD_NETWORK_NAME

    fun isNameCollision(error: Throwable): Boolean =
        error is SMBApiException && error.status == NtStatus.STATUS_OBJECT_NAME_COLLISION

    private const val MAX_CHAIN = 8
}
