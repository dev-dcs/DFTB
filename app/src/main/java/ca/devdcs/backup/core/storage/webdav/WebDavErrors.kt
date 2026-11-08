package ca.devdcs.backup.core.storage.webdav

import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * HTTP status and I/O failure mapping for WebDAV.
 *
 * Exists so retry policy and UI messages depend on one tested table. Notable choices: 409 on
 * PUT/MKCOL means a missing parent collection (NOT_FOUND); 405 on PROPFIND means the URL is not a
 * WebDAV endpoint (UNSUPPORTED); 3xx means the configured URL is wrong (e.g. http→https).
 */
internal object WebDavErrors {

    fun reasonForStatus(code: Int): Reason = when (code) {
        401 -> Reason.AUTH
        403 -> Reason.PERMISSION
        404, 410, 409 -> Reason.NOT_FOUND
        405, 501 -> Reason.UNSUPPORTED
        507, 413 -> Reason.NO_SPACE
        408 -> Reason.TIMEOUT
        429 -> Reason.NETWORK
        in 300..399 -> Reason.MISCONFIGURED
        in 500..599 -> Reason.PROTOCOL
        else -> Reason.MISCONFIGURED
    }

    fun fromStatus(operation: String, code: Int, message: String?): StorageException {
        val detail = message?.trim().orEmpty()
        return StorageException(
            reasonForStatus(code),
            "$operation failed: HTTP $code${if (detail.isEmpty()) "" else " $detail"}",
        )
    }

    /** [fromStatus] for a received [response]; a redirect names its target so the user can fix the URL. */
    fun fromResponse(operation: String, response: Response): StorageException {
        val location = if (response.code in 300..399) response.header("Location") else null
        val detail = if (location != null) "${response.message} (redirects to $location; update the server address)" else response.message
        return fromStatus(operation, response.code, detail)
    }

    fun fromIo(operation: String, e: IOException): StorageException {
        val reason = when (e) {
            is SocketTimeoutException -> Reason.TIMEOUT
            is SSLPeerUnverifiedException -> Reason.HOST_KEY
            is SSLHandshakeException ->
                if (generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }) Reason.HOST_KEY
                else Reason.NETWORK
            // OkHttp reports call/read timeouts as InterruptedIOException("timeout").
            is InterruptedIOException -> Reason.TIMEOUT
            else -> Reason.NETWORK
        }
        return StorageException(reason, "$operation failed: ${e.message ?: e.javaClass.simpleName}", e)
    }
}
