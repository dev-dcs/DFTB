package ca.devdcs.backup.core.storage.smb

import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.core.storage.ssh.NetCredentials
import ca.devdcs.backup.core.storage.ssh.StorageStrings
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StorageBackend] for SMB destinations (Windows shares, Samba, NAS appliances) on smbj.
 *
 * Config: `host`, `port` (445), `basePath` = `/<share>/<folder...>` unless `options["share"]` names
 * the share, `options["domain"]` for the NTLM domain, `options["encrypt"]="true"` to require SMB 3
 * encryption, `options["guest"]="true"` for guest access when no credential is set. Dialects SMB
 * 2.0.2–3.1.1 are offered; signing is enabled and used whenever the server negotiates or requires
 * it. Crypto goes through smbj's BouncyCastle provider so AES-CMAC/GCM signing and encryption do not
 * depend on Android's JCE.
 */
@Singleton
class SmbBackend @Inject constructor(
    private val vault: CredentialVault,
    private val strings: StorageStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StorageBackend {

    override val types: Set<DestinationType> = setOf(DestinationType.SMB)

    override suspend fun connect(destination: Destination): StorageSession = withContext(io) {
        val config = destination.config
        val host = NetCredentials.requireHost(destination, strings)
        val port = config.port?.takeIf { it in 1..65535 } ?: SMBClient.DEFAULT_PORT
        val location = SmbLocation.parse(config.basePath, config.options[OPTION_SHARE])
            ?: throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_smb_missing_share))
        val encrypt = config.options[OPTION_ENCRYPT].toBoolean()
        val auth = authContext(destination)

        val smbConfig = SmbConfig.builder()
            .withDialects(
                SMB2Dialect.SMB_3_1_1,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_2_0_2,
            )
            .withSecurityProvider(BCSecurityProvider())
            .withSigningEnabled(true)
            .withSigningRequired(false)
            .withEncryptData(encrypt)
            .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(smbConfig)
        var connection: Connection? = null
        var session: Session? = null
        try {
            val conn = runInterruptible { client.connect(host, port) }
            connection = conn
            val dialect = conn.negotiatedProtocol.dialect
            if (encrypt && !dialect.isSmb3x) {
                throw StorageException(StorageException.Reason.UNSUPPORTED, strings.get(R.string.storage_smb_encryption_unsupported))
            }
            val smbSession = runInterruptible { conn.authenticate(auth) }
            session = smbSession
            val share = runInterruptible { smbSession.connectShare(location.share) }
            if (share !is DiskShare) {
                runCatching { share.close() }
                throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_smb_not_disk_share, location.share))
            }
            DftbLog.i(TAG, "SMB session open: dialect=$dialect share=${location.share} encrypt=$encrypt")
            SmbSession(destination, client, conn, smbSession, share, location, strings, io)
        } catch (e: CancellationException) {
            closeQuietly(client, connection, session)
            throw e
        } catch (e: Exception) {
            closeQuietly(client, connection, session)
            throw connectFailure(e, host)
        }
    }

    private suspend fun authContext(destination: Destination): AuthenticationContext {
        val domain = destination.config.options[OPTION_DOMAIN]?.trim()?.takeIf { it.isNotEmpty() }
        val secret = NetCredentials.load(vault, destination, strings, required = false)
        return when (secret) {
            null -> if (destination.config.options[OPTION_GUEST].toBoolean()) AuthenticationContext.guest() else AuthenticationContext.anonymous()
            is Secret.Password -> {
                val username = destination.config.username?.trim()?.takeIf { it.isNotEmpty() }
                    ?: secret.username?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_net_missing_username))
                AuthenticationContext(username, secret.password.toCharArray(), domain)
            }
            else -> throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_net_credential_wrong_kind))
        }
    }

    private fun connectFailure(e: Exception, host: String): StorageException {
        if (e is StorageException) return e
        val reason = SmbErrors.reasonFor(e)
        val message = if (reason == StorageException.Reason.AUTH) {
            strings.get(R.string.storage_smb_auth_failed)
        } else {
            strings.get(R.string.storage_net_connect_failed, host, e.message ?: e.javaClass.simpleName)
        }
        return StorageException(reason, message, e)
    }

    private fun closeQuietly(client: SMBClient, connection: Connection?, session: Session?) {
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client.close() }
    }

    private companion object {
        const val TAG = "SmbBackend"
        const val OPTION_SHARE = "share"
        const val OPTION_DOMAIN = "domain"
        const val OPTION_ENCRYPT = "encrypt"
        const val OPTION_GUEST = "guest"
        const val TIMEOUT_SECONDS = 60L
        const val SO_TIMEOUT_SECONDS = 120L
    }
}
