package ca.devdcs.backup.core.storage.ftp

import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import org.apache.commons.net.ftp.FTPConnectionClosedException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Translates FTP reply codes and I/O failures into [StorageException]s.
 *
 * Exists so the pipeline's retry policy and the UI message depend on one well-tested table rather
 * than on reply-code checks scattered through [FtpSession]. Pure logic, JVM-unit-tested.
 */
internal object FtpErrors {

    private val NOT_FOUND_HINTS = listOf("no such", "not found", "not exist", "doesn't exist", "cannot find", "can't find")

    /** Reason for a negative FTP reply [code] whose text is [reply]. */
    fun reasonFor(code: Int, reply: String?): Reason {
        val text = reply.orEmpty().lowercase()
        return when (code) {
            530, 331, 332, 430 -> Reason.AUTH
            550 -> if (NOT_FOUND_HINTS.any { it in text }) Reason.NOT_FOUND else Reason.PERMISSION
            552, 452 -> Reason.NO_SPACE
            553 -> Reason.PERMISSION
            421, 425, 426 -> Reason.NETWORK
            // TLS policy refusals: server demands/refuses protection levels this config did not use.
            522, 533, 534, 536 -> Reason.MISCONFIGURED
            502, 504 -> Reason.UNSUPPORTED
            else -> Reason.PROTOCOL
        }
    }

    /** Exception for a failed [operation] given the client's last reply. */
    fun fromReply(operation: String, code: Int, reply: String?, ftps: Boolean = false): StorageException {
        val reason = reasonFor(code, reply)
        val base = "$operation failed: ${reply?.trim().orEmpty().ifEmpty { "reply $code" }}"
        // vsftpd/FileZilla may require TLS session reuse on data connections, which the Java TLS
        // stack cannot guarantee; surface that as the likely cause instead of a bare 425/522.
        val message = if (ftps && (code == 425 || code == 522)) {
            "$base (the server may require TLS session reuse on data connections; disable that server option)"
        } else {
            base
        }
        return StorageException(reason, message)
    }

    /** Exception for an I/O failure during [operation]. */
    fun fromIo(operation: String, e: IOException): StorageException {
        val reason = when (e) {
            is SocketTimeoutException -> Reason.TIMEOUT
            is SSLPeerUnverifiedException -> Reason.HOST_KEY
            is SSLHandshakeException ->
                if (generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }) Reason.HOST_KEY
                else Reason.NETWORK
            is FTPConnectionClosedException -> Reason.NETWORK
            is UnknownHostException -> Reason.NETWORK
            is InterruptedIOException -> Reason.TIMEOUT
            else -> Reason.NETWORK
        }
        return StorageException(reason, "$operation failed: ${e.message ?: e.javaClass.simpleName}", e)
    }

    /**
     * Free bytes from an `AVBL` reply (draft-peterson-streamlined-ftp-command-extensions), e.g.
     * `213 1234567`. Null when the reply is not a 213 carrying a non-negative number.
     */
    fun parseAvbl(code: Int, reply: String?): Long? {
        if (code != 213 || reply == null) return null
        val line = reply.trim().lineSequence().lastOrNull()?.trim() ?: return null
        val payload = line.removePrefix("213").trimStart('-', ' ')
        val number = payload.takeWhile { it.isDigit() }
        return number.toLongOrNull()?.takeIf { it >= 0 }
    }
}
