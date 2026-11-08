package ca.devdcs.backup.core.storage.webdav

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.core.storage.ftp.TlsTrust
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.VaultException
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext

/**
 * [StorageBackend] for WebDAV (Nextcloud/ownCloud, NAS WebDAV shares, Apache mod_dav, nginx...).
 *
 * Exists because WebDAV is the most widely available HTTP file protocol on self-hosted storage.
 * Built on OkHttp; one shared connection pool, with per-session clients carrying timeouts, auth
 * and optional certificate pinning ([TlsTrust]).
 *
 * Authentication is sent preemptively (uploads are one-shot streams that cannot be replayed after
 * a 401 challenge): [Secret.Password] → HTTP Basic (username from config, else the secret);
 * [Secret.OAuthToken] → `Authorization: Bearer` (app tokens / OAuth); no credential → anonymous.
 * HTTP Digest is not supported: it needs a challenge round-trip per nonce that a streamed PUT
 * cannot satisfy; servers offering Digest (Apache, IIS) also accept Basic over HTTPS.
 */
@Singleton
class WebDavBackend @Inject constructor(
    private val vault: CredentialVault,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StorageBackend {

    override val types: Set<DestinationType> = setOf(DestinationType.WEBDAV)

    /** Shared pool/dispatcher; sessions derive their own configured client from it. */
    private val sharedClient: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    override suspend fun connect(destination: Destination): StorageSession {
        val config = destination.config
        val baseUrl = WebDavUrls.baseUrl(config)
        val pin = TlsTrust.parsePin(config.options[TlsTrust.OPTION_TLS_PIN])
        val authorization = authorization(destination)
        if (authorization != null && !baseUrl.isHttps) {
            DftbLog.w(TAG, "WebDAV credentials will be sent over plain HTTP to ${baseUrl.host}")
        }
        val connectS = seconds(config.options[OPTION_CONNECT_TIMEOUT_S], DEFAULT_CONNECT_TIMEOUT_S)
        val ioS = seconds(config.options[OPTION_IO_TIMEOUT_S], DEFAULT_IO_TIMEOUT_S)

        val builder = sharedClient.newBuilder()
            .connectTimeout(connectS, TimeUnit.SECONDS)
            .readTimeout(ioS, TimeUnit.SECONDS)
            .writeTimeout(ioS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // Uploads/downloads of large archives are unbounded.
            // Redirects are never followed: OkHttp rewrites redirected MOVE/DELETE/MKCOL into GET (a
            // false "success"), and https↔http hops would expose credentials. 3xx → MISCONFIGURED.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().header("User-Agent", USER_AGENT)
                if (authorization != null) request.header("Authorization", authorization)
                chain.proceed(request.build())
            }
        if (pin != null) {
            val trustManager = TlsTrust.trustManager(pin)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            builder.sslSocketFactory(context.socketFactory, trustManager)
            builder.hostnameVerifier(TlsTrust.hostnameVerifier(pin))
        }
        val session = WebDavSession(destination, builder.build(), baseUrl, io)
        try {
            session.verifyBase()
        } catch (e: Throwable) {
            session.close()
            throw e
        }
        DftbLog.i(TAG, "Connected to WebDAV at ${baseUrl.host}:${baseUrl.port}")
        return session
    }

    private suspend fun authorization(destination: Destination): String? {
        val ref = destination.credentialRef?.takeIf { it.isNotBlank() } ?: return null
        val secret = try {
            vault.load(ref) ?: throw StorageException(Reason.AUTH, "Saved credential is missing; re-enter it")
        } catch (e: VaultException) {
            throw StorageException(Reason.AUTH, "Saved credential cannot be read; re-enter it", e)
        }
        return when (secret) {
            is Secret.Password -> {
                val user = destination.config.username?.takeIf { it.isNotBlank() }
                    ?: secret.username.orEmpty()
                Credentials.basic(user, secret.password, Charsets.UTF_8)
            }
            is Secret.OAuthToken -> "Bearer ${secret.accessToken}"
            else -> throw StorageException(Reason.MISCONFIGURED, "WebDAV needs a password or token credential, found ${secret.kind}")
        }
    }

    private fun seconds(raw: String?, default: Long): Long =
        raw?.trim()?.toLongOrNull()?.takeIf { it in 1..3600 } ?: default

    companion object {
        private const val TAG = "WebDavBackend"
        private const val USER_AGENT = "DFTB-Backup"

        /** Option key: `"https"` (default) or `"http"`, used when host carries no scheme. */
        const val OPTION_SCHEME: String = WebDavUrls.OPTION_SCHEME

        /** Option key: TCP connect timeout in seconds (1..3600). */
        const val OPTION_CONNECT_TIMEOUT_S: String = "connect_timeout_s"

        /** Option key: socket read/write timeout in seconds (1..3600). */
        const val OPTION_IO_TIMEOUT_S: String = "io_timeout_s"

        /** Option key for a pinned server certificate; see [TlsTrust]. */
        const val OPTION_TLS_PIN: String = TlsTrust.OPTION_TLS_PIN

        private const val DEFAULT_CONNECT_TIMEOUT_S = 20L
        private const val DEFAULT_IO_TIMEOUT_S = 60L
    }
}
