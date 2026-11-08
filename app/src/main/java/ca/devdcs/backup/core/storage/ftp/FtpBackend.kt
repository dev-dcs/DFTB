package ca.devdcs.backup.core.storage.ftp

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.VaultException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [StorageBackend] for plain FTP and FTPS (Apache commons-net).
 *
 * Exists because FTP(S) is still the lowest-common-denominator protocol on routers, NAS boxes and
 * shared hosting. Sessions use passive mode, binary type and UTF-8 control encoding. FTPS is
 * explicit (`AUTH TLS`) by default, implicit when `options["ftps_mode"] = "implicit"`, and always
 * protects the data channel (`PBSZ 0` + `PROT P`).
 *
 * Credentials: username from [ca.devdcs.backup.domain.model.DestinationConfig.username] (falling
 * back to the [Secret.Password.username]), password from the vault. No credential means anonymous.
 */
@Singleton
class FtpBackend @Inject constructor(
    private val vault: CredentialVault,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StorageBackend {

    override val types: Set<DestinationType> = setOf(DestinationType.FTP, DestinationType.FTPS)

    override suspend fun connect(destination: Destination): StorageSession {
        val config = destination.config
        val host = config.host?.trim().orEmpty()
        if (host.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "FTP host is not set")
        val ftps = destination.type == DestinationType.FTPS
        val implicit = ftps && config.options[OPTION_FTPS_MODE].equals(FTPS_MODE_IMPLICIT, ignoreCase = true)
        val port = config.port ?: if (implicit) DEFAULT_IMPLICIT_PORT else DEFAULT_PORT
        val connectMs = timeoutMillis(config.options[OPTION_CONNECT_TIMEOUT_S], DEFAULT_CONNECT_TIMEOUT_S)
        val dataMs = timeoutMillis(config.options[OPTION_DATA_TIMEOUT_S], DEFAULT_DATA_TIMEOUT_S)
        val pin = TlsTrust.parsePin(config.options[TlsTrust.OPTION_TLS_PIN])
        val (user, password) = credentials(destination)

        return withContext(io) {
            val client: FTPClient = if (ftps) {
                FTPSClient("TLS", implicit).apply {
                    trustManager = TlsTrust.trustManager(pin)
                    hostnameVerifier = TlsTrust.hostnameVerifier(pin)
                }
            } else {
                FTPClient()
            }
            try {
                client.setControlEncoding(Charsets.UTF_8.name())
                client.connectTimeout = connectMs
                client.defaultTimeout = dataMs
                client.setDataTimeout(Duration.ofMillis(dataMs.toLong()))
                client.connect(host, port)
                if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                    throw FtpErrors.fromReply("Connect", client.replyCode, client.replyString, ftps)
                }
                client.soTimeout = dataMs
                if (!client.login(user, password)) {
                    throw StorageException(Reason.AUTH, "FTP login rejected: ${client.replyString?.trim().orEmpty()}")
                }
                if (client is FTPSClient) {
                    try {
                        client.execPBSZ(0)
                        client.execPROT("P")
                    } catch (e: SSLException) {
                        throw StorageException(Reason.MISCONFIGURED, "Server refused a protected data channel (PROT P)", e)
                    }
                }
                // Best effort: some servers need UTF-8 switched on explicitly; failure is harmless.
                client.sendCommand("OPTS", "UTF8 ON")
                client.enterLocalPassiveMode()
                if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                    throw FtpErrors.fromReply("TYPE I", client.replyCode, client.replyString, ftps)
                }
                val pwd = client.printWorkingDirectory()
                val root = FtpSession.resolveRoot(config.basePath, pwd)
                val mlst = runCatching { client.hasFeature("MLST") }.getOrDefault(false)
                DftbLog.i(TAG, "Connected to ${destination.type.storageKey}://$host:$port (mlst=$mlst)")
                FtpSession(destination, client, root, ftps, mlst, io)
            } catch (e: CancellationException) {
                quietDisconnect(client)
                throw e
            } catch (e: StorageException) {
                quietDisconnect(client)
                throw e
            } catch (e: IOException) {
                quietDisconnect(client)
                throw FtpErrors.fromIo("Connect to $host:$port", e)
            } catch (e: RuntimeException) {
                quietDisconnect(client)
                throw StorageException(Reason.PROTOCOL, "FTP connect failed: ${e.message}", e)
            }
        }
    }

    private suspend fun credentials(destination: Destination): Pair<String, String> {
        val ref = destination.credentialRef
        val secret = if (ref.isNullOrBlank()) {
            null
        } else {
            try {
                vault.load(ref) ?: throw StorageException(Reason.AUTH, "Saved credential is missing; re-enter the password")
            } catch (e: VaultException) {
                throw StorageException(Reason.AUTH, "Saved credential cannot be read; re-enter the password", e)
            }
        }
        return when (secret) {
            null -> (destination.config.username?.takeIf { it.isNotBlank() } ?: ANONYMOUS_USER) to ANONYMOUS_PASSWORD
            is Secret.Password -> {
                val user = destination.config.username?.takeIf { it.isNotBlank() }
                    ?: secret.username?.takeIf { it.isNotBlank() }
                    ?: ANONYMOUS_USER
                user to secret.password
            }
            else -> throw StorageException(Reason.MISCONFIGURED, "FTP needs a password credential, found ${secret.kind}")
        }
    }

    private fun timeoutMillis(raw: String?, defaultSeconds: Int): Int {
        val seconds = raw?.trim()?.toIntOrNull()?.takeIf { it in 1..3600 } ?: defaultSeconds
        return seconds * 1000
    }

    private fun quietDisconnect(client: FTPClient) {
        try {
            if (client.isConnected) client.disconnect()
        } catch (_: IOException) {
            // Already failing; the earlier build error is what matters.
        }
    }

    companion object {
        private const val TAG = "FtpBackend"

        /** Option key: `"explicit"` (default, AUTH TLS on port 21) or `"implicit"` (TLS from connect, port 990). */
        const val OPTION_FTPS_MODE: String = "ftps_mode"
        const val FTPS_MODE_EXPLICIT: String = "explicit"
        const val FTPS_MODE_IMPLICIT: String = "implicit"

        /** Option key: TCP connect timeout in seconds (1..3600). */
        const val OPTION_CONNECT_TIMEOUT_S: String = "connect_timeout_s"

        /** Option key: control/data socket read timeout in seconds (1..3600). */
        const val OPTION_DATA_TIMEOUT_S: String = "data_timeout_s"

        /** Option key for a pinned server certificate; see [TlsTrust]. */
        const val OPTION_TLS_PIN: String = TlsTrust.OPTION_TLS_PIN

        const val DEFAULT_PORT: Int = 21
        const val DEFAULT_IMPLICIT_PORT: Int = 990
        private const val DEFAULT_CONNECT_TIMEOUT_S = 20
        private const val DEFAULT_DATA_TIMEOUT_S = 60
        private const val ANONYMOUS_USER = "anonymous"
        private const val ANONYMOUS_PASSWORD = "dftb@"
    }
}
