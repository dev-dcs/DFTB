package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StorageBackend] for the SFTP and SSH destination types, on sshj.
 *
 * Exists because SSH servers are the most common self-hosted backup target. Authentication uses the
 * vault secret: a password ([Secret.Password], tried as `password` then `keyboard-interactive`) or
 * a private key ([Secret.SshKey], PEM / OpenSSH / PKCS#8 / PuTTY, optional passphrase). Host keys
 * are trust-on-first-use via [SshKnownHosts]; a changed key fails with HOST_KEY and is never
 * accepted automatically.
 *
 * SFTP type: all operations over the SFTP subsystem, capacity via `statvfs@openssh.com`.
 * SSH type: file data still uses the SFTP subsystem whenever the server offers it (fast, binary-safe,
 * resumable); capacity comes from `df -Pk` over an exec channel. If the subsystem is refused the
 * session falls back to POSIX shell commands ([SshExecSession]), which requires a POSIX login shell.
 */
@Singleton
class SftpBackend @Inject constructor(
    private val vault: CredentialVault,
    private val knownHosts: SshKnownHosts,
    private val strings: StorageStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StorageBackend {

    override val types: Set<DestinationType> = setOf(DestinationType.SFTP, DestinationType.SSH)

    override suspend fun connect(destination: Destination): StorageSession = withContext(io) {
        val config = destination.config
        val host = NetCredentials.requireHost(destination, strings)
        val port = config.port?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        val secret = NetCredentials.load(vault, destination, strings, required = true)
            ?: throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_net_missing_credential))
        val username = config.username?.trim()?.takeIf { it.isNotEmpty() }
            ?: (secret as? Secret.Password)?.username?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_net_missing_username))

        SecurityProviders.ensureBouncyCastle()
        val sshConfig = DefaultConfig().apply {
            keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
            timeoutMs = REQUEST_TIMEOUT_MILLIS
        }
        val client = SSHClient(sshConfig)
        val verifier = TofuHostKeyVerifier(knownHosts, KnownHostsFormat.hostId(host, port))
        client.addHostKeyVerifier(verifier)
        client.connectTimeout = CONNECT_TIMEOUT_MILLIS
        // Must be set before connect(): sshj starts the keep-alive thread only if enabled at connect.
        client.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS

        try {
            runInterruptible { client.connect(host, port) }
            runInterruptible { authenticate(client, username, secret) }
            openSession(destination, client)
        } catch (e: CancellationException) {
            runCatching { client.disconnect() }
            throw e
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw connectFailure(e, host, verifier)
        }
    }

    private suspend fun openSession(destination: Destination, client: SSHClient): StorageSession {
        val isSshType = destination.type == DestinationType.SSH
        val exec = SshExec(client, io, strings)
        val configuredBase = destination.config.basePath?.trim().orEmpty()
        val sftp: SFTPClient? = try {
            runInterruptible { client.newSFTPClient() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!isSshType) throw e
            DftbLog.w(TAG, "SFTP subsystem refused; falling back to shell commands", e)
            null
        }
        if (sftp != null) {
            sftp.getSFTPEngine().timeoutMs = REQUEST_TIMEOUT_MILLIS
            val base = try {
                resolveBase(configuredBase) { runInterruptible { sftp.canonicalize(".") } }
            } catch (e: Exception) {
                runCatching { sftp.close() }
                throw e
            }
            DftbLog.i(TAG, "SFTP session open (v${sftp.version()}), base=$base")
            return SftpSession(destination, client, sftp, base, spaceViaDf = isSshType, exec, strings, io)
        }
        val base = resolveBase(configuredBase) {
            val pwd = exec.run("pwd", PWD_TIMEOUT_MILLIS)
            pwd.stdout.firstOrNull()?.trim()?.takeIf { pwd.exitCode == 0 && it.startsWith("/") }
                ?: throw StorageException(StorageException.Reason.UNSUPPORTED, strings.get(R.string.storage_ssh_no_shell))
        }
        DftbLog.i(TAG, "SSH exec session open, base=$base")
        return SshExecSession(destination, client, exec, base, strings, io)
    }

    /** Absolute base: the configured absolute path, or a relative one under the login directory. */
    private suspend fun resolveBase(configured: String, home: suspend () -> String): String =
        if (configured.startsWith("/")) RemotePaths.join(configured, "") else RemotePaths.join(home(), configured)

    private fun authenticate(client: SSHClient, username: String, secret: Secret) {
        when (secret) {
            is Secret.Password -> client.auth(
                username,
                AuthPassword(StaticPassword(secret.password)),
                AuthKeyboardInteractive(PasswordResponseProvider(StaticPassword(secret.password))),
            )
            is Secret.SshKey -> {
                val keys = try {
                    client.loadKeys(secret.privateKeyPem, secret.publicKey, secret.passphrase?.let { StaticPassword(it) })
                } catch (e: IOException) {
                    throw StorageException(StorageException.Reason.AUTH, strings.get(R.string.storage_ssh_key_unreadable), e)
                }
                client.authPublickey(username, keys)
            }
            else -> throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_net_credential_wrong_kind))
        }
    }

    private fun connectFailure(e: Exception, host: String, verifier: TofuHostKeyVerifier): StorageException {
        if (e is StorageException) return e
        verifier.rejectedFingerprint?.let { fingerprint ->
            return StorageException(StorageException.Reason.HOST_KEY, strings.get(R.string.storage_ssh_host_key_changed, host, fingerprint), e)
        }
        if (e is UserAuthException) {
            return StorageException(StorageException.Reason.AUTH, strings.get(R.string.storage_ssh_auth_failed), e)
        }
        val reason = SshErrors.reasonFor(e)
        val message = if (reason == StorageException.Reason.AUTH) {
            strings.get(R.string.storage_ssh_auth_failed)
        } else {
            strings.get(R.string.storage_net_connect_failed, host, e.message ?: e.javaClass.simpleName)
        }
        return StorageException(reason, message, e)
    }

    /**
     * A [PasswordFinder] that can answer more than once (password then keyboard-interactive, or a
     * key format that asks twice). Never retries a wrong value, so a bad password fails fast.
     */
    private class StaticPassword(private val value: String) : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = value.toCharArray()
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    private companion object {
        const val TAG = "SftpBackend"
        const val DEFAULT_PORT = 22
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val REQUEST_TIMEOUT_MILLIS = 60_000
        const val KEEPALIVE_SECONDS = 15
        const val PWD_TIMEOUT_MILLIS = 30_000L
    }
}
