package ca.devdcs.backup.core.storage.ftp

import ca.devdcs.backup.core.storage.StorageException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust policy for FTPS and WebDAV over HTTPS.
 *
 * Exists because home NAS boxes commonly use self-signed certificates: by default the system trust
 * store validates the chain and hostname, and the user may instead pin the server's leaf
 * certificate by SHA-256 fingerprint (option [OPTION_TLS_PIN]). A pin replaces CA validation for
 * exactly that certificate only; nothing ever disables verification wholesale.
 */
internal object TlsTrust {

    /** Destination option: hex SHA-256 of the server leaf certificate (colons/spaces allowed). */
    const val OPTION_TLS_PIN: String = "tls_pin_sha256"

    /** Parses a fingerprint option; null when unset, MISCONFIGURED when malformed. */
    fun parsePin(raw: String?): ByteArray? {
        if (raw.isNullOrBlank()) return null
        val hex = raw.filterNot { it == ':' || it == ' ' || it == '-' }.lowercase()
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw StorageException(
                StorageException.Reason.MISCONFIGURED,
                "Certificate fingerprint must be 64 hex digits (SHA-256)",
            )
        }
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** The platform's default trust manager (system + user CAs per network security config). */
    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw StorageException(StorageException.Reason.PROTOCOL, "No X509 trust manager available")
    }

    /** System trust, or system trust plus an accepted pinned leaf when [pin] is set. */
    fun trustManager(pin: ByteArray?): X509TrustManager {
        val system = systemTrustManager()
        return if (pin == null) system else PinningTrustManager(pin, system)
    }

    /** Hostname verification: platform default, bypassed only for the pinned leaf certificate. */
    fun hostnameVerifier(pin: ByteArray?): HostnameVerifier {
        val default = HttpsURLConnection.getDefaultHostnameVerifier()
        if (pin == null) return default
        return HostnameVerifier { host, session -> leafMatches(session, pin) || default.verify(host, session) }
    }

    fun sha256(cert: X509Certificate): ByteArray = MessageDigest.getInstance("SHA-256").digest(cert.encoded)

    private fun leafMatches(session: SSLSession, pin: ByteArray): Boolean = try {
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
        leaf != null && MessageDigest.isEqual(sha256(leaf), pin)
    } catch (_: SSLPeerUnverifiedException) {
        false
    }

    /** Accepts the pinned leaf as-is; every other chain goes through normal CA validation. */
    private class PinningTrustManager(
        private val pin: ByteArray,
        private val delegate: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
            // An explicit pin is trusted even when expired: NAS self-signed certs often are.
            if (MessageDigest.isEqual(sha256(leaf), pin)) return
            delegate.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }
}
